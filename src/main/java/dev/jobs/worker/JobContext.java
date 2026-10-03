package dev.jobs.worker;

import dev.jobs.job.Job;
import tools.jackson.databind.JsonNode;

/**
 * What a handler is given.
 *
 * @param attempt 1 for the first try
 */
public record JobContext(Job job, JsonNode payload, int attempt) {}
