package id.mydev.peoplecore.identity.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

public final class EnrollmentDto {
    private EnrollmentDto() { }

    public record IssueInvitationRequest(
        @NotNull UUID employeeId,
        @NotBlank @Size(max = 255) String intendedIdentityRef,
        @NotBlank @Size(max = 255) String expectedIssuer,
        @NotBlank @Size(max = 255) String expectedSubject
    ) { }

    public record ReissueInvitationRequest(
        @NotBlank @Size(max = 255) String intendedIdentityRef,
        @NotBlank @Size(max = 255) String expectedIssuer,
        @NotBlank @Size(max = 255) String expectedSubject
    ) { }

    public record InvitationResponse(
        UUID id,
        UUID employeeId,
        String intendedIdentityRef,
        String status,
        String deliveryStatus,
        Instant expiresAt,
        String secret
    ) { }

    public record ActivateRequest(
        @NotNull UUID invitationId,
        @NotBlank String secret
    ) { }

    public record BindingResponse(
        UUID employeeId,
        String issuer,
        String subject,
        Instant createdAt
    ) { }

    public record OffboardRequest(
        @NotNull Instant accessEndsAt,
        LocalTime cutoffTime,
        @Size(max = 64) String cutoffTimezone,
        @Size(max = 500) String reason
    ) { }

    public record RebindRequest(
        @NotBlank @Size(max = 255) String newIssuer,
        @NotBlank @Size(max = 255) String newSubject,
        @NotBlank @Size(max = 500) String reason
    ) { }

    public record RehireRequest(
        @Size(max = 500) String reason
    ) { }

    public record RestoreAccessRequest(
        @NotNull @Size(min = 1, max = 10) java.util.List<@NotBlank @Size(max = 32) String> roles,
        @Size(max = 128) String scope,
        @Size(max = 500) String reason
    ) { }
}
