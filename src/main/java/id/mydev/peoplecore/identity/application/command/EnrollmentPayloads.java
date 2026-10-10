package id.mydev.peoplecore.identity.application.command;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public final class EnrollmentPayloads {
    private EnrollmentPayloads() { }

    public record IssueInvitationPayload(UUID employeeId, UUID invitationId, String intendedIdentityRef,
                                          String expectedIssuer, String expectedSubject, Long timeToLiveSeconds) { }

    public record ActivatePayload(UUID invitationId, String secret) { }

    public record OffboardPayload(UUID employeeId, Instant accessEndsAt, LocalTime cutoffTime,
                                   String cutoffTimezone, String reason) { }

    public record RebindPayload(UUID employeeId, String newIssuer, String newSubject, String reason) { }

    public record RehirePayload(UUID employeeId, String reason) { }

    public record RestoreAccessPayload(UUID employeeId, List<String> roles, String scope, String reason) { }
}
