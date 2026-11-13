package id.mydev.peoplecore.document.application.command;

import id.mydev.peoplecore.common.command.CommandExecutionService;
import id.mydev.peoplecore.common.command.CommandExecutionService.AuditDescriptor;
import id.mydev.peoplecore.common.command.CommandExecutionService.CommandContext;
import id.mydev.peoplecore.common.command.CommandRetry;
import id.mydev.peoplecore.common.command.IdempotencyKeys;
import id.mydev.peoplecore.document.application.service.DocumentDeletionService;
import id.mydev.peoplecore.document.application.service.DocumentDeletionService.Deletion;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class DocumentDeletionCommands {
    private static final Duration RECEIPT_TTL = Duration.ofDays(7);
    private static final String CLAIM = "DOCUMENT_DELETE_CLAIM";
    private static final String RESTORE = "DOCUMENT_DELETE_RESTORE";

    private final CommandExecutionService commands;
    private final DocumentDeletionService deletions;
    private final ObjectMapper mapper;

    public DocumentDeletionCommands(CommandExecutionService commands, DocumentDeletionService deletions,
                                    ObjectMapper mapper) {
        this.commands = commands;
        this.deletions = deletions;
        this.mapper = mapper;
    }

    public Deletion claim(Authentication caller, String key, UUID documentPublicId) {
        IdempotencyKeys.requireValid(key);
        return execute(caller, key, CLAIM, new Payload(documentPublicId),
            () -> deletions.claim(documentPublicId, caller), "Document deletion claimed");
    }

    public Deletion restore(Authentication caller, String key, UUID documentPublicId) {
        IdempotencyKeys.requireValid(key);
        return execute(caller, key, RESTORE, new Payload(documentPublicId),
            () -> deletions.restore(documentPublicId, caller), "Document deletion restored");
    }

    public void verifyCommand(String type, JsonNode payload, Authentication caller) {
        deletions.status(mapper.readValue(payload.toString(), Payload.class).documentPublicId(), caller);
    }

    public void verifyReplay(String type, JsonNode payload, Authentication caller) {
        verifyCommand(type, payload, caller);
    }

    private Deletion execute(Authentication caller, String key, String type, Payload payload,
                             Supplier<Deletion> action, String description) {
        Objects.requireNonNull(payload.documentPublicId(), "documentPublicId must not be null");
        CommandContext context = new CommandContext(caller.getName(), type, key,
            mapper.writeValueAsString(payload), RECEIPT_TTL);
        return CommandRetry.withRetry(() -> commands.executeCommand(context, action, Deletion.class,
            result -> new AuditDescriptor(type, "DOCUMENT", null, result.documentPublicId(), null, description,
                mapper.writeValueAsString(result)),
            result -> null));
    }

    public record Payload(UUID documentPublicId) { }
}
