package dev.jobs.job;

import java.time.Duration;

/**
 * @param delay how long to wait before the job may run
 * @param idempotencyKey if set, enqueueing again with the same key returns the first job
 */
public record NewJob(
        String queue, String type, String payload, int priority, Duration delay, int maxAttempts, String idempotencyKey) {

    public static NewJob of(String queue, String type, String payload) {
        return new NewJob(queue, type, payload, 0, Duration.ZERO, 5, null);
    }

    public NewJob withMaxAttempts(int attempts) {
        return new NewJob(queue, type, payload, priority, delay, attempts, idempotencyKey);
    }

    public NewJob withPriority(int value) {
        return new NewJob(queue, type, payload, value, delay, maxAttempts, idempotencyKey);
    }

    public NewJob withDelay(Duration value) {
        return new NewJob(queue, type, payload, priority, value, maxAttempts, idempotencyKey);
    }

    public NewJob withIdempotencyKey(String key) {
        return new NewJob(queue, type, payload, priority, delay, maxAttempts, key);
    }
}
