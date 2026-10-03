package dev.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jobs.schedule.Schedule;
import dev.jobs.schedule.ScheduleRunner;
import dev.jobs.support.IntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ScheduleIT extends IntegrationTest {

    private static final String EVERY_SECOND = "* * * * * *";

    @Autowired
    private ScheduleRunner schedules;

    private Schedule create(String cron) {
        return schedules.create("schedule-" + UUID.randomUUID(), cron, queue, "noop", "{\"from\":\"schedule\"}");
    }

    private List<Instant> ticksFired(Schedule schedule) {
        return jdbc.sql("select scheduled_for from jobs where schedule_id = :id order by scheduled_for")
                .param("id", schedule.id())
                .query((row, n) -> row.getTimestamp(1).toInstant())
                .list();
    }

    @Test
    void aTickProducesOneJobHoweverManyInstancesNoticeIt() throws Exception {
        Schedule schedule = create(EVERY_SECOND);
        AtomicBoolean running = new AtomicBoolean(true);

        // Eight "instances" hammering the scheduler, on top of the application's own loop.
        try (ExecutorService instances = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 8; i++) {
                instances.submit(() -> {
                    while (running.get()) {
                        schedules.fireDue();
                    }
                });
            }
            Thread.sleep(4_200);
            running.set(false);
        }

        List<Instant> ticks = ticksFired(schedule);
        assertThat(ticks.size()).isBetween(3, 5);
        // Consecutive seconds, none missing, none twice.
        for (int i = 1; i < ticks.size(); i++) {
            assertThat(ticks.get(i)).isEqualTo(ticks.get(i - 1).plusSeconds(1));
        }
        assertThat(count("QUEUED")).isEqualTo(ticks.size());
    }

    @Test
    void ticksMissedWhileNothingWasRunningAreNotReplayed() {
        Schedule schedule = create("0 * * * * *");
        // As if every instance had been down for the last three hours.
        jdbc.sql("update schedules set next_fire_at = now() - interval '3 hours' where id = :id")
                .param("id", schedule.id())
                .update();

        schedules.fireDue();
        schedules.fireDue();

        List<Instant> ticks = ticksFired(schedule);
        assertThat(ticks).hasSize(1);
        // The one job is for the most recent minute that was missed, not the oldest.
        assertThat(ticks.getFirst()).isAfter(Instant.now().minusSeconds(120));
        Schedule after = schedules.list().stream().filter(s -> s.id().equals(schedule.id())).findFirst().orElseThrow();
        assertThat(after.nextFireAt()).isAfter(Instant.now().minusSeconds(5));
        assertThat(after.lastFiredAt()).isEqualTo(ticks.getFirst());
    }

    @Test
    void aDisabledScheduleDoesNotFireAndResumesFromNowWhenEnabled() throws Exception {
        Schedule schedule = create(EVERY_SECOND);
        schedules.setEnabled(schedule.id(), false);
        int before = ticksFired(schedule).size();

        Thread.sleep(2_200);
        assertThat(ticksFired(schedule)).hasSize(before);

        schedules.setEnabled(schedule.id(), true);
        Thread.sleep(2_200);

        List<Instant> ticks = ticksFired(schedule);
        // Two or three new ticks, and none for the seconds it spent disabled.
        assertThat(ticks.size() - before).isBetween(1, 3);
    }

    @Test
    void deletingAScheduleKeepsTheJobsItAlreadyMade() throws Exception {
        Schedule schedule = create(EVERY_SECOND);
        Thread.sleep(1_500);
        // Paused first, so no tick can land between counting and deleting.
        schedules.setEnabled(schedule.id(), false);
        long made = count("QUEUED");
        assertThat(made).isPositive();

        assertThat(schedules.delete(schedule.id())).isTrue();

        assertThat(count("QUEUED")).isEqualTo(made);
        Thread.sleep(1_500);
        assertThat(count("QUEUED")).isEqualTo(made);
    }
}
