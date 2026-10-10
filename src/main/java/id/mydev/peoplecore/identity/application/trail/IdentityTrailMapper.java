package id.mydev.peoplecore.identity.application.trail;

import id.mydev.peoplecore.common.command.CommandExecutionService.AuditDescriptor;
import id.mydev.peoplecore.common.command.CommandExecutionService.OutboxDescriptor;
import id.mydev.peoplecore.identity.application.command.EnrollmentResults.BindingResult;
import id.mydev.peoplecore.identity.application.command.EnrollmentResults.InvitationResult;
import id.mydev.peoplecore.identity.application.command.EnrollmentResults.OffboardResult;
import id.mydev.peoplecore.identity.application.command.EnrollmentResults.RebindResult;
import id.mydev.peoplecore.identity.application.command.EnrollmentResults.RehireResult;
import id.mydev.peoplecore.identity.application.command.EnrollmentResults.RestoreResult;
import id.mydev.peoplecore.organization.application.command.EmployeeResults.EmployeeState;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class IdentityTrailMapper {

    private static final int SCHEMA_VERSION = 1;

    public record InvitationEvent(UUID invitationId, UUID employeeId, String intendedIdentityRef,
                                    String status, Instant expiresAt) { }

    public record BindingEvent(UUID employeeId, String issuer, String subject, Instant createdAt) { }

    public record OffboardEvent(UUID employeeId, UUID accountId, Instant accessEndsAt, String reason) { }

    public record RehireEvent(UUID employeeId, String reason) { }

    public record AccessRestoredEvent(UUID employeeId, UUID accountId, List<String> roles,
                                      String scope, String reason) { }

    public record EmploymentChange(EmployeeState before, EmployeeState after) { }

    private final ObjectMapper mapper;

    public IdentityTrailMapper(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public AuditDescriptor audit(String action, String aggregateType, UUID aggregatePublicId, String reason) {
        return new AuditDescriptor(action, aggregateType, null, aggregatePublicId, null, reason, null);
    }

    public AuditDescriptor employmentAudit(String action, UUID employeeId, String reason,
                                            EmployeeState before, EmployeeState after) {
        return new AuditDescriptor(action, "EMPLOYEE", null, employeeId, null, reason,
            toPayload(new EmploymentChange(before, after)));
    }

    public OutboxDescriptor outbox(String eventType, String aggregateType, UUID aggregatePublicId, Object payload) {
        return new OutboxDescriptor(eventType, SCHEMA_VERSION, aggregateType, null,
            aggregatePublicId, toPayload(payload));
    }

    public InvitationEvent invitationEvent(InvitationResult result) {
        return new InvitationEvent(result.invitationId(), result.employeeId(),
            result.intendedIdentityRef(), result.status(), result.expiresAt());
    }

    public BindingEvent bindingEvent(BindingResult result) {
        return new BindingEvent(result.employeeId(), result.issuer(), result.subject(),
            result.createdAt());
    }

    public OffboardEvent offboardEvent(OffboardResult result, Instant accessEndsAt, String reason) {
        return new OffboardEvent(result.employeeId(), result.accountId(), accessEndsAt, reason);
    }

    public BindingEvent rebindEvent(RebindResult result, String newIssuer, String newSubject) {
        return new BindingEvent(result.employeeId(), newIssuer, newSubject, null);
    }

    public RehireEvent rehireEvent(RehireResult result, String reason) {
        return new RehireEvent(result.employeeId(), reason);
    }

    public AccessRestoredEvent accessRestoredEvent(RestoreResult result, String reason) {
        return new AccessRestoredEvent(result.employeeId(), result.accountId(),
            result.roles(), result.scope(), reason);
    }

    public String toPayload(Object payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (Exception ex) {
            throw new IllegalStateException("Trail payload serialization failed", ex);
        }
    }
}
