package id.mydev.peoplecore.payslip.application;

import id.mydev.peoplecore.common.command.CommandExecutionService;
import id.mydev.peoplecore.common.command.CommandExecutionService.AuditDescriptor;
import id.mydev.peoplecore.common.command.CommandExecutionService.CommandContext;
import id.mydev.peoplecore.common.command.CommandExecutionService.OutboxDescriptor;
import id.mydev.peoplecore.common.command.CommandIds;
import id.mydev.peoplecore.common.command.CommandRetry;
import id.mydev.peoplecore.common.command.IdempotencyKeys;
import id.mydev.peoplecore.payslip.application.PayslipModels.Draft;
import id.mydev.peoplecore.payslip.application.PayslipModels.Mutation;
import id.mydev.peoplecore.payslip.application.PayslipModels.Result;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class PayslipCommands {
    private final PayslipService service;
    private final CommandExecutionService commands;
    private final ObjectMapper mapper;

    public PayslipCommands(PayslipService service, CommandExecutionService commands, ObjectMapper mapper) {
        this.service = service;
        this.commands = commands;
        this.mapper = mapper;
    }

    public Result create(Authentication caller, String key, Draft draft) {
        String type = "PAYSLIP_BATCH_CREATE";
        UUID batch = CommandIds.derive(type, caller.getName(), key);
        return execute(caller, key, type, new Mutation(batch, draft, null), () -> service.create(batch, draft, caller));
    }

    public Result edit(Authentication caller, String key, UUID batch, Draft draft, long expected) {
        return execute(caller, key, "PAYSLIP_BATCH_EDIT", new Mutation(batch, draft, expected),
            () -> service.edit(batch, draft, expected, caller));
    }

    public Result review(Authentication caller, String key, UUID batch, long expected) {
        return execute(caller, key, "PAYSLIP_BATCH_REVIEW", new Mutation(batch, null, expected),
            () -> service.review(batch, expected, caller));
    }

    public Result publish(Authentication caller, String key, UUID batch, long expected) {
        return execute(caller, key, "PAYSLIP_BATCH_PUBLISH", new Mutation(batch, null, expected),
            () -> service.publish(batch, expected, caller));
    }

    private Result execute(Authentication caller, String key, String type, Mutation payload, Supplier<Result> action) {
        IdempotencyKeys.requireValid(key);
        CommandContext context = new CommandContext(caller.getName(), type, key, mapper.writeValueAsString(payload), Duration.ofDays(7));
        return CommandRetry.withRetry(() -> commands.executeCommand(context, action, Result.class,
            result -> new AuditDescriptor(type, "PAYSLIP_BATCH", null, result.batchPublicId(), null,
                "Payslip batch transition", mapper.writeValueAsString(result)),
            result -> type.equals("PAYSLIP_BATCH_PUBLISH")
                ? new OutboxDescriptor(type, 1, "PAYSLIP_BATCH", null, result.batchPublicId(), mapper.writeValueAsString(result))
                : null));
    }
}
