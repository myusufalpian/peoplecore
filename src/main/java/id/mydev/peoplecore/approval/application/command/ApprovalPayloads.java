package id.mydev.peoplecore.approval.application.command;

import java.util.List;
import java.util.UUID;

public final class ApprovalPayloads {
    private ApprovalPayloads() { }

    public record RulePayload(
        String ruleId,
        int priority,
        String orgUnit,
        String jobLevel,
        UUID reviewerAccountPublicId
    ) { }

    public record PublishRuleSetPayload(
        UUID ruleSetPublicId,
        List<RulePayload> rules
    ) { }

    public record ActivateRuleSetPayload(
        UUID ruleSetPublicId
    ) { }
}
