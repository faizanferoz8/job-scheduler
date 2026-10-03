package dev.jobs.job;

public enum JobStatus {
    /** Waiting for its time and a free worker. */
    QUEUED,
    /** Claimed by a worker that holds a lease on it. */
    RUNNING,
    SUCCEEDED,
    /** Failed on every attempt it was allowed. Stays put until someone retries it by hand. */
    DEAD,
    CANCELLED
}
