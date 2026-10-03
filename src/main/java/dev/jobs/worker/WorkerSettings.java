package dev.jobs.worker;

import java.time.Duration;

public record WorkerSettings(
        String workerId,
        String queue,
        int concurrency,
        Duration pollInterval,
        Duration lease,
        Duration heartbeat,
        Duration shutdownGrace,
        Backoff backoff) {

    public WorkerSettings {
        if (concurrency < 1) {
            throw new IllegalArgumentException("A worker needs at least one slot");
        }
        if (heartbeat.multipliedBy(2).compareTo(lease) > 0) {
            // With less margin, one slow heartbeat would be enough to lose every job.
            throw new IllegalArgumentException("The lease must be at least twice the heartbeat interval");
        }
    }
}
