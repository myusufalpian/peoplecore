package id.mydev.peoplecore.reimbursement.application.command;

import id.mydev.peoplecore.common.command.CommandExecutionService;
import id.mydev.peoplecore.common.command.CommandExecutionService.AuditDescriptor;
import id.mydev.peoplecore.common.command.CommandExecutionService.CommandContext;
import id.mydev.peoplecore.common.command.CommandExecutionService.OutboxDescriptor;
import id.mydev.peoplecore.common.command.CommandIds;
import id.mydev.peoplecore.common.command.CommandRetry;
import id.mydev.peoplecore.common.command.IdempotencyKeys;
import id.mydev.peoplecore.reimbursement.application.command.ReimbursementModels.Action;
import id.mydev.peoplecore.reimbursement.application.command.ReimbursementModels.Draft;
import id.mydev.peoplecore.reimbursement.application.command.ReimbursementModels.Receipt;
import id.mydev.peoplecore.reimbursement.application.command.ReimbursementModels.View;
import id.mydev.peoplecore.reimbursement.application.service.ReimbursementCategoryService;
import id.mydev.peoplecore.reimbursement.application.service.ReimbursementCategoryService.Category;
import id.mydev.peoplecore.reimbursement.application.service.ReimbursementWorkflowService;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class ReimbursementCommands {
    private static final Duration RECEIPT_TTL = Duration.ofDays(7);
    private final CommandExecutionService commands;
    private final ReimbursementWorkflowService workflow;
    private final ReimbursementCategoryService categories;
    private final ObjectMapper mapper;

    public ReimbursementCommands(CommandExecutionService commands, ReimbursementWorkflowService workflow,
                                 ReimbursementCategoryService categories, ObjectMapper mapper) {
        this.commands = commands;
        this.workflow = workflow;
        this.categories = categories;
        this.mapper = mapper;
    }

    public Receipt create(Authentication caller, String key, Draft draft) {
        String type = "REIMBURSEMENT_CREATE";
        UUID id = CommandIds.derive(type, caller.getName(), key);
        return execute(caller, key, type, new Payload(draft.employeePublicId(), id, draft),
            () -> receipt(workflow.create(id, draft, caller)));
    }

    public Receipt edit(Authentication caller, String key, UUID id, Draft draft) {
        return execute(caller, key, "REIMBURSEMENT_EDIT", new Payload(draft.employeePublicId(), id, draft),
            () -> receipt(workflow.edit(id, draft, caller)));
    }

    public Receipt act(Authentication caller, String key, UUID id, String action, Action payload) {
        String type = "REIMBURSEMENT_" + action;
        return execute(caller, key, type, new Payload(payload.employeePublicId(), id, payload),
            () -> receipt(workflow.act(id, action, payload, caller)));
    }

    public Receipt publishCategory(Authentication caller, String key, Category category) {
        String type = "REIMBURSEMENT_CATEGORY_PUBLISH";
        UUID id = CommandIds.derive(type, caller.getName(), key);
        return execute(caller, key, type, new Payload(null, id, category), () -> {
            categories.publish(id, category, caller);
            return new Receipt(id, "PUBLISHED", 0);
        });
    }

    private Receipt execute(Authentication caller, String key, String type, Payload payload, Supplier<Receipt> action) {
        IdempotencyKeys.requireValid(key);
        CommandContext context = new CommandContext(caller.getName(), type, key, mapper.writeValueAsString(payload), RECEIPT_TTL);
        return CommandRetry.withRetry(() -> commands.executeCommand(context, action, Receipt.class,
            result -> new AuditDescriptor(type, "REIMBURSEMENT", null, result.publicId(), null,
                "Reimbursement transition", mapper.writeValueAsString(result)),
            result -> new OutboxDescriptor(type, 1, "REIMBURSEMENT", null, result.publicId(), mapper.writeValueAsString(result))));
    }

    private static Receipt receipt(View view) {
        return new Receipt(view.publicId(), view.status(), view.version());
    }

    private record Payload(UUID employeePublicId, UUID claimPublicId, Object data) { }
}
