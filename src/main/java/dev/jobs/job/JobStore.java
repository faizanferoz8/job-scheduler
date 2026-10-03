package dev.jobs.job;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every statement that changes a job's state. The rules that keep the queue correct
 * live in these statements' WHERE clauses, not in Java, so they hold however many
 * instances run them at once.
 *
 * <p>All times come from the database's clock ({@code now()}). Instances whose own
 * clocks disagree therefore still agree about when a job is due and when a lease ends.
 */
@Repository
public class JobStore {

    private static final String COLUMNS = "id, queue, type, payload::text as payload, status, priority, run_at, attempts,"
            + " max_attempts, locked_by, lease_expires_at, last_error, schedule_id, created_at, finished_at";

    private final JdbcClient jdbc;

    public JobStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Enqueued(Job job, boolean created) {}

    /**
     * Adds a job. With an idempotency key, a second call returns the job the first one
     * made: the unique index decides the race between simultaneous calls, so exactly one
     * of them inserts.
     */
    @Transactional
    public Enqueued enqueue(NewJob job) {
        Optional<Job> inserted = jdbc.sql("insert into jobs (id, queue, type, payload, status, priority, run_at,"
                        + " max_attempts, idempotency_key)"
                        + " values (:id, :queue, :type, :payload::jsonb, 'QUEUED', :priority,"
                        + " now() + make_interval(secs => :delay), :maxAttempts, :key)"
                        + " on conflict (idempotency_key) do nothing returning " + COLUMNS)
                .param("id", UUID.randomUUID())
                .param("queue", job.queue())
                .param("type", job.type())
                .param("payload", job.payload())
                .param("priority", job.priority())
                .param("delay", job.delay().toMillis() / 1000.0)
                .param("maxAttempts", job.maxAttempts())
                .param("key", job.idempotencyKey())
                .query(JobStore::toJob)
                .optional();
        if (inserted.isPresent()) {
            return new Enqueued(inserted.get(), true);
        }
        Job existing = jdbc.sql("select " + COLUMNS + " from jobs where idempotency_key = :key")
                .param("key", job.idempotencyKey())
                .query(JobStore::toJob)
                .single();
        return new Enqueued(existing, false);
    }

    /**
     * Takes up to {@code limit} due jobs for a worker: highest priority first, then
     * oldest.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} is what lets many workers do this at once. Each
     * locks the rows it selects; a worker that meets a locked row skips it and takes the
     * next, instead of waiting for it or taking it too. No two claims can return the same
     * job, and none of them blocks another.
     */
    @Transactional
    public List<Job> claim(String queue, String worker, int limit, Duration lease) {
        List<Job> claimed = jdbc.sql("update jobs set status = 'RUNNING', attempts = attempts + 1, locked_by = :worker,"
                        + " lease_expires_at = now() + make_interval(secs => :lease)"
                        + " where id in (select id from jobs where queue = :queue and status = 'QUEUED' and run_at <= now()"
                        + "   order by priority desc, run_at, id for update skip locked limit :limit)"
                        + " returning " + COLUMNS)
                .param("worker", worker)
                .param("lease", lease.toMillis() / 1000.0)
                .param("queue", queue)
                .param("limit", limit)
                .query(JobStore::toJob)
                .list();
        for (Job job : claimed) {
            // An attempt handed back at shutdown keeps its number, so the row may exist.
            jdbc.sql("insert into job_attempts (job_id, attempt, worker) values (:id, :attempt, :worker)"
                            + " on conflict (job_id, attempt) do update set worker = excluded.worker,"
                            + " started_at = now(), finished_at = null, outcome = null, error = null")
                    .param("id", job.id())
                    .param("attempt", job.attempts())
                    .param("worker", worker)
                    .update();
        }
        return claimed;
    }

    /**
     * Records success, if the caller still owns the attempt it is reporting on. A worker
     * that stalled past its lease may come back to find the job given to someone else,
     * or already finished; its report then matches no row and is ignored.
     *
     * @return false if the lease was lost
     */
    @Transactional
    public boolean succeed(Job job, String worker) {
        int updated = jdbc.sql("update jobs set status = 'SUCCEEDED', locked_by = null, lease_expires_at = null,"
                        + " last_error = null, finished_at = now() where " + OWNED)
                .param("id", job.id())
                .param("worker", worker)
                .param("attempt", job.attempts())
                .update();
        if (updated == 1) {
            finishAttempt(job, "SUCCEEDED", null);
        }
        return updated == 1;
    }

    /**
     * Records a failure. The job goes back in the queue to run after {@code retryIn}, or
     * to DEAD if that was its last attempt.
     *
     * @return false if the lease was lost
     */
    @Transactional
    public boolean fail(Job job, String worker, String error, Duration retryIn) {
        int updated = jdbc.sql("update jobs set"
                        + " status = case when attempts >= max_attempts then 'DEAD' else 'QUEUED' end,"
                        + " finished_at = case when attempts >= max_attempts then now() end,"
                        + " run_at = now() + make_interval(secs => :retry),"
                        + " locked_by = null, lease_expires_at = null, last_error = :error where " + OWNED)
                .param("retry", retryIn.toMillis() / 1000.0)
                .param("error", error)
                .param("id", job.id())
                .param("worker", worker)
                .param("attempt", job.attempts())
                .update();
        if (updated == 1) {
            finishAttempt(job, "FAILED", error);
        }
        return updated == 1;
    }

    /**
     * Hands a running job back untouched, for a worker that is shutting down. The
     * attempt is not counted: a deploy should not use up a job's retries.
     */
    @Transactional
    public boolean release(Job job, String worker) {
        int updated = jdbc.sql("update jobs set status = 'QUEUED', attempts = attempts - 1, run_at = now(),"
                        + " locked_by = null, lease_expires_at = null where " + OWNED)
                .param("id", job.id())
                .param("worker", worker)
                .param("attempt", job.attempts())
                .update();
        if (updated == 1) {
            finishAttempt(job, "RELEASED", null);
        }
        return updated == 1;
    }

    private static final String OWNED =
            "id = :id and status = 'RUNNING' and locked_by = :worker and attempts = :attempt";

    private void finishAttempt(Job job, String outcome, String error) {
        jdbc.sql("update job_attempts set finished_at = now(), outcome = :outcome, error = :error"
                        + " where job_id = :id and attempt = :attempt")
                .param("outcome", outcome)
                .param("error", error)
                .param("id", job.id())
                .param("attempt", job.attempts())
                .update();
    }

    /**
     * Extends the leases a worker holds and says which of them it still holds. Anything
     * missing from the answer was taken away, and the worker should stop working on it.
     */
    public Set<UUID> renewLeases(String worker, Collection<UUID> jobIds, Duration lease) {
        if (jobIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql("update jobs set lease_expires_at = now() + make_interval(secs => :lease)"
                        + " where locked_by = :worker and status = 'RUNNING' and id in (:ids) returning id")
                .param("lease", lease.toMillis() / 1000.0)
                .param("worker", worker)
                .param("ids", jobIds)
                .query(UUID.class)
                .list());
    }

    /**
     * Takes jobs away from workers that stopped renewing their leases: crashed, hung, or
     * cut off from the database. Each goes back in the queue, or to DEAD if the lost
     * attempt was its last. Safe to run from every instance at once.
     */
    @Transactional
    public int reapExpiredLeases() {
        return jdbc.sql("with expired as ("
                        + "  select id, attempts, locked_by from jobs"
                        + "  where status = 'RUNNING' and lease_expires_at < now() for update skip locked),"
                        + " reaped as ("
                        + "  update jobs j set"
                        + "   status = case when j.attempts >= j.max_attempts then 'DEAD' else 'QUEUED' end,"
                        + "   finished_at = case when j.attempts >= j.max_attempts then now() end,"
                        + "   run_at = now(), locked_by = null, lease_expires_at = null,"
                        + "   last_error = 'Worker ' || e.locked_by || ' stopped responding'"
                        + "  from expired e where j.id = e.id returning j.id, e.attempts)"
                        + " update job_attempts a set finished_at = now(), outcome = 'LEASE_EXPIRED',"
                        + "  error = 'The worker stopped renewing its lease'"
                        + " from reaped r where a.job_id = r.id and a.attempt = r.attempts")
                .update();
    }

    /** Only a job that has not started can be cancelled; a running one is its worker's to finish. */
    public boolean cancel(UUID id) {
        return jdbc.sql("update jobs set status = 'CANCELLED', finished_at = now() where id = :id and status = 'QUEUED'")
                        .param("id", id)
                        .update()
                == 1;
    }

    /** Puts a dead or cancelled job back in the queue with a fresh set of attempts. */
    public boolean retry(UUID id) {
        return jdbc.sql("update jobs set status = 'QUEUED', attempts = 0, run_at = now(), finished_at = null"
                                + " where id = :id and status in ('DEAD', 'CANCELLED')")
                        .param("id", id)
                        .update()
                == 1;
    }

    public Optional<Job> find(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from jobs where id = :id")
                .param("id", id)
                .query(JobStore::toJob)
                .optional();
    }

    public List<Job> list(JobStatus status, int limit) {
        return jdbc.sql("select " + COLUMNS + " from jobs where (:status::text is null or status = :status::text)"
                        + " order by created_at desc, id limit :limit")
                .param("status", status == null ? null : status.name())
                .param("limit", limit)
                .query(JobStore::toJob)
                .list();
    }

    public List<Job> running() {
        return jdbc.sql("select " + COLUMNS + " from jobs where status = 'RUNNING' order by locked_by, created_at")
                .query(JobStore::toJob)
                .list();
    }

    public List<JobAttempt> attempts(UUID jobId) {
        return jdbc.sql("select attempt, worker, started_at, finished_at, outcome, error from job_attempts"
                        + " where job_id = :id order by attempt")
                .param("id", jobId)
                .query((row, n) -> new JobAttempt(
                        row.getInt("attempt"),
                        row.getString("worker"),
                        instant(row, "started_at"),
                        instant(row, "finished_at"),
                        row.getString("outcome"),
                        row.getString("error")))
                .list();
    }

    public Map<String, Long> countsByStatus() {
        Map<String, Long> counts = new java.util.LinkedHashMap<>();
        for (JobStatus status : JobStatus.values()) {
            counts.put(status.name(), 0L);
        }
        jdbc.sql("select status, count(*) as n from jobs group by status")
                .query((row, n) -> counts.put(row.getString("status"), row.getLong("n")))
                .list();
        return counts;
    }

    /** How long the oldest due job has been waiting, in milliseconds: the queue's lag. */
    public long oldestDueWaitMillis() {
        return jdbc.sql("select coalesce(extract(epoch from now() - min(run_at)) * 1000, 0)::bigint"
                        + " from jobs where status = 'QUEUED' and run_at <= now()")
                .query(Long.class)
                .single();
    }

    public long finishedInLastMinute() {
        return jdbc.sql("select count(*) from jobs where status = 'SUCCEEDED' and finished_at > now() - interval '1 minute'")
                .query(Long.class)
                .single();
    }

    static Job toJob(ResultSet row, int n) throws SQLException {
        return new Job(
                row.getObject("id", UUID.class),
                row.getString("queue"),
                row.getString("type"),
                row.getString("payload"),
                JobStatus.valueOf(row.getString("status")),
                row.getInt("priority"),
                instant(row, "run_at"),
                row.getInt("attempts"),
                row.getInt("max_attempts"),
                row.getString("locked_by"),
                instant(row, "lease_expires_at"),
                row.getString("last_error"),
                row.getObject("schedule_id", UUID.class),
                instant(row, "created_at"),
                instant(row, "finished_at"));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
