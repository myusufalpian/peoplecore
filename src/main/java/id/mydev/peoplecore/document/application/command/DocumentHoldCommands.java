package id.mydev.peoplecore.document.application.command;

import id.mydev.peoplecore.common.command.CommandExecutionService;
import id.mydev.peoplecore.common.command.CommandExecutionService.AuditDescriptor;
import id.mydev.peoplecore.common.command.CommandExecutionService.CommandContext;
import id.mydev.peoplecore.common.command.CommandIds;
import id.mydev.peoplecore.common.command.CommandRetry;
import id.mydev.peoplecore.common.command.IdempotencyKeys;
import id.mydev.peoplecore.document.application.service.DocumentHoldService;
import id.mydev.peoplecore.document.application.service.DocumentHoldService.Hold;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class DocumentHoldCommands {
    private static final Duration RECEIPT_TTL = Duration.ofDays(7);
    private static final String PLACE = "DOCUMENT_HOLD_PLACE";
    private static final String RELEASE = "DOCUMENT_HOLD_RELEASE";

    private final CommandExecutionService commands;
    private final DocumentHoldService holds;
    private final ObjectMapper mapper;

    public DocumentHoldCommands(CommandExecutionService commands, DocumentHoldService holds, ObjectMapper mapper) {
        this.commands = commands;
        this.holds = holds;
        this.mapper = mapper;
    }

    public Hold place(Authentication caller, String key, UUID documentPublicId, String reason) {
        IdempotencyKeys.requireValid(key);
        UUID holdPublicId = CommandIds.derive(PLACE, caller.getName(), key);
        return execute(caller, key, PLACE, new Payload(documentPublicId, holdPublicId, reason),
            () -> holds.place(holdPublicId, documentPublicId, reason, caller));
    }

    public Hold release(Authentication caller, String key, UUID documentPublicId, UUID holdPublicId, String reason) {
        IdempotencyKeys.requireValid(key);
        return execute(caller, key, RELEASE, new Payload(documentPublicId, holdPublicId, reason),
            () -> holds.release(holdPublicId, documentPublicId, reason, caller));
    }

    public void verifyCommand(String type, JsonNode payload, Authentication caller) {
        holds.verifyAuthority(mapper.readValue(payload.toString(), Payload.class).documentPublicId(), caller);
    }

    public void verifyReplay(String type, JsonNode payload, Authentication caller) {
        verifyCommand(type, payload, caller);
    }

    private Hold execute(Authentication caller, String key, String type, Payload payload, Supplier<Hold> action) {
        Objects.requireNonNull(payload.documentPublicId(), "documentPublicId must not be null");
        Objects.requireNonNull(payload.holdPublicId(), "holdPublicId must not be null");
        CommandContext context = new CommandContext(caller.getName(), type, key,
            mapper.writeValueAsString(payload), RECEIPT_TTL);
        String description = PLACE.equals(type) ? "Document hold placed" : "Document hold released";
        return CommandRetry.withRetry(() -> commands.executeCommand(context, action, Hold.class,
            result -> new AuditDescriptor(type, "DOCUMENT_HOLD", null, result.publicId(), null, description,
                mapper.writeValueAsString(result)),
            result -> null));
    }

    public record Payload(UUID documentPublicId, UUID holdPublicId, String reason) {
        public Payload {
            if (reason == null || reason.isBlank() || reason.length() > DocumentHoldService.MAX_REASON_LENGTH) {
                throw new IllegalArgumentException("A hold reason is required");
            }
        }
    }
}
