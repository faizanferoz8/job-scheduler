package dev.jobs.api;

import dev.jobs.job.Job;
import dev.jobs.job.JobStore;
import dev.jobs.job.WorkerRegistry;
import dev.jobs.job.WorkerRegistry.WorkerInfo;
import dev.jobs.worker.WorkerProperties;
import dev.jobs.worker.Workers;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** What the dashboard polls, and the two buttons that let it add and kill workers. */
@RestController
@RequestMapping("/api")
class OverviewController {

    record RunningJob(UUID id, String type, int attempt, Instant leaseExpiresAt) {}

    /** @param local true if this instance hosts the worker, and so can crash it on request */
    record WorkerView(
            String id, int concurrency, Instant startedAt, Instant lastSeenAt, boolean alive, boolean local, List<RunningJob> running) {}

    record Overview(
            Map<String, Long> counts,
            long oldestDueWaitMillis,
            long succeededLastMinute,
            List<WorkerView> workers,
            List<String> types,
            long leaseSeconds) {}

    private final JobStore store;
    private final WorkerRegistry registry;
    private final Workers workers;
    private final WorkerProperties properties;

    OverviewController(JobStore store, WorkerRegistry registry, Workers workers, WorkerProperties properties) {
        this.store = store;
        this.registry = registry;
        this.workers = workers;
        this.properties = properties;
    }

    @GetMapping("/overview")
    Overview overview() {
        List<Job> running = store.running();
        // Silent for two heartbeats: probably gone, though its leases have a while to run.
        List<WorkerView> views = registry.list(properties.worker().heartbeat().multipliedBy(2)).stream()
                .map(worker -> view(worker, running))
                .toList();
        return new Overview(
                store.countsByStatus(),
                store.oldestDueWaitMillis(),
                store.finishedInLastMinute(),
                views,
                workers.knownTypes(),
                properties.worker().lease().toSeconds());
    }

    private WorkerView view(WorkerInfo worker, List<Job> running) {
        List<RunningJob> jobs = running.stream()
                .filter(job -> worker.id().equals(job.lockedBy()))
                .map(job -> new RunningJob(job.id(), job.type(), job.attempts(), job.leaseExpiresAt()))
                .toList();
        return new WorkerView(
                worker.id(),
                worker.concurrency(),
                worker.startedAt(),
                worker.lastSeenAt(),
                worker.alive(),
                workers.find(worker.id()).isPresent(),
                jobs);
    }

    @PostMapping("/workers")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, String> startWorker() {
        return Map.of("id", workers.startAnother().id());
    }

    /** Kills a worker without letting it hand anything back, to show its jobs being recovered. */
    @DeleteMapping("/workers/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void crashWorker(@PathVariable String id) {
        if (!workers.crash(id)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "This instance is not running a worker called " + id + ".");
        }
    }
}
