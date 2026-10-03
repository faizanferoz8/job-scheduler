package dev.jobs.worker;

import dev.jobs.job.Job;
import dev.jobs.job.JobStore;
import dev.jobs.job.WorkerRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * One worker: claims jobs from a queue, runs each on its own virtual thread, and keeps
 * its leases alive while they run.
 *
 * <p>Three loops, and the reason for each:
 *
 * <ul>
 *   <li><b>Poll.</b> Claims as many due jobs as there are free slots. It never claims
 *       more than it can start at once, so a job is not held idle by one worker while
 *       another has room.
 *   <li><b>Heartbeat.</b> Renews the lease on everything in progress. If a renewal comes
 *       back without one of the jobs, the lease was lost (this worker stalled long enough
 *       for the job to be given away), and the handler is interrupted so two workers are
 *       not both doing the work.
 *   <li><b>The jobs themselves</b>, which report their outcome with the attempt number
 *       they were given. The database accepts the report only from the current owner of
 *       that attempt.
 * </ul>
 */
public class WorkerPool {

    private static final Logger log = LoggerFactory.getLogger(WorkerPool.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private record InFlight(Job job, Thread thread) {}

    private final JobStore store;
    private final WorkerRegistry registry;
    private final Map<String, JobHandler> handlers;
    private final WorkerSettings settings;
    private final Map<UUID, InFlight> inFlight = new ConcurrentHashMap<>();
    private final ExecutorService runners = Executors.newVirtualThreadPerTaskExecutor();

    private volatile boolean claiming;
    /** Set when heartbeats must stop: after a shutdown, or to simulate a crash. */
    private volatile boolean halted;
    private Thread pollThread;
    private Thread heartbeatThread;

    public WorkerPool(JobStore store, WorkerRegistry registry, List<JobHandler> handlers, WorkerSettings settings) {
        this.store = store;
        this.registry = registry;
        this.handlers = new ConcurrentHashMap<>();
        handlers.forEach(handler -> this.handlers.put(handler.type(), handler));
        this.settings = settings;
    }

    public String id() {
        return settings.workerId();
    }

    public int running() {
        return inFlight.size();
    }

    public synchronized void start() {
        claiming = true;
        registry.seen(settings.workerId(), settings.queue(), settings.concurrency());
        pollThread = Thread.ofPlatform().name(settings.workerId() + "-poll").daemon(true).start(this::pollLoop);
        heartbeatThread =
                Thread.ofPlatform().name(settings.workerId() + "-heartbeat").daemon(true).start(this::heartbeatLoop);
        log.info("Worker {} started on queue '{}' with {} slots", settings.workerId(), settings.queue(), settings.concurrency());
    }

    private void pollLoop() {
        while (claiming) {
            try {
                int free = settings.concurrency() - inFlight.size();
                if (free > 0) {
                    for (Job job : store.claim(settings.queue(), settings.workerId(), free, settings.lease())) {
                        // Registered before it starts, so the heartbeat covers it from the first moment.
                        inFlight.put(job.id(), new InFlight(job, null));
                        runners.execute(() -> run(job));
                    }
                }
            } catch (RuntimeException e) {
                // Usually the database being briefly unreachable. Keep polling.
                log.warn("Worker {} could not claim jobs: {}", settings.workerId(), e.toString());
            }
            if (!sleep(settings.pollInterval())) {
                return;
            }
        }
    }

    private void run(Job job) {
        inFlight.put(job.id(), new InFlight(job, Thread.currentThread()));
        try {
            Throwable failure = attempt(job);
            // An interrupt means the job is no longer this worker's: its lease was lost, or it
            // was handed back at shutdown. There is nothing to report, and a report would be
            // refused anyway. The flag is checked as well as the exception because a handler
            // interrupted inside a blocking call often surfaces it as something else.
            if (failure instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                log.info("Worker {} abandoned job {}", settings.workerId(), job.id());
                return;
            }
            record(job, failure);
        } finally {
            inFlight.remove(job.id());
            // Do not carry an interrupt meant for this job into the executor's next task.
            Thread.interrupted();
        }
    }

    /** Runs the handler. Returns what went wrong, or null if nothing did. */
    private Throwable attempt(Job job) {
        try {
            JobHandler handler = handlers.get(job.type());
            if (handler == null) {
                throw new IllegalStateException("No handler is registered for job type '" + job.type() + "'");
            }
            handler.handle(new JobContext(job, JSON.readTree(job.payload()), job.attempts()));
            return null;
        } catch (Exception | Error e) {
            return e;
        }
    }

    private void record(Job job, Throwable failure) {
        try {
            boolean accepted = failure == null
                    ? store.succeed(job, settings.workerId())
                    : store.fail(job, settings.workerId(), describe(failure), settings.backoff().after(job.attempts()));
            if (!accepted) {
                log.warn(
                        "Worker {} finished attempt {} of job {} after losing its lease; the result was discarded",
                        settings.workerId(),
                        job.attempts(),
                        job.id());
            }
        } catch (RuntimeException e) {
            // The outcome could not be written, most likely because the database is
            // unreachable. Nothing more to do here: the lease will run out and the job
            // will be run again, which is the at-least-once guarantee doing its job.
            log.warn("Worker {} could not record the outcome of job {}: {}", settings.workerId(), job.id(), e.toString());
        }
    }

    private void heartbeatLoop() {
        while (!halted) {
            try {
                registry.seen(settings.workerId(), settings.queue(), settings.concurrency());
                Set<UUID> held = Set.copyOf(inFlight.keySet());
                Set<UUID> stillOurs = store.renewLeases(settings.workerId(), held, settings.lease());
                for (UUID id : held) {
                    InFlight lost = stillOurs.contains(id) ? null : inFlight.get(id);
                    if (lost != null && lost.thread() != null) {
                        log.warn("Worker {} lost its lease on job {}; stopping work on it", settings.workerId(), id);
                        lost.thread().interrupt();
                    }
                }
            } catch (RuntimeException e) {
                log.warn("Worker {} could not renew its leases: {}", settings.workerId(), e.toString());
            }
            if (!sleep(settings.heartbeat())) {
                return;
            }
        }
    }

    /**
     * Stops claiming, gives running jobs the grace period to finish, then hands back
     * whatever is still running so another worker can take it at once instead of
     * waiting out the lease.
     */
    public synchronized void stop() {
        if (!claiming && pollThread == null) {
            return;
        }
        claiming = false;
        interruptAndJoin(pollThread);
        long deadline = System.nanoTime() + settings.shutdownGrace().toNanos();
        while (!inFlight.isEmpty() && System.nanoTime() < deadline) {
            if (!sleep(Duration.ofMillis(25))) {
                break;
            }
        }
        for (InFlight unfinished : List.copyOf(inFlight.values())) {
            if (store.release(unfinished.job(), settings.workerId())) {
                log.info("Worker {} handed back job {} on shutdown", settings.workerId(), unfinished.job().id());
            }
            if (unfinished.thread() != null) {
                unfinished.thread().interrupt();
            }
        }
        halted = true;
        interruptAndJoin(heartbeatThread);
        runners.shutdown();
        try {
            runners.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        registry.gone(settings.workerId());
        pollThread = null;
        log.info("Worker {} stopped", settings.workerId());
    }

    /**
     * Goes silent, the way a machine cut off from the network does: no more claims, no
     * more heartbeats, nothing handed back. Running handlers are left running and will
     * still try to report, which is what makes this the harder case than {@link #kill()}.
     */
    public void simulateCrash() {
        halted = true;
        claiming = false;
        if (pollThread != null) {
            pollThread.interrupt();
        }
        if (heartbeatThread != null) {
            heartbeatThread.interrupt();
        }
        log.warn("Worker {} crashed (simulated) with {} jobs in progress", settings.workerId(), inFlight.size());
    }

    /**
     * Stops the way a killed process does: everything in progress dies with it, unfinished
     * and unreported. Its jobs stay marked as running until their leases run out.
     */
    public void kill() {
        simulateCrash();
        for (InFlight dying : List.copyOf(inFlight.values())) {
            if (dying.thread() != null) {
                dying.thread().interrupt();
            }
        }
    }

    private static void interruptAndJoin(Thread thread) {
        if (thread == null) {
            return;
        }
        thread.interrupt();
        try {
            thread.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean sleep(Duration duration) {
        try {
            Thread.sleep(duration);
            return true;
        } catch (InterruptedException e) {
            return false;
        }
    }

    private static String describe(Throwable e) {
        String message = e.getMessage();
        String text = e.getClass().getSimpleName() + (message == null ? "" : ": " + message);
        return text.length() > 2_000 ? text.substring(0, 2_000) : text;
    }
}
