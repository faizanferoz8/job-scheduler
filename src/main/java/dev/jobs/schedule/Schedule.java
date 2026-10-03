package dev.jobs.schedule;

import java.time.Instant;
import java.util.UUID;

public record Schedule(
        UUID id,
        String name,
        String cron,
        String queue,
        String type,
        String payload,
        boolean enabled,
        Instant nextFireAt,
        Instant lastFiredAt) {}
