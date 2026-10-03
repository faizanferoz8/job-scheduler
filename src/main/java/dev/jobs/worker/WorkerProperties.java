package dev.jobs.worker;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("jobs")
public record WorkerProperties(Worker worker, Retry retry, Maintenance maintenance) {

    public record Worker(
            boolean enabled,
            String queue,
            int concurrency,
            Duration pollInterval,
            Duration lease,
            Duration heartbeat,
            Duration shutdownGrace) {}

    public record Retry(Duration baseDelay, Duration maxDelay) {}

    public record Maintenance(Duration interval) {}

    public WorkerSettings settingsFor(String workerId) {
        return new WorkerSettings(
                workerId,
                worker.queue(),
                worker.concurrency(),
                worker.pollInterval(),
                worker.lease(),
                worker.heartbeat(),
                worker.shutdownGrace(),
                new Backoff(retry.baseDelay(), retry.maxDelay()));
    }
}
