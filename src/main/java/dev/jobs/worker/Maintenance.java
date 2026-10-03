package dev.jobs.worker;

import dev.jobs.job.JobStore;
import dev.jobs.schedule.ScheduleRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The housekeeping every instance does: take jobs back from workers that went silent,
 * and turn due schedules into jobs. There is no leader. Every instance runs both, and
 * the statements are written so that doing them concurrently is harmless.
 */
@Component
class Maintenance {

    private static final Logger log = LoggerFactory.getLogger(Maintenance.class);

    private final JobStore store;
    private final ScheduleRunner schedules;

    Maintenance(JobStore store, ScheduleRunner schedules) {
        this.store = store;
        this.schedules = schedules;
    }

    @Scheduled(fixedDelayString = "${jobs.maintenance.interval}")
    void tick() {
        try {
            int reaped = store.reapExpiredLeases();
            if (reaped > 0) {
                log.warn("Took back {} job(s) whose worker stopped responding", reaped);
            }
            schedules.fireDue();
        } catch (RuntimeException e) {
            log.warn("Maintenance failed, will try again: {}", e.toString());
        }
    }
}
