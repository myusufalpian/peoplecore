package id.mydev.peoplecore.attendance.api.controller;

import id.mydev.peoplecore.attendance.application.service.WorkRevisionQueryService;
import id.mydev.peoplecore.common.api.ApiData;
import id.mydev.peoplecore.common.security.CurrentCaller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/attendance")
public class WorkRevisionQueryController {
    private final WorkRevisionQueryService queries;
    public WorkRevisionQueryController(WorkRevisionQueryService queries) { this.queries = queries; }

    @GetMapping("/schedule-revisions")
    public ApiData<List<WorkRevisionQueryService.Summary>> schedules(
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return queries.schedules(CurrentCaller.require(), page, size);
    }

    @GetMapping("/calendar-revisions")
    public ApiData<List<WorkRevisionQueryService.Summary>> calendars(
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return queries.calendars(CurrentCaller.require(), page, size);
    }

    @GetMapping("/schedule-revisions/{id}")
    public ApiData<WorkRevisionQueryService.Schedule> schedule(@PathVariable UUID id) {
        return ApiData.of(queries.schedule(CurrentCaller.require(), id));
    }

    @GetMapping("/calendar-revisions/{id}")
    public ApiData<WorkRevisionQueryService.Calendar> calendar(@PathVariable UUID id) {
        return ApiData.of(queries.calendar(CurrentCaller.require(), id));
    }
}
