package dev.jobs.job;

import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Which workers exist, for the dashboard. Correctness never depends on this table; leases do that. */
@Repository
public class WorkerRegistry {

    public record WorkerInfo(String id, String queue, int concurrency, Instant startedAt, Instant lastSeenAt, boolean alive) {}

    private final JdbcClient jdbc;

    public WorkerRegistry(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void seen(String id, String queue, int concurrency) {
        jdbc.sql("insert into workers (id, queue, concurrency) values (:id, :queue, :concurrency)"
                        + " on conflict (id) do update set last_seen_at = now()")
                .param("id", id)
                .param("queue", queue)
                .param("concurrency", concurrency)
                .update();
    }

    public void gone(String id) {
        jdbc.sql("delete from workers where id = :id").param("id", id).update();
    }

    /** Workers heard from in the last two minutes; one silent for longer than {@code silence} is shown as not alive. */
    public List<WorkerInfo> list(java.time.Duration silence) {
        return jdbc.sql("select id, queue, concurrency, started_at, last_seen_at,"
                        + " last_seen_at > now() - make_interval(secs => :silence) as alive"
                        + " from workers where last_seen_at > now() - interval '2 minutes' order by started_at, id")
                .param("silence", silence.toMillis() / 1000.0)
                .query((row, n) -> new WorkerInfo(
                        row.getString("id"),
                        row.getString("queue"),
                        row.getInt("concurrency"),
                        row.getTimestamp("started_at").toInstant(),
                        row.getTimestamp("last_seen_at").toInstant(),
                        row.getBoolean("alive")))
                .list();
    }
}
