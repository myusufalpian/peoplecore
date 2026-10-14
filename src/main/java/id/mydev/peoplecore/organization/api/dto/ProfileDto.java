package id.mydev.peoplecore.organization.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ProfileDto {
    private ProfileDto() { }

    public record ChangeRequest(
        @NotBlank @Size(max = 64) String fieldName,
        @NotNull @Size(max = 1000) String proposedValue
    ) { }

    public record CreateProposalRequest(
        @NotNull List<@Valid ChangeRequest> changes
    ) { }

    public record EditProposalRequest(
        long expectedVersion,
        @NotNull List<@Valid ChangeRequest> changes
    ) { }

    public record SubmitProposalRequest(
        long expectedVersion
    ) { }

    public record ResubmitProposalRequest(
        long expectedVersion,
        @NotNull List<@Valid ChangeRequest> changes
    ) { }

    public record WithdrawProposalRequest(
        long expectedVersion
    ) { }

    public record DecideProposalRequest(
        long expectedVersion,
        @NotNull UUID revisionId,
        @NotNull UUID assignmentId,
        @Size(max = 1000) String reason
    ) { }

    public record ReassignProposalRequest(
        long expectedVersion,
        @NotNull UUID assignmentId,
        @NotNull UUID replacementAccountId,
        @NotBlank @Size(max = 1000) String reason
    ) { }

    public record RetryRouteProposalRequest(
        long expectedVersion
    ) { }

    public record MasterCorrectRequest(
        long expectedPhoneVersion,
        long expectedEmailVersion,
        long expectedAddressVersion,
        long expectedEmergencyVersion,
        @NotNull List<@Valid ChangeRequest> changes,
        @NotBlank @Size(max = 1000) String reason
    ) { }

    public record ProposalResponse(
        UUID proposalId,
        UUID employeeId,
        String state,
        long proposalVersion,
        UUID revisionId,
        int revisionNumber,
        UUID assignmentId,
        String blockedReason
    ) { }

    public record ChangeResponse(
        String fieldName,
        String baselineValue,
        String proposedValue
    ) { }

    public record AssignmentResponse(
        UUID assignmentId,
        UUID reviewerAccountId,
        String status,
        String reason,
        Instant createdAt
    ) { }

    public record ProposalDetailResponse(
        ProposalResponse proposal,
        List<ChangeResponse> changes,
        AssignmentResponse assignment,
        List<String> availableActions
    ) { }

    public record RevisionResponse(
        UUID orgAssignmentId,
        UUID orgAssignmentPredecessorId,
        String organizationContextStatus,
        UUID revisionId,
        int revisionNumber,
        UUID ruleSetId,
        Instant evaluatedAt,
        String evaluatedOrgUnit,
        String evaluatedJobLevel,
        UUID reviewerAccountId,
        String blockedReason,
        List<ChangeResponse> changes
    ) { }

    public record DecisionResponse(
        UUID revisionId,
        UUID assignmentId,
        String action,
        UUID actorAccountId,
        String reason,
        Instant createdAt
    ) { }

    public record EvaluationResponse(
        UUID revisionId,
        Instant evaluatedAt,
        UUID ruleSetId,
        String orgUnit,
        String jobLevel,
        String result,
        String ruleId,
        UUID reviewerAccountId,
        String blockedReason
    ) { }

    public record AssignmentHistoryResponse(UUID assignmentId, UUID revisionId, UUID reviewerAccountId,
        String status, String reason, String actor, Instant createdAt, Instant closedAt) { }

    public record HistoryResponse(
        ProposalResponse proposal,
        List<RevisionResponse> revisions,
        List<DecisionResponse> decisions,
        List<EvaluationResponse> evaluations,
        List<AssignmentHistoryResponse> assignments
    ) { }

    public record ProposalSummaryResponse(
        UUID proposalId,
        UUID employeeId,
        String state,
        String blockedReason,
        Instant createdAt
    ) { }

    public record QueueResponse(
        List<ProposalSummaryResponse> proposals
    ) { }

    public record ContactProfileResponse(
        UUID employeeId,
        String personalPhone,
        String personalEmail,
        String residentialAddress,
        String emergencyContactName,
        String emergencyContactRelationship,
        String emergencyContactPhone,
        long phoneVersion,
        long emailVersion,
        long addressVersion,
        long emergencyVersion
    ) { }
}
