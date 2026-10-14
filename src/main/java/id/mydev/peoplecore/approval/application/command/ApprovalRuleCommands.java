package id.mydev.peoplecore.approval.application.command;

import id.mydev.peoplecore.approval.application.command.ApprovalPayloads.ActivateRuleSetPayload;
import id.mydev.peoplecore.approval.application.command.ApprovalPayloads.PublishRuleSetPayload;
import id.mydev.peoplecore.approval.application.command.ApprovalPayloads.RulePayload;
import id.mydev.peoplecore.approval.application.command.ApprovalResults.ActivationResult;
import id.mydev.peoplecore.approval.application.command.ApprovalResults.RuleSetResult;
import id.mydev.peoplecore.approval.application.service.ApprovalRuleService;
import id.mydev.peoplecore.approval.application.trail.ApprovalTrailMapper;
import id.mydev.peoplecore.common.command.CommandExecutionService;
import id.mydev.peoplecore.common.command.CommandExecutionService.CommandContext;
import id.mydev.peoplecore.common.command.CommandExecutionService.AuditDescriptor;
import id.mydev.peoplecore.common.command.CommandExecutionService.OutboxDescriptor;
import id.mydev.peoplecore.common.command.CommandIds;
import id.mydev.peoplecore.common.command.CommandRetry;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

@Component
public class ApprovalRuleCommands {

    private static final String COMMAND_PUBLISH = "PROFILE_RULE_PUBLISH";
    private static final String COMMAND_ACTIVATE = "PROFILE_RULE_ACTIVATE";
    private static final String AGGREGATE_RULE_SET = "PROFILE_RULE_SET";
    private static final Duration RECEIPT_TTL = Duration.ofDays(7);

    private final ApprovalRuleService rules;
    private final CommandExecutionService commands;
    private final ApprovalTrailMapper trail;

    public ApprovalRuleCommands(ApprovalRuleService rules, CommandExecutionService commands,
                                ApprovalTrailMapper trail) {
        this.rules = rules;
        this.commands = commands;
        this.trail = trail;
    }

    public RuleSetResult publish(Authentication caller, String idempotencyKey,
                                 UUID ruleSetPublicId, List<RulePayload> ruleInputs) {
        UUID resolvedId = ruleSetPublicId != null ? ruleSetPublicId
            : CommandIds.derive(COMMAND_PUBLISH, caller.getName(), idempotencyKey);
        var payload = new PublishRuleSetPayload(resolvedId, List.copyOf(ruleInputs));
        var context = commandContext(caller, COMMAND_PUBLISH, idempotencyKey, payload);
        return execute(context,
            () -> rules.publishRuleSet(resolvedId, payload.rules(), caller),
            RuleSetResult.class,
            result -> trail.ruleSetAudit(COMMAND_PUBLISH, result,
                "Profile approval rules published"),
            result -> trail.outbox(COMMAND_PUBLISH, result.ruleSetPublicId(),
                trail.ruleSetEvent(result)));
    }

    public ActivationResult activate(Authentication caller, String idempotencyKey, UUID ruleSetPublicId) {
        var payload = new ActivateRuleSetPayload(ruleSetPublicId);
        var context = commandContext(caller, COMMAND_ACTIVATE, idempotencyKey, payload);
        return execute(context,
            () -> rules.activateRuleSet(ruleSetPublicId, caller),
            ActivationResult.class,
            result -> trail.activationAudit(COMMAND_ACTIVATE, result,
                "Profile approval rules activated"),
            result -> trail.outbox(COMMAND_ACTIVATE, result.ruleSetPublicId(),
                trail.activationEvent(result)));
    }

    private <T> T execute(CommandContext context, Supplier<T> action, Class<T> resultType,
                          Function<T, AuditDescriptor> audit, Function<T, OutboxDescriptor> outbox) {
        return CommandRetry.withRetry(() -> commands.executeCommand(context, action, resultType, audit, outbox));
    }

    private CommandContext commandContext(Authentication caller, String commandType,
                                           String idempotencyKey, Object payload) {
        return new CommandContext(caller.getName(), commandType, idempotencyKey,
            trail.toPayload(payload), RECEIPT_TTL);
    }
}
