package id.mydev.peoplecore.identity.application.command;

import id.mydev.peoplecore.organization.application.command.EmployeeResults.EmployeeState;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class EnrollmentResults {
    private EnrollmentResults() { }

    public record InvitationResult(
        UUID invitationId,
        UUID employeeId,
        String intendedIdentityRef,
        String status,
        String deliveryStatus,
        Instant expiresAt
    ) { }

    public record IssuedInvitation(InvitationResult result, String secret) { }

    public record BindingResult(
        UUID employeeId,
        UUID invitationId,
        String issuer,
        String subject,
        Instant createdAt
    ) { }

    public record OffboardResult(
        UUID employeeId,
        UUID accountId,
        EmployeeState before,
        EmployeeState after
    ) {
        public OffboardResult(UUID employeeId, UUID accountId) { this(employeeId, accountId, null, null); }
    }

    public record RebindResult(
        UUID employeeId,
        UUID accountId
    ) { }

    public record RehireResult(
        UUID employeeId,
        EmployeeState before,
        EmployeeState after
    ) {
        public RehireResult(UUID employeeId) { this(employeeId, null, null); }
    }

    public record RestoreResult(
        UUID employeeId,
        UUID accountId,
        List<String> roles,
        String scope
    ) { }
}
