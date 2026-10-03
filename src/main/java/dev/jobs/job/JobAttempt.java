package dev.jobs.job;

import java.time.Instant;

public record JobAttempt(int attempt, String worker, Instant startedAt, Instant finishedAt, String outcome, String error) {}
