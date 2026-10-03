package dev.jobs.schedule;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns cron schedules into jobs.
 *
 * <p>Every instance calls {@link #fireDue()} about once a second, so several will often
 * notice the same tick. Two things make that safe. A due schedule is read with
 * {@code FOR UPDATE SKIP LOCKED}, so normally only one instance handles it. And the job
 * it creates is unique on (schedule, tick time), so even if that were to go wrong the
 * second insert would do nothing. A tick produces one job.
 *
 * <p>If nothing was running when ticks came due, they are not replayed. The schedule
 * fires once for the most recent missed tick and resumes from now: a nightly report
 * that was down for a week should run once, not seven times.
 */
@Component
public class ScheduleRunner {

    private static final String COLUMNS =
            "id, name, cron, queue, type, payload::text as payload, enabled, next_fire_at, last_fired_at";

    private final JdbcClient jdbc;

    ScheduleRunner(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public int fireDue() {
        Instant now = databaseTime();
        List<Schedule> due = jdbc.sql("select " + COLUMNS + " from schedules where enabled and next_fire_at <= :now"
                        + " order by next_fire_at for update skip locked")
                .param("now", Timestamp.from(now))
                .query(ScheduleRunner::toSchedule)
                .list();
        int fired = 0;
        for (Schedule schedule : due) {
            Instant tick = latestTickAtOrBefore(schedule, now);
            fired += jdbc.sql("insert into jobs (id, queue, type, payload, status, max_attempts, schedule_id, scheduled_for)"
                            + " values (:id, :queue, :type, :payload::jsonb, 'QUEUED', 5, :schedule, :tick)"
                            + " on conflict (schedule_id, scheduled_for) do nothing")
                    .param("id", UUID.randomUUID())
                    .param("queue", schedule.queue())
                    .param("type", schedule.type())
                    .param("payload", schedule.payload())
                    .param("schedule", schedule.id())
                    .param("tick", Timestamp.from(tick))
                    .update();
            jdbc.sql("update schedules set next_fire_at = :next, last_fired_at = :tick where id = :id")
                    .param("next", Timestamp.from(nextAfter(schedule.cron(), now)))
                    .param("tick", Timestamp.from(tick))
                    .param("id", schedule.id())
                    .update();
        }
        return fired;
    }

    private static Instant latestTickAtOrBefore(Schedule schedule, Instant now) {
        Instant tick = schedule.nextFireAt();
        // Walks forward over ticks that were missed; bounded so a per-second schedule that
        // was off for a year cannot spin here.
        for (int i = 0; i < 10_000; i++) {
            Instant following = nextAfter(schedule.cron(), tick);
            if (following.isAfter(now)) {
                break;
            }
            tick = following;
        }
        return tick;
    }

    public Schedule create(String name, String cron, String queue, String type, String payload) {
        Instant first = nextAfter(cron, databaseTime());
        return jdbc.sql("insert into schedules (id, name, cron, queue, type, payload, next_fire_at)"
                        + " values (:id, :name, :cron, :queue, :type, :payload::jsonb, :next) returning " + COLUMNS)
                .param("id", UUID.randomUUID())
                .param("name", name)
                .param("cron", cron)
                .param("queue", queue)
                .param("type", type)
                .param("payload", payload)
                .param("next", Timestamp.from(first))
                .query(ScheduleRunner::toSchedule)
                .single();
    }

    /** Re-enabling starts from now; ticks that passed while it was off are not owed. */
    @Transactional
    public Optional<Schedule> setEnabled(UUID id, boolean enabled) {
        Optional<Schedule> found = jdbc.sql("select " + COLUMNS + " from schedules where id = :id for update")
                .param("id", id)
                .query(ScheduleRunner::toSchedule)
                .optional();
        if (found.isEmpty()) {
            return found;
        }
        return jdbc.sql("update schedules set enabled = :enabled, next_fire_at = :next where id = :id returning " + COLUMNS)
                .param("enabled", enabled)
                .param("next", Timestamp.from(nextAfter(found.get().cron(), databaseTime())))
                .param("id", id)
                .query(ScheduleRunner::toSchedule)
                .optional();
    }

    public boolean delete(UUID id) {
        return jdbc.sql("delete from schedules where id = :id").param("id", id).update() == 1;
    }

    public List<Schedule> list() {
        return jdbc.sql("select " + COLUMNS + " from schedules order by name")
                .query(ScheduleRunner::toSchedule)
                .list();
    }

    /** Spring's six-field cron: second, minute, hour, day of month, month, day of week. In UTC. */
    public static Instant nextAfter(String cron, Instant after) {
        ZonedDateTime next = CronExpression.parse(cron).next(after.atZone(ZoneOffset.UTC));
        if (next == null) {
            throw new IllegalArgumentException("The cron expression '" + cron + "' never fires again");
        }
        return next.toInstant();
    }

    private Instant databaseTime() {
        return jdbc.sql("select now()").query(Timestamp.class).single().toInstant();
    }

    private static Schedule toSchedule(java.sql.ResultSet row, int n) throws java.sql.SQLException {
        Timestamp last = row.getTimestamp("last_fired_at");
        return new Schedule(
                row.getObject("id", UUID.class),
                row.getString("name"),
                row.getString("cron"),
                row.getString("queue"),
                row.getString("type"),
                row.getString("payload"),
                row.getBoolean("enabled"),
                row.getTimestamp("next_fire_at").toInstant(),
                last == null ? null : last.toInstant());
    }
}
