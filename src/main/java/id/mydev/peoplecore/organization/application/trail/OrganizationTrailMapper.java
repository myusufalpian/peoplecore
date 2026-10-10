package id.mydev.peoplecore.organization.application.trail;

import id.mydev.peoplecore.common.command.CommandExecutionService.AuditDescriptor;
import id.mydev.peoplecore.common.command.CommandExecutionService.OutboxDescriptor;
import id.mydev.peoplecore.organization.application.command.EmployeeResults.AssignmentResult;
import id.mydev.peoplecore.organization.application.command.EmployeeResults.EmployeeResult;
import id.mydev.peoplecore.organization.application.command.EmployeeResults.EmployeeState;
import id.mydev.peoplecore.organization.application.command.EmployeeResults.AssignmentState;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
@Component
public class OrganizationTrailMapper {

    private static final int SCHEMA_VERSION = 1;

    public record EmployeeEvent(UUID employeeId, String employeeNumber, String status,
                                LocalDate employmentStartDate, LocalDate employmentEndDate) { }

    public record AssignmentEvent(UUID employeeId, UUID assignmentId, String orgUnit,
                                    String jobLevel, Instant validFrom, Instant validTo, UUID managerId,
                                    UUID supersedesId, UUID retainedAssignmentId) { }

    public record EmployeeChange(EmployeeState before, EmployeeState after) { }

    public record AssignmentChange(AssignmentState before, AssignmentState after, UUID retainedAssignmentId) { }

    private final ObjectMapper mapper;

    public OrganizationTrailMapper(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public AuditDescriptor audit(String action, String aggregateType, UUID aggregatePublicId, String reason) {
        return new AuditDescriptor(action, aggregateType, null, aggregatePublicId, null, reason, null);
    }

    public AuditDescriptor employeeAudit(String action, EmployeeResult result, String reason) {
        EmployeeState after = new EmployeeState(result.employeeNumber(), result.status(),
            result.employmentStartDate(), result.employmentEndDate());
        return changeAudit(action, result.employeeId(), reason, new EmployeeChange(result.before(), after));
    }

    public AuditDescriptor assignmentAudit(String action, AssignmentResult result, String reason) {
        AssignmentState after = new AssignmentState(result.assignmentId(), result.orgUnit(), result.managerId(),
            result.jobLevel(), result.validFrom(), result.validTo());
        return changeAudit(action, result.employeeId(), reason,
            new AssignmentChange(result.before(), after, result.retainedAssignmentId()));
    }

    private AuditDescriptor changeAudit(String action, UUID employeeId, String reason, Object change) {
        return new AuditDescriptor(action, "EMPLOYEE", null, employeeId, null, reason, toPayload(change));
    }

    public OutboxDescriptor outbox(String eventType, String aggregateType, UUID aggregatePublicId, Object payload) {
        return new OutboxDescriptor(eventType, SCHEMA_VERSION, aggregateType, null,
            aggregatePublicId, toPayload(payload));
    }

    public EmployeeEvent employeeEvent(EmployeeResult result) {
        return new EmployeeEvent(result.employeeId(), result.employeeNumber(), result.status(),
            result.employmentStartDate(), result.employmentEndDate());
    }

    public AssignmentEvent assignmentEvent(AssignmentResult result) {
        return new AssignmentEvent(result.employeeId(), result.assignmentId(), result.orgUnit(),
            result.jobLevel(), result.validFrom(), result.validTo(), result.managerId(),
            result.supersedesId(), result.retainedAssignmentId());
    }

    public String toPayload(Object payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (Exception ex) {
            throw new IllegalStateException("Trail payload serialization failed", ex);
        }
    }
}
