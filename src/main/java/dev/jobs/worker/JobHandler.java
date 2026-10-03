package dev.jobs.worker;

/**
 * The code that does one type of job.
 *
 * <p>A job runs at least once, not exactly once: a worker can finish the work and die
 * before saying so, and the job will then run again elsewhere. Handlers should be safe
 * to repeat.
 */
public interface JobHandler {

    /** The job type this handler runs. */
    String type();

    /**
     * Does the work. Returning normally means success; any exception is a failed
     * attempt. If the thread is interrupted the worker has lost the job or is shutting
     * down, and the handler should stop.
     */
    void handle(JobContext context) throws Exception;
}
