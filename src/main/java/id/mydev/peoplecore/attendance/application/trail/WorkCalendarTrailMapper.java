package id.mydev.peoplecore.attendance.application.trail;

import id.mydev.peoplecore.attendance.application.command.WorkCalendarResults.AssignmentResult;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarResults.RevisionResult;
import id.mydev.peoplecore.common.command.CommandExecutionService.AuditDescriptor;
import id.mydev.peoplecore.common.command.CommandExecutionService.OutboxDescriptor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

@Component
public class WorkCalendarTrailMapper {

    private static final int SCHEMA_VERSION = 1;
    private static final String AGGREGATE_SCHEDULE = "WORK_SCHEDULE_REVISION";
    private static final String AGGREGATE_CALENDAR = "HOLIDAY_CALENDAR_REVISION";
    private static final String AGGREGATE_SCHEDULE_ASSIGNMENT = "SCHEDULE_ASSIGNMENT";
    private static final String AGGREGATE_CALENDAR_ASSIGNMENT = "CALENDAR_ASSIGNMENT";

    public record RevisionEvent(UUID revisionPublicId, String status) { }

    public record AssignmentEvent(UUID assignmentPublicId, UUID employeePublicId, UUID revisionPublicId) { }

    private final ObjectMapper mapper;

    public WorkCalendarTrailMapper(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public AuditDescriptor scheduleAudit(String action, RevisionResult result, String reason) {
        return new AuditDescriptor(action, AGGREGATE_SCHEDULE, null, result.publicId(),
            null, reason, toPayload(new RevisionEvent(result.publicId(), result.status())));
    }

    public AuditDescriptor calendarAudit(String action, RevisionResult result, String reason) {
        return new AuditDescriptor(action, AGGREGATE_CALENDAR, null, result.publicId(),
            null, reason, toPayload(new RevisionEvent(result.publicId(), result.status())));
    }

    public AuditDescriptor scheduleAssignmentAudit(String action, AssignmentResult result, String reason) {
        return new AuditDescriptor(action, AGGREGATE_SCHEDULE_ASSIGNMENT, null, result.publicId(),
            null, reason, toPayload(new AssignmentEvent(result.publicId(),
                result.employeePublicId(), result.revisionPublicId())));
    }

    public AuditDescriptor calendarAssignmentAudit(String action, AssignmentResult result, String reason) {
        return new AuditDescriptor(action, AGGREGATE_CALENDAR_ASSIGNMENT, null, result.publicId(),
            null, reason, toPayload(new AssignmentEvent(result.publicId(),
                result.employeePublicId(), result.revisionPublicId())));
    }

    public OutboxDescriptor scheduleOutbox(String eventType, RevisionResult result) {
        return new OutboxDescriptor(eventType, SCHEMA_VERSION, AGGREGATE_SCHEDULE, null,
            result.publicId(), toPayload(new RevisionEvent(result.publicId(), result.status())));
    }

    public OutboxDescriptor calendarOutbox(String eventType, RevisionResult result) {
        return new OutboxDescriptor(eventType, SCHEMA_VERSION, AGGREGATE_CALENDAR, null,
            result.publicId(), toPayload(new RevisionEvent(result.publicId(), result.status())));
    }

    public OutboxDescriptor scheduleAssignmentOutbox(String eventType, AssignmentResult result) {
        return new OutboxDescriptor(eventType, SCHEMA_VERSION, AGGREGATE_SCHEDULE_ASSIGNMENT, null,
            result.publicId(), toPayload(new AssignmentEvent(result.publicId(),
                result.employeePublicId(), result.revisionPublicId())));
    }

    public OutboxDescriptor calendarAssignmentOutbox(String eventType, AssignmentResult result) {
        return new OutboxDescriptor(eventType, SCHEMA_VERSION, AGGREGATE_CALENDAR_ASSIGNMENT, null,
            result.publicId(), toPayload(new AssignmentEvent(result.publicId(),
                result.employeePublicId(), result.revisionPublicId())));
    }

    public String toPayload(Object payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (Exception ex) {
            throw new IllegalStateException("Trail payload serialization failed", ex);
        }
    }
}
