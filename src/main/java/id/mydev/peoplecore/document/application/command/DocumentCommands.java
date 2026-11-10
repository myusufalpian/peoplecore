package id.mydev.peoplecore.document.application.command;

import id.mydev.peoplecore.common.command.CommandConflictException;
import id.mydev.peoplecore.common.command.CommandExecutionService;
import id.mydev.peoplecore.common.command.CommandExecutionService.AuditDescriptor;
import id.mydev.peoplecore.common.command.CommandExecutionService.CommandContext;
import id.mydev.peoplecore.common.command.CommandExecutionService.OutboxDescriptor;
import id.mydev.peoplecore.common.command.CommandIds;
import id.mydev.peoplecore.common.command.CommandRetry;
import id.mydev.peoplecore.common.command.IdempotencyKeys;
import id.mydev.peoplecore.document.application.service.DocumentUploadService;
import id.mydev.peoplecore.document.application.service.DocumentUploadService.PendingUpload;
import id.mydev.peoplecore.document.application.service.DocumentUploadService.Request;
import id.mydev.peoplecore.document.application.service.DocumentUploadService.Upload;
import id.mydev.peoplecore.document.infrastructure.storage.PrivateDocumentStorage;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class DocumentCommands {
    private static final Duration RECEIPT_TTL = Duration.ofDays(7);
    private static final Duration UPLOAD_URL_TTL = Duration.ofMinutes(5);
    private final CommandExecutionService commands;
    private final DocumentUploadService uploads;
    private final ObjectProvider<PrivateDocumentStorage> storage;
    private final ObjectMapper mapper;

    public DocumentCommands(CommandExecutionService commands, DocumentUploadService uploads,
                            ObjectProvider<PrivateDocumentStorage> storage, ObjectMapper mapper) {
        this.commands = commands;
        this.uploads = uploads;
        this.storage = storage;
        this.mapper = mapper;
    }

    public Upload create(Authentication caller, String key, Request request) {
        String type = "DOCUMENT_CREATE";
        IdempotencyKeys.requireValid(key);
        UUID publicId = CommandIds.derive(type, caller.getName(), key);
        return execute(caller, key, type, new Payload(publicId, null, request),
            () -> uploads.create(publicId, request, caller), Upload.class);
    }

    public UploadUrl reserve(Authentication caller, String key, UUID documentId) {
        String type = "DOCUMENT_RESERVE_UPLOAD";
        IdempotencyKeys.requireValid(key);
        UUID uploadId = CommandIds.derive(type, caller.getName(), key);
        PendingUpload reserved = execute(caller, key, type, new Payload(documentId, uploadId, null),
            () -> uploads.reserve(documentId, uploadId, caller), PendingUpload.class);
        PendingUpload current = uploads.currentUpload(documentId, caller);
        if (!current.equals(reserved)) {
            throw new CommandConflictException("Document upload changed");
        }
        PresignedPutObjectRequest signed;
        try {
            signed = storage().presignUpload(current.uploadPublicId(), current.mediaType(), UPLOAD_URL_TTL);
        } catch (SdkClientException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Document storage unavailable");
        }
        if (!uploads.currentUpload(documentId, caller).equals(current)) {
            throw new CommandConflictException("Document upload changed");
        }
        return new UploadUrl(signed.url(), signed.signedHeaders(), signed.expiration());
    }

    public Upload finalizeUpload(Authentication caller, String key, UUID documentId) {
        String type = "DOCUMENT_FINALIZE";
        IdempotencyKeys.requireValid(key);
        uploads.requireUploadAuthority(documentId, caller);
        PendingUpload pending = pendingUpload(documentId, caller);
        String etag = pending == null ? null : stagingEtag(pending, caller);
        UUID attemptId = CommandIds.derive(type, caller.getName(), key);
        return execute(caller, key, type, new Payload(documentId, attemptId, null), () -> {
            if (pending == null) {
                throw new CommandConflictException("Document has no pending upload");
            }
            return uploads.queueVerification(pending, attemptId, etag, caller);
        }, Upload.class);
    }

    public void verifyCommand(String type, JsonNode payload, Authentication caller) {
        Payload parsed = mapper.readValue(payload.toString(), Payload.class);
        if ("DOCUMENT_CREATE".equals(type)) {
            uploads.verifyCreate(parsed.request(), caller);
        } else {
            uploads.requireUploadAuthority(parsed.documentPublicId(), caller);
        }
    }

    public void verifyReplay(String type, JsonNode payload, Authentication caller) {
        Payload parsed = mapper.readValue(payload.toString(), Payload.class);
        uploads.requireUploadAuthority(parsed.documentPublicId(), caller);
    }

    private PendingUpload pendingUpload(UUID documentId, Authentication caller) {
        try {
            return uploads.currentUpload(documentId, caller);
        } catch (CommandConflictException exception) {
            return null;
        }
    }

    private String stagingEtag(PendingUpload pending, Authentication caller) {
        try {
            return storage().headStaging(pending.uploadPublicId(), pending.byteSize()).etag();
        } catch (S3Exception exception) {
            uploads.requireUploadAuthority(pending.documentPublicId(), caller);
            if (exception.statusCode() == 404) {
                throw new CommandConflictException("Document upload has not completed");
            }
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Document storage unavailable");
        } catch (SdkClientException exception) {
            uploads.requireUploadAuthority(pending.documentPublicId(), caller);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Document storage unavailable");
        } catch (IllegalArgumentException exception) {
            uploads.requireUploadAuthority(pending.documentPublicId(), caller);
            throw exception;
        }
    }

    private PrivateDocumentStorage storage() {
        PrivateDocumentStorage configured = storage.getIfAvailable();
        if (configured == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Document storage unavailable");
        }
        return configured;
    }

    private <T> T execute(Authentication caller, String key, String type, Payload payload,
                          Supplier<T> action, Class<T> resultType) {
        IdempotencyKeys.requireValid(key);
        CommandContext context = new CommandContext(caller.getName(), type, key, mapper.writeValueAsString(payload), RECEIPT_TTL);
        String safe = mapper.writeValueAsString(new Trail(payload.documentPublicId(), type));
        return CommandRetry.withRetry(() -> commands.executeCommand(context, action, resultType,
            result -> new AuditDescriptor(type, "DOCUMENT", null, payload.documentPublicId(), null, "Document transition", safe),
            result -> new OutboxDescriptor(type, 1, "DOCUMENT", null, payload.documentPublicId(), safe)));
    }

    private record Payload(UUID documentPublicId, UUID attemptPublicId, Request request) { }
    private record Trail(UUID publicId, String action) { }
    public record UploadUrl(URL url, Map<String, List<String>> headers, Instant expiresAt) {
        @Override
        public String toString() {
            return "UploadUrl[redacted]";
        }
    }
}
