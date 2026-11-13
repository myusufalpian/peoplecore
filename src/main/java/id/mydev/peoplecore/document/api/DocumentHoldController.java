package id.mydev.peoplecore.document.api;

import id.mydev.peoplecore.common.api.ApiData;
import id.mydev.peoplecore.document.application.command.DocumentHoldCommands;
import id.mydev.peoplecore.document.application.service.DocumentHoldService;
import id.mydev.peoplecore.document.application.service.DocumentHoldService.Hold;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/documents")
@ConditionalOnProperty(name = "peoplecore.documents.enabled", havingValue = "true")
public class DocumentHoldController {
    private final DocumentHoldCommands commands;
    private final DocumentHoldService holds;

    public DocumentHoldController(DocumentHoldCommands commands, DocumentHoldService holds) {
        this.commands = commands;
        this.holds = holds;
    }

    @GetMapping("/{documentId}/holds")
    public ResponseEntity<ApiData<List<Hold>>> active(Authentication caller, @PathVariable UUID documentId) {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(ApiData.of(holds.active(documentId, caller)));
    }

    @GetMapping("/{documentId}/holds/{holdPublicId}")
    public ResponseEntity<ApiData<Hold>> hold(Authentication caller, @PathVariable UUID documentId,
                                              @PathVariable UUID holdPublicId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiData.of(holds.hold(documentId, holdPublicId, caller)));
    }

    @PostMapping("/{documentId}/holds")
    public ResponseEntity<ApiData<Hold>> place(Authentication caller, @PathVariable UUID documentId,
                                               @RequestHeader("Idempotency-Key") String key,
                                               @Valid @RequestBody HoldRequest request) {
        Hold hold = commands.place(caller, key, documentId, request.reason());
        return ResponseEntity.created(URI.create("/api/v1/documents/" + documentId + "/holds/" + hold.publicId()))
            .cacheControl(CacheControl.noStore())
            .body(ApiData.of(hold));
    }

    @PostMapping("/{documentId}/holds/{holdPublicId}/release")
    public ResponseEntity<ApiData<Hold>> release(Authentication caller, @PathVariable UUID documentId,
                                                 @PathVariable UUID holdPublicId,
                                                 @RequestHeader("Idempotency-Key") String key,
                                                 @Valid @RequestBody HoldRequest request) {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(ApiData.of(commands.release(caller, key, documentId, holdPublicId, request.reason())));
    }

    public record HoldRequest(@NotBlank @Size(max = DocumentHoldService.MAX_REASON_LENGTH) String reason) { }
}
