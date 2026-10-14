package id.mydev.peoplecore.organization.application.command;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ProfileResults {
    private ProfileResults() { }

    public record ProposalResult(
        UUID proposalPublicId,
        UUID employeePublicId,
        String state,
        long proposalVersion,
        UUID revisionPublicId,
        int revisionNumber,
        UUID assignmentPublicId,
        String blockedReason
    ) { }

    public record RevisionView(
        UUID orgAssignmentId,
        UUID orgAssignmentPredecessorId,
        String organizationContextStatus,
        UUID revisionPublicId,
        int revisionNumber,
        UUID ruleSetPublicId,
        Instant evaluatedAt,
        String evaluatedOrgUnit,
        String evaluatedJobLevel,
        UUID routeReviewerAccountPublicId,
        String blockedReason,
        List<ChangeView> changes
    ) { }

    public record ChangeView(
        String fieldName,
        String baselineValue,
        String proposedValue
    ) { }

    public record DecisionView(
        UUID revisionPublicId,
        UUID assignmentPublicId,
        String action,
        UUID actorAccountPublicId,
        String reason,
        Instant createdAt
    ) { }

    public record AssignmentView(
        UUID assignmentPublicId,
        UUID reviewerAccountPublicId,
        String status,
        String reason,
        Instant createdAt,
        boolean eligible
    ) { }

    public record EvaluationView(
        UUID revisionPublicId,
        Instant evaluatedAt,
        UUID ruleSetPublicId,
        String orgUnit,
        String jobLevel,
        String result,
        String ruleId,
        UUID reviewerAccountPublicId,
        String blockedReason
    ) { }

    public record ProposalDetailResult(
        ProposalResult proposal,
        List<ChangeView> changes,
        AssignmentView assignment,
        List<String> availableActions
    ) { }

    public record AssignmentHistoryView(UUID assignmentPublicId, UUID revisionPublicId, UUID reviewerAccountId,
        String status, String reason, String actor, Instant createdAt, Instant closedAt) { }

    public record ProposalHistoryResult(
        ProposalResult proposal,
        List<RevisionView> revisions,
        List<DecisionView> decisions,
        List<EvaluationView> evaluations,
        List<AssignmentHistoryView> assignments,
        long totalRevisions,
        long totalDecisions,
        long totalEvaluations,
        long totalAssignments
    ) { }

    public record ProposalSummary(
        UUID proposalPublicId,
        UUID employeePublicId,
        String state,
        String blockedReason,
        UUID assignmentPublicId,
        UUID assigneeAccountPublicId,
        String derivedBlockedReason,
        Instant createdAt
    ) { }

    public record ProposalQueueResult(
        List<ProposalSummary> proposals,
        int page,
        int size,
        long total
    ) { }

    public record ContactProfileResult(
        UUID employeePublicId,
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

    public record ContactCorrectionResult(
        UUID employeePublicId,
        long phoneVersion,
        long emailVersion,
        long addressVersion,
        long emergencyVersion
    ) { }

    public record ContactChange(
        Map<String, String> before,
        Map<String, String> after
    ) { }
}
