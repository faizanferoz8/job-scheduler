package dev.jobs.api;

import dev.jobs.job.Job;
import dev.jobs.job.JobAttempt;
import dev.jobs.job.JobStatus;
import dev.jobs.job.JobStore;
import dev.jobs.job.JobStore.Enqueued;
import dev.jobs.job.NewJob;
import dev.jobs.worker.Workers;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/jobs")
class JobController {

    /**
     * @param count how many copies to enqueue; more than one is for load and demos, and
     *     cannot be combined with an idempotency key
     */
    record EnqueueRequest(
            @NotBlank String type,
            JsonNode payload,
            @Min(-100) @Max(100) Integer priority,
            @Min(0) @Max(86_400) Integer delaySeconds,
            @Min(1) @Max(20) Integer maxAttempts,
            @Min(1) @Max(1_000) Integer count) {}

    record JobView(
            UUID id,
            String queue,
            String type,
            JsonNode payload,
            JobStatus status,
            int priority,
            Instant runAt,
            int attempts,
            int maxAttempts,
            String worker,
            String lastError,
            boolean scheduled,
            Instant createdAt,
            Instant finishedAt) {}

    record JobDetail(JobView job, List<JobAttempt> attempts) {}

    private final JobStore store;
    private final Workers workers;
    private final JsonMapper json;
    private final String queue;

    JobController(JobStore store, Workers workers, JsonMapper json, dev.jobs.worker.WorkerProperties properties) {
        this.store = store;
        this.workers = workers;
        this.json = json;
        this.queue = properties.worker().queue();
    }

    @PostMapping
    ResponseEntity<List<JobView>> enqueue(
            @Valid @RequestBody EnqueueRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        if (!workers.knownTypes().contains(request.type())) {
            // Refused here rather than left to fail on a worker five times over.
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "There is no handler for job type '" + request.type() + "'. Known types: " + workers.knownTypes() + ".");
        }
        int count = request.count() == null ? 1 : request.count();
        if (count > 1 && idempotencyKey != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "An idempotency key identifies one job; do not combine it with count.");
        }
        NewJob job = new NewJob(
                queue,
                request.type(),
                request.payload() == null ? "{}" : json.writeValueAsString(request.payload()),
                request.priority() == null ? 0 : request.priority(),
                Duration.ofSeconds(request.delaySeconds() == null ? 0 : request.delaySeconds()),
                request.maxAttempts() == null ? 5 : request.maxAttempts(),
                idempotencyKey);
        List<JobView> created = new ArrayList<>();
        boolean anyNew = false;
        for (int i = 0; i < count; i++) {
            Enqueued enqueued = store.enqueue(job);
            anyNew |= enqueued.created();
            created.add(view(enqueued.job()));
        }
        // 201 for a new job, 200 when the key matched one that already existed.
        return ResponseEntity.status(anyNew ? HttpStatus.CREATED : HttpStatus.OK).body(created);
    }

    @GetMapping
    List<JobView> list(@RequestParam(required = false) JobStatus status, @RequestParam(defaultValue = "50") int limit) {
        return store.list(status, Math.clamp(limit, 1, 200)).stream().map(this::view).toList();
    }

    @GetMapping("/{id}")
    JobDetail get(@PathVariable UUID id) {
        return new JobDetail(view(find(id)), store.attempts(id));
    }

    @PostMapping("/{id}/cancel")
    JobView cancel(@PathVariable UUID id) {
        if (!store.cancel(id)) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "Only a queued job can be cancelled; this one is " + find(id).status() + ".");
        }
        return view(find(id));
    }

    @PostMapping("/{id}/retry")
    JobView retry(@PathVariable UUID id) {
        if (!store.retry(id)) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "Only a dead or cancelled job can be retried; this one is " + find(id).status() + ".");
        }
        return view(find(id));
    }

    private Job find(UUID id) {
        return store.find(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "There is no job " + id + "."));
    }

    private JobView view(Job job) {
        return new JobView(
                job.id(),
                job.queue(),
                job.type(),
                json.readTree(job.payload()),
                job.status(),
                job.priority(),
                job.runAt(),
                job.attempts(),
                job.maxAttempts(),
                job.lockedBy(),
                job.lastError(),
                job.scheduleId() != null,
                job.createdAt(),
                job.finishedAt());
    }
}
