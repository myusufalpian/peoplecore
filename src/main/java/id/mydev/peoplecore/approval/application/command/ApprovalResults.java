package id.mydev.peoplecore.approval.application.command;

import java.time.Instant;
import java.util.UUID;

public final class ApprovalResults {
    private ApprovalResults() { }

    public record RuleSetResult(
        UUID ruleSetPublicId,
        String status,
        int ruleCount,
        Instant publishedAt
    ) { }

    public record ActivationResult(
        UUID ruleSetPublicId,
        Instant activatedAt
    ) { }

    public record RouteResolution(
        boolean matched,
        UUID ruleSetPublicId,
        String ruleId,
        UUID reviewerAccountPublicId,
        String blockedReason
    ) { }

    public record RoutePreview(
        boolean matched,
        UUID ruleSetPublicId,
        String ruleId,
        UUID reviewerAccountPublicId,
        String blockedReason,
        String orgUnit,
        String jobLevel
    ) { }
}
