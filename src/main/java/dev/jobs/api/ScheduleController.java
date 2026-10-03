package dev.jobs.api;

import dev.jobs.schedule.Schedule;
import dev.jobs.schedule.ScheduleRunner;
import dev.jobs.worker.WorkerProperties;
import dev.jobs.worker.Workers;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/schedules")
class ScheduleController {

    record CreateSchedule(@NotBlank @Size(max = 100) String name, @NotBlank String cron, @NotBlank String type, JsonNode payload) {}

    record SetEnabled(@NotNull Boolean enabled) {}

    record ScheduleView(
            UUID id, String name, String cron, String type, JsonNode payload, boolean enabled, Instant nextFireAt, Instant lastFiredAt) {}

    private final ScheduleRunner schedules;
    private final Workers workers;
    private final JsonMapper json;
    private final String queue;

    ScheduleController(ScheduleRunner schedules, Workers workers, JsonMapper json, WorkerProperties properties) {
        this.schedules = schedules;
        this.workers = workers;
        this.json = json;
        this.queue = properties.worker().queue();
    }

    @GetMapping
    List<ScheduleView> list() {
        return schedules.list().stream().map(this::view).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ScheduleView create(@Valid @RequestBody CreateSchedule request) {
        if (!CronExpression.isValidExpression(request.cron())) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "'" + request.cron() + "' is not a cron expression. Use six fields: second minute hour day month weekday,"
                            + " for example '0 */5 * * * *' for every five minutes.");
        }
        if (!workers.knownTypes().contains(request.type())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "There is no handler for job type '" + request.type() + "'.");
        }
        String payload = request.payload() == null ? "{}" : json.writeValueAsString(request.payload());
        return view(schedules.create(request.name().trim(), request.cron().trim(), queue, request.type(), payload));
    }

    @PutMapping("/{id}/enabled")
    ScheduleView setEnabled(@PathVariable UUID id, @Valid @RequestBody SetEnabled request) {
        return schedules.setEnabled(id, request.enabled()).map(this::view).orElseThrow(() -> missing(id));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id) {
        if (!schedules.delete(id)) {
            throw missing(id);
        }
    }

    private static ApiException missing(UUID id) {
        return new ApiException(HttpStatus.NOT_FOUND, "There is no schedule " + id + ".");
    }

    private ScheduleView view(Schedule schedule) {
        return new ScheduleView(
                schedule.id(),
                schedule.name(),
                schedule.cron(),
                schedule.type(),
                json.readTree(schedule.payload()),
                schedule.enabled(),
                schedule.nextFireAt(),
                schedule.lastFiredAt());
    }
}
