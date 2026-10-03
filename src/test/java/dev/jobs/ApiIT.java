package dev.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jobs.support.IntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Over real HTTP. The application's worker is off, so jobs stay queued and can be inspected. */
class ApiIT extends IntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private record Response(int status, JsonNode body) {}

    @Value("${local.server.port}")
    private int port;

    private final HttpClient client = HttpClient.newHttpClient();

    private Response send(String method, String path, String body, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(
                response.statusCode(), response.body().isBlank() ? JSON.nullNode() : JSON.readTree(response.body()));
    }

    private String enqueueOne(String body) throws Exception {
        Response response = send("POST", "/api/jobs", body);
        assertThat(response.status()).isEqualTo(201);
        return response.body().get(0).get("id").asString();
    }

    @Test
    void enqueueingStoresTheJobAsGiven() throws Exception {
        Response response = send(
                "POST",
                "/api/jobs",
                "{\"type\":\"sleep\",\"payload\":{\"millis\":50},\"priority\":7,\"delaySeconds\":3600,\"maxAttempts\":2}");

        assertThat(response.status()).isEqualTo(201);
        JsonNode job = response.body().get(0);
        assertThat(job.get("status").asString()).isEqualTo("QUEUED");
        assertThat(job.get("priority").asInt()).isEqualTo(7);
        assertThat(job.get("maxAttempts").asInt()).isEqualTo(2);
        assertThat(job.get("payload").get("millis").asInt()).isEqualTo(50);
        assertThat(java.time.Instant.parse(job.get("runAt").asString()))
                .isAfter(java.time.Instant.now().plusSeconds(3_500));
    }

    @Test
    void theSameIdempotencyKeySentManyTimesAtOnceMakesOneJob() throws Exception {
        String key = "order-" + UUID.randomUUID();
        int callers = 10;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Response>> responses = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(callers)) {
            for (int i = 0; i < callers; i++) {
                responses.add(pool.submit(() -> {
                    start.await();
                    return send("POST", "/api/jobs", "{\"type\":\"sleep\",\"delaySeconds\":3600}", "Idempotency-Key", key);
                }));
            }
            start.countDown();
        }

        List<Integer> statuses = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (Future<Response> future : responses) {
            statuses.add(future.get().status());
            ids.add(future.get().body().get(0).get("id").asString());
        }
        assertThat(statuses).filteredOn(status -> status == 201).hasSize(1);
        assertThat(statuses).filteredOn(status -> status == 200).hasSize(callers - 1);
        assertThat(ids).containsOnly(ids.getFirst());
        assertThat(jdbc.sql("select count(*) from jobs where idempotency_key = :key")
                        .param("key", key)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void aJobTypeNobodyCanRunIsRefusedAtTheDoor() throws Exception {
        Response response = send("POST", "/api/jobs", "{\"type\":\"teleport\"}");

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body().get("detail").asString()).contains("teleport").contains("sleep");
    }

    @Test
    void invalidRequestsSayWhichFieldIsWrong() throws Exception {
        Response response = send("POST", "/api/jobs", "{\"type\":\"sleep\",\"maxAttempts\":0,\"count\":5000}");

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body().get("fields").has("maxAttempts")).isTrue();
        assertThat(response.body().get("fields").has("count")).isTrue();
        assertThat(send("POST", "/api/jobs", "{not json").status()).isEqualTo(400);
        assertThat(send("POST", "/api/jobs", "{\"type\":\"sleep\",\"count\":3}", "Idempotency-Key", "k").status())
                .isEqualTo(400);
    }

    @Test
    void aQueuedJobCanBeCancelledAndThenRetried() throws Exception {
        String id = enqueueOne("{\"type\":\"sleep\",\"delaySeconds\":3600}");

        Response cancelled = send("POST", "/api/jobs/" + id + "/cancel", null);
        Response cancelledAgain = send("POST", "/api/jobs/" + id + "/cancel", null);
        Response retried = send("POST", "/api/jobs/" + id + "/retry", null);
        Response retriedAgain = send("POST", "/api/jobs/" + id + "/retry", null);

        assertThat(cancelled.status()).isEqualTo(200);
        assertThat(cancelled.body().get("status").asString()).isEqualTo("CANCELLED");
        assertThat(cancelledAgain.status()).isEqualTo(409);
        assertThat(cancelledAgain.body().get("detail").asString()).contains("CANCELLED");
        assertThat(retried.body().get("status").asString()).isEqualTo("QUEUED");
        assertThat(retriedAgain.status()).isEqualTo(409);
    }

    @Test
    void aRunningJobCannotBeCancelled() throws Exception {
        String id = enqueueOne("{\"type\":\"sleep\"}");
        jdbc.sql("update jobs set status = 'RUNNING', attempts = 1, locked_by = 'someone',"
                        + " lease_expires_at = now() + interval '1 hour' where id = :id")
                .param("id", UUID.fromString(id))
                .update();

        Response response = send("POST", "/api/jobs/" + id + "/cancel", null);

        assertThat(response.status()).isEqualTo(409);
        assertThat(send("GET", "/api/jobs/" + id, null).body().get("job").get("status").asString())
                .isEqualTo("RUNNING");
    }

    @Test
    void anUnknownJobIsNotFound() throws Exception {
        assertThat(send("GET", "/api/jobs/" + UUID.randomUUID(), null).status()).isEqualTo(404);
        assertThat(send("POST", "/api/jobs/" + UUID.randomUUID() + "/cancel", null).status()).isEqualTo(404);
    }

    @Test
    void schedulesAreValidatedAndNamedUniquely() throws Exception {
        String name = "nightly-" + UUID.randomUUID();
        String valid = "{\"name\":\"" + name + "\",\"cron\":\"0 0 2 * * *\",\"type\":\"sleep\"}";

        Response created = send("POST", "/api/schedules", valid);
        Response duplicate = send("POST", "/api/schedules", valid);
        Response badCron = send("POST", "/api/schedules", "{\"name\":\"x\",\"cron\":\"every tuesday\",\"type\":\"sleep\"}");
        Response badType = send("POST", "/api/schedules", "{\"name\":\"y\",\"cron\":\"0 0 2 * * *\",\"type\":\"teleport\"}");

        assertThat(created.status()).isEqualTo(201);
        assertThat(java.time.Instant.parse(created.body().get("nextFireAt").asString())).isAfter(java.time.Instant.now());
        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(badCron.status()).isEqualTo(400);
        assertThat(badCron.body().get("detail").asString()).contains("every tuesday");
        assertThat(badType.status()).isEqualTo(400);

        String id = created.body().get("id").asString();
        assertThat(send("PUT", "/api/schedules/" + id + "/enabled", "{\"enabled\":false}").body().get("enabled").asBoolean())
                .isFalse();
        assertThat(send("DELETE", "/api/schedules/" + id, null).status()).isEqualTo(204);
        assertThat(send("DELETE", "/api/schedules/" + id, null).status()).isEqualTo(404);
    }

    @Test
    void theOverviewReportsCountsLagAndWorkersWithWhatTheyAreRunning() throws Exception {
        String worker = send("POST", "/api/workers", null).body().get("id").asString();
        try {
            String id = enqueueOne("{\"type\":\"sleep\",\"payload\":{\"millis\":4000}}");
            org.awaitility.Awaitility.await().until(() -> send("GET", "/api/jobs/" + id, null)
                    .body()
                    .get("job")
                    .get("status")
                    .asString()
                    .equals("RUNNING"));

            JsonNode overview = send("GET", "/api/overview", null).body();

            assertThat(overview.get("counts").get("RUNNING").asLong()).isPositive();
            assertThat(overview.get("types").toString()).contains("sleep", "flaky", "always-fails");
            JsonNode mine = null;
            for (JsonNode candidate : overview.get("workers")) {
                if (candidate.get("id").asString().equals(worker)) {
                    mine = candidate;
                }
            }
            assertThat(mine).isNotNull();
            assertThat(mine.get("alive").asBoolean()).isTrue();
            assertThat(mine.get("local").asBoolean()).isTrue();
            assertThat(mine.get("running").toString()).contains(id);
        } finally {
            assertThat(send("DELETE", "/api/workers/" + worker, null).status()).isEqualTo(204);
        }
        assertThat(send("DELETE", "/api/workers/" + worker, null).status()).isEqualTo(404);
    }
}
