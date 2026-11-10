package id.mydev.peoplecore.document.api;

import id.mydev.peoplecore.common.api.ApiData;
import id.mydev.peoplecore.common.security.CurrentCaller;
import id.mydev.peoplecore.document.application.command.DocumentCommands;
import id.mydev.peoplecore.document.application.service.DocumentStatusQuery;
import id.mydev.peoplecore.document.application.service.DocumentUploadService.Request;
import id.mydev.peoplecore.document.application.service.DocumentUploadService.Upload;
import id.mydev.peoplecore.document.application.service.VerifiedDocumentAccess.Purpose;
import id.mydev.peoplecore.document.infrastructure.config.DocumentVerificationScheduler;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/documents")
@ConditionalOnProperty(name = "peoplecore.documents.enabled", havingValue = "true")
public class DocumentUploadController {
    private final DocumentCommands commands;
    private final DocumentStatusQuery status;
    private final ObjectProvider<DocumentVerificationScheduler> scheduler;

    public DocumentUploadController(DocumentCommands commands, DocumentStatusQuery status,
                                    ObjectProvider<DocumentVerificationScheduler> scheduler) {
        this.commands = commands;
        this.status = status;
        this.scheduler = scheduler;
    }

    @PostMapping("/uploads")
    public ResponseEntity<ApiData<Upload>> create(@Valid @RequestBody UploadRequest request,
                                                   @RequestHeader("Idempotency-Key") String key) {
        requireVerification();
        Upload result = commands.create(CurrentCaller.require(), key, new Request(request.employeeId(), request.purpose(),
            request.fileName(), request.mediaType(), request.byteSize()));
        return ResponseEntity.created(URI.create("/api/v1/documents/" + result.publicId())).body(ApiData.of(result));
    }

    @PostMapping("/{documentId}/upload-url")
    public ResponseEntity<ApiData<DocumentCommands.UploadUrl>> uploadUrl(@PathVariable UUID documentId,
            @RequestHeader("Idempotency-Key") String key) {
        requireVerification();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiData.of(commands.reserve(CurrentCaller.require(), key, documentId)));
    }

    @PostMapping("/{documentId}/finalize")
    public ApiData<Upload> finalizeUpload(@PathVariable UUID documentId, @RequestHeader("Idempotency-Key") String key) {
        requireVerification();
        return ApiData.of(commands.finalizeUpload(CurrentCaller.require(), key, documentId));
    }

    @GetMapping("/{documentId}")
    public ResponseEntity<ApiData<Upload>> status(@PathVariable UUID documentId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiData.of(status.status(documentId, CurrentCaller.require())));
    }

    private void requireVerification() {
        if (scheduler.getIfAvailable() == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Document verification unavailable");
        }
    }

    public record UploadRequest(@NotNull UUID employeeId, @NotNull Purpose purpose,
                                @NotBlank @Size(max = 255) String fileName, @NotBlank String mediaType,
                                @Positive @Max(10485760) long byteSize) { }
}
