package dev.jobs.support;

import dev.jobs.job.Job;
import dev.jobs.job.JobStore;
import dev.jobs.job.WorkerRegistry;
import dev.jobs.worker.Backoff;
import dev.jobs.worker.JobContext;
import dev.jobs.worker.JobHandler;
import dev.jobs.worker.WorkerPool;
import dev.jobs.worker.WorkerSettings;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Runs the application against a real Postgres. SKIP LOCKED, row locks and the
 * database's clock are what these tests are about, and an in-memory substitute has
 * none of them.
 *
 * <p>Postgres comes from Testcontainers. Where Docker is not available, set
 * JOBS_TEST_DB_URL, JOBS_TEST_DB_USER and JOBS_TEST_DB_PASSWORD to use an existing
 * server.
 *
 * <p>The application's own worker is switched off. Each test starts the workers it
 * needs on a queue of its own, so tests cannot take each other's jobs. The maintenance
 * loop (lease reaper and schedules) runs as it does in production, only faster.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTest {

    private static final String URL;
    private static final String USER;
    private static final String PASSWORD;

    static {
        String external = System.getenv("JOBS_TEST_DB_URL");
        if (external != null) {
            URL = external;
            USER = System.getenv("JOBS_TEST_DB_USER");
            PASSWORD = System.getenv("JOBS_TEST_DB_PASSWORD");
        } else {
            PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");
            postgres.start();
            URL = postgres.getJdbcUrl();
            USER = postgres.getUsername();
            PASSWORD = postgres.getPassword();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> URL);
        registry.add("spring.datasource.username", () -> USER);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 30);
        registry.add("jobs.worker.enabled", () -> false);
        registry.add("jobs.maintenance.interval", () -> "100ms");
    }

    @Autowired
    protected JobStore store;

    @Autowired
    protected WorkerRegistry registry;

    @Autowired
    protected JdbcClient jdbc;

    private final List<WorkerPool> started = new ArrayList<>();

    /** A queue no other test uses. */
    protected final String queue = "test-" + UUID.randomUUID();

    /** Short timings, so a lost lease is noticed in about a second instead of thirty. */
    protected WorkerSettings settings(String name, int concurrency) {
        return new WorkerSettings(
                name + "-" + UUID.randomUUID().toString().substring(0, 6),
                queue,
                concurrency,
                Duration.ofMillis(25),
                Duration.ofMillis(1_000),
                Duration.ofMillis(250),
                Duration.ofMillis(200),
                new Backoff(Duration.ofMillis(200), Duration.ofSeconds(2)));
    }

    protected WorkerPool startWorker(String name, int concurrency, JobHandler... handlers) {
        WorkerPool pool = new WorkerPool(store, registry, List.of(handlers), settings(name, concurrency));
        started.add(pool);
        pool.start();
        return pool;
    }

    @AfterEach
    void stopWorkers() {
        started.forEach(WorkerPool::stop);
    }

    protected static JobHandler handler(String type, Work work) {
        return new JobHandler() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public void handle(JobContext context) throws Exception {
                work.run(context);
            }
        };
    }

    public interface Work {
        void run(JobContext context) throws Exception;
    }

    protected Job reload(Job job) {
        return store.find(job.id()).orElseThrow();
    }

    protected long count(String status) {
        return jdbc.sql("select count(*) from jobs where queue = :queue and status = :status")
                .param("queue", queue)
                .param("status", status)
                .query(Long.class)
                .single();
    }
}
