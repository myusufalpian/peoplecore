package id.mydev.peoplecore.approval.application.trail;

import id.mydev.peoplecore.approval.application.command.ApprovalResults.ActivationResult;
import id.mydev.peoplecore.approval.application.command.ApprovalResults.RuleSetResult;
import id.mydev.peoplecore.common.command.CommandExecutionService.AuditDescriptor;
import id.mydev.peoplecore.common.command.CommandExecutionService.OutboxDescriptor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

@Component
public class ApprovalTrailMapper {

    private static final int SCHEMA_VERSION = 1;
    private static final String AGGREGATE_RULE_SET = "PROFILE_RULE_SET";

    public record RuleSetEvent(UUID ruleSetPublicId, String status, int ruleCount) { }

    public record ActivationEvent(UUID ruleSetPublicId) { }

    private final ObjectMapper mapper;

    public ApprovalTrailMapper(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public AuditDescriptor ruleSetAudit(String action, RuleSetResult result, String reason) {
        return new AuditDescriptor(action, AGGREGATE_RULE_SET, null, result.ruleSetPublicId(),
            null, reason, toPayload(new RuleSetEvent(result.ruleSetPublicId(),
                result.status(), result.ruleCount())));
    }

    public AuditDescriptor activationAudit(String action, ActivationResult result, String reason) {
        return new AuditDescriptor(action, AGGREGATE_RULE_SET, null, result.ruleSetPublicId(),
            null, reason, toPayload(new ActivationEvent(result.ruleSetPublicId())));
    }

    public OutboxDescriptor outbox(String eventType, UUID aggregatePublicId, Object payload) {
        return new OutboxDescriptor(eventType, SCHEMA_VERSION, AGGREGATE_RULE_SET, null,
            aggregatePublicId, toPayload(payload));
    }

    public RuleSetEvent ruleSetEvent(RuleSetResult result) {
        return new RuleSetEvent(result.ruleSetPublicId(), result.status(), result.ruleCount());
    }

    public ActivationEvent activationEvent(ActivationResult result) {
        return new ActivationEvent(result.ruleSetPublicId());
    }

    public String toPayload(Object payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (Exception ex) {
            throw new IllegalStateException("Trail payload serialization failed", ex);
        }
    }
}
