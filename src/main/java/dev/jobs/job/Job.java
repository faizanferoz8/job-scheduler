package dev.jobs.job;

import java.time.Instant;
import java.util.UUID;

/**
 * A job as stored. {@code attempts} counts attempts started, so on a running job it is
 * the number of the attempt in progress.
 */
public record Job(
        UUID id,
        String queue,
        String type,
        String payload,
        JobStatus status,
        int priority,
        Instant runAt,
        int attempts,
        int maxAttempts,
        String lockedBy,
        Instant leaseExpiresAt,
        String lastError,
        UUID scheduleId,
        Instant createdAt,
        Instant finishedAt) {}
