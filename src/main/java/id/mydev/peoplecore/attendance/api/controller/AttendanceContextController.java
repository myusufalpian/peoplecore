package id.mydev.peoplecore.attendance.api.controller;

import id.mydev.peoplecore.attendance.api.dto.WorkCalendarDto.AssignmentRequest;
import id.mydev.peoplecore.attendance.api.dto.WorkCalendarDto.AssignmentResponse;
import id.mydev.peoplecore.attendance.api.dto.WorkCalendarDto.ImpactPreviewResponse;
import id.mydev.peoplecore.attendance.api.dto.WorkCalendarDto.OccurrenceListResponse;
import id.mydev.peoplecore.attendance.api.dto.WorkCalendarDto.PreviewRequest;
import id.mydev.peoplecore.attendance.api.dto.WorkCalendarDto.PublishCalendarRequest;
import id.mydev.peoplecore.attendance.api.dto.WorkCalendarDto.PublishScheduleRequest;
import id.mydev.peoplecore.attendance.api.dto.WorkCalendarDto.RevisionResponse;
import id.mydev.peoplecore.attendance.api.mapper.WorkCalendarDtoMapper;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarCommands;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarPayloads.AssignmentPayload;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarPayloads.HolidayPayload;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarPayloads.PreviewPayload;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarPayloads.PublishCalendarPayload;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarPayloads.PublishSchedulePayload;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarPayloads.ScheduleDayPayload;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarResults.AssignmentResult;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarResults.ImpactPreview;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarResults.OccurrenceListResult;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarResults.RevisionResult;
import id.mydev.peoplecore.attendance.application.service.WorkCalendarService;
import id.mydev.peoplecore.attendance.domain.model.WorkScheduleRevision;
import id.mydev.peoplecore.common.api.ApiData;
import id.mydev.peoplecore.common.command.IdempotencyKeys;
import id.mydev.peoplecore.common.security.CurrentCaller;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class AttendanceContextController {

    private final WorkCalendarService calendars;
    private final WorkCalendarCommands commands;

    public AttendanceContextController(WorkCalendarService calendars, WorkCalendarCommands commands) {
        this.calendars = calendars;
        this.commands = commands;
    }

    /**
     * Publication records an immutable revision and deliberately takes no impact
     * fingerprint: it does not alter any employee's effective work context until an
     * assignment is made, and assignments (the fingerprint-bound operations) are
     * where concurrent context changes are rejected.
     */
    @PostMapping("/attendance/schedule-revisions")
    public ResponseEntity<ApiData<RevisionResponse>> publishSchedule(
        @Valid @RequestBody PublishScheduleRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        Authentication caller = CurrentCaller.require();
        IdempotencyKeys.requireValid(idempotencyKey);
        String timezone = request.timezone() == null
            ? WorkScheduleRevision.PILOT_TIMEZONE : request.timezone();
        List<ScheduleDayPayload> days = new ArrayList<>(request.days().size());
        for (var day : request.days()) {
            days.add(WorkCalendarDtoMapper.dayPayload(day));
        }
        RevisionResult result = commands.publishSchedule(caller, idempotencyKey,
            new PublishSchedulePayload(null, timezone, days));
        return ResponseEntity.created(URI.create("/api/v1/attendance/schedule-revisions/" + result.publicId()))
            .body(ApiData.of(WorkCalendarDtoMapper.revision(result)));
    }

    @PostMapping("/attendance/calendar-revisions")
    public ResponseEntity<ApiData<RevisionResponse>> publishCalendar(
        @Valid @RequestBody PublishCalendarRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        Authentication caller = CurrentCaller.require();
        IdempotencyKeys.requireValid(idempotencyKey);
        List<HolidayPayload> holidays = new ArrayList<>(request.holidays().size());
        for (var holiday : request.holidays()) {
            holidays.add(WorkCalendarDtoMapper.holidayPayload(holiday));
        }
        RevisionResult result = commands.publishCalendar(caller, idempotencyKey,
            new PublishCalendarPayload(null, request.location(), holidays));
        return ResponseEntity.created(URI.create("/api/v1/attendance/calendar-revisions/" + result.publicId()))
            .body(ApiData.of(WorkCalendarDtoMapper.revision(result)));
    }

    @PostMapping("/attendance/publish-preview")
    public ResponseEntity<ApiData<ImpactPreviewResponse>> preview(
        @Valid @RequestBody PreviewRequest request
    ) {
        Authentication caller = CurrentCaller.require();
        PreviewPayload payload = new PreviewPayload(request.scheduleRevisionId(),
            request.calendarRevisionId(), request.fromDate(), request.toDate(),
            request.employeeIds(), request.supersedesId(), request.fingerprint());
        ImpactPreview result = calendars.previewImpact(payload, caller);
        return ResponseEntity.ok(ApiData.of(WorkCalendarDtoMapper.preview(result)));
    }

    @PostMapping("/employees/{employeeId}/schedule-assignments")
    public ResponseEntity<ApiData<AssignmentResponse>> assignSchedule(
        @PathVariable("employeeId") UUID employeeId,
        @Valid @RequestBody AssignmentRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        Authentication caller = CurrentCaller.require();
        IdempotencyKeys.requireValid(idempotencyKey);
        AssignmentResult result = commands.assignSchedule(caller, idempotencyKey,
            new AssignmentPayload(null, employeeId, request.revisionId(), request.fromDate(),
                request.toDate(), request.supersedesId(), request.backdateReason(),
                request.expectedFingerprint()));
        return ResponseEntity.created(URI.create(
                "/api/v1/employees/" + employeeId + "/schedule-assignments/" + result.publicId()))
            .body(ApiData.of(WorkCalendarDtoMapper.assignment(result)));
    }

    @PostMapping("/employees/{employeeId}/calendar-assignments")
    public ResponseEntity<ApiData<AssignmentResponse>> assignCalendar(
        @PathVariable("employeeId") UUID employeeId,
        @Valid @RequestBody AssignmentRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        Authentication caller = CurrentCaller.require();
        IdempotencyKeys.requireValid(idempotencyKey);
        AssignmentResult result = commands.assignCalendar(caller, idempotencyKey,
            new AssignmentPayload(null, employeeId, request.revisionId(), request.fromDate(),
                request.toDate(), request.supersedesId(), request.backdateReason(),
                request.expectedFingerprint()));
        return ResponseEntity.created(URI.create(
                "/api/v1/employees/" + employeeId + "/calendar-assignments/" + result.publicId()))
            .body(ApiData.of(WorkCalendarDtoMapper.assignment(result)));
    }

    @GetMapping("/employees/{employeeId}/work-context")
    public ResponseEntity<ApiData<OccurrenceListResponse>> workContext(
        @PathVariable("employeeId") UUID employeeId,
        @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        Authentication caller = CurrentCaller.require();
        OccurrenceListResult result = calendars.queryOccurrences(employeeId, from, to, caller);
        return ResponseEntity.ok(ApiData.of(WorkCalendarDtoMapper.occurrences(result)));
    }
}
