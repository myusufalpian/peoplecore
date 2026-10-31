package id.mydev.peoplecore.attendance.application.command;

import id.mydev.peoplecore.attendance.application.command.WorkCalendarPayloads.AssignmentPayload;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarPayloads.PublishCalendarPayload;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarPayloads.PublishSchedulePayload;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarResults.AssignmentResult;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarResults.RevisionResult;
import id.mydev.peoplecore.attendance.application.service.WorkCalendarService;
import id.mydev.peoplecore.attendance.application.trail.WorkCalendarTrailMapper;
import id.mydev.peoplecore.common.command.CommandExecutionService;
import id.mydev.peoplecore.common.command.CommandExecutionService.CommandContext;
import id.mydev.peoplecore.common.command.CommandExecutionService.AuditDescriptor;
import id.mydev.peoplecore.common.command.CommandExecutionService.OutboxDescriptor;
import id.mydev.peoplecore.common.command.CommandIds;
import id.mydev.peoplecore.common.command.CommandRetry;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

@Component
public class WorkCalendarCommands {

    public static final String COMMAND_SCHEDULE_PUBLISH = "SCHEDULE_PUBLISH";
    public static final String COMMAND_CALENDAR_PUBLISH = "CALENDAR_PUBLISH";
    public static final String COMMAND_SCHEDULE_ASSIGN = "SCHEDULE_ASSIGN";
    public static final String COMMAND_CALENDAR_ASSIGN = "CALENDAR_ASSIGN";
    private static final Duration RECEIPT_TTL = Duration.ofDays(7);

    private final WorkCalendarService calendars;
    private final CommandExecutionService commands;
    private final WorkCalendarTrailMapper trail;

    public WorkCalendarCommands(WorkCalendarService calendars, CommandExecutionService commands,
                                WorkCalendarTrailMapper trail) {
        this.calendars = calendars;
        this.commands = commands;
        this.trail = trail;
    }

    public RevisionResult publishSchedule(Authentication caller, String idempotencyKey,
                                          PublishSchedulePayload payload) {
        UUID resolvedId = payload.publicId() != null ? payload.publicId()
            : CommandIds.derive(COMMAND_SCHEDULE_PUBLISH, caller.getName(), idempotencyKey);
        var canonical = new PublishSchedulePayload(resolvedId, payload.timezone(), payload.days());
        var context = commandContext(caller, COMMAND_SCHEDULE_PUBLISH, idempotencyKey, canonical);
        return execute(context,
            () -> calendars.publishSchedule(canonical, caller),
            RevisionResult.class,
            result -> trail.scheduleAudit(COMMAND_SCHEDULE_PUBLISH, result, "Work schedule published"),
            result -> trail.scheduleOutbox(COMMAND_SCHEDULE_PUBLISH, result));
    }

    public RevisionResult publishCalendar(Authentication caller, String idempotencyKey,
                                          PublishCalendarPayload payload) {
        UUID resolvedId = payload.publicId() != null ? payload.publicId()
            : CommandIds.derive(COMMAND_CALENDAR_PUBLISH, caller.getName(), idempotencyKey);
        var canonical = new PublishCalendarPayload(resolvedId, payload.location(), payload.holidays());
        var context = commandContext(caller, COMMAND_CALENDAR_PUBLISH, idempotencyKey, canonical);
        return execute(context,
            () -> calendars.publishCalendar(canonical, caller),
            RevisionResult.class,
            result -> trail.calendarAudit(COMMAND_CALENDAR_PUBLISH, result, "Holiday calendar published"),
            result -> trail.calendarOutbox(COMMAND_CALENDAR_PUBLISH, result));
    }

    public AssignmentResult assignSchedule(Authentication caller, String idempotencyKey,
                                           AssignmentPayload payload) {
        UUID resolvedId = payload.assignmentPublicId() != null ? payload.assignmentPublicId()
            : CommandIds.derive(COMMAND_SCHEDULE_ASSIGN, caller.getName(), idempotencyKey);
        var canonical = new AssignmentPayload(resolvedId, payload.employeePublicId(),
            payload.revisionPublicId(), payload.fromDate(), payload.toDate(),
            payload.supersedesPublicId(), payload.backdateReason(), payload.expectedFingerprint());
        var context = commandContext(caller, COMMAND_SCHEDULE_ASSIGN, idempotencyKey, canonical);
        return execute(context,
            () -> calendars.assignSchedule(canonical, caller),
            AssignmentResult.class,
            result -> trail.scheduleAssignmentAudit(COMMAND_SCHEDULE_ASSIGN, result,
                "Employee schedule assigned"),
            result -> trail.scheduleAssignmentOutbox(COMMAND_SCHEDULE_ASSIGN, result));
    }

    public AssignmentResult assignCalendar(Authentication caller, String idempotencyKey,
                                           AssignmentPayload payload) {
        UUID resolvedId = payload.assignmentPublicId() != null ? payload.assignmentPublicId()
            : CommandIds.derive(COMMAND_CALENDAR_ASSIGN, caller.getName(), idempotencyKey);
        var canonical = new AssignmentPayload(resolvedId, payload.employeePublicId(),
            payload.revisionPublicId(), payload.fromDate(), payload.toDate(),
            payload.supersedesPublicId(), payload.backdateReason(), payload.expectedFingerprint());
        var context = commandContext(caller, COMMAND_CALENDAR_ASSIGN, idempotencyKey, canonical);
        return execute(context,
            () -> calendars.assignCalendar(canonical, caller),
            AssignmentResult.class,
            result -> trail.calendarAssignmentAudit(COMMAND_CALENDAR_ASSIGN, result,
                "Employee calendar assigned"),
            result -> trail.calendarAssignmentOutbox(COMMAND_CALENDAR_ASSIGN, result));
    }

    private <T> T execute(CommandContext context, Supplier<T> action, Class<T> resultType,
                          Function<T, AuditDescriptor> audit, Function<T, OutboxDescriptor> outbox) {
        return CommandRetry.withRetry(() -> commands.executeCommand(context, action, resultType, audit, outbox));
    }

    private CommandContext commandContext(Authentication caller, String commandType,
                                           String idempotencyKey, Object payload) {
        return new CommandContext(caller.getName(), commandType, idempotencyKey,
            trail.toPayload(payload), RECEIPT_TTL);
    }
}
