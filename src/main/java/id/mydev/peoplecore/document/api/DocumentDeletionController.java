package id.mydev.peoplecore.document.api;

import id.mydev.peoplecore.common.api.ApiData;
import id.mydev.peoplecore.document.application.command.DocumentDeletionCommands;
import id.mydev.peoplecore.document.application.service.DocumentDeletionService;
import id.mydev.peoplecore.document.application.service.DocumentDeletionService.Deletion;
import java.net.URI;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/documents")
@ConditionalOnProperty(name = {"peoplecore.documents.enabled", "peoplecore.documents.deletion.enabled"},
    havingValue = "true")
public class DocumentDeletionController {
    private final DocumentDeletionCommands commands;
    private final DocumentDeletionService deletions;

    public DocumentDeletionController(DocumentDeletionCommands commands, DocumentDeletionService deletions) {
        this.commands = commands;
        this.deletions = deletions;
    }

    @GetMapping("/{documentId}/deletion")
    public ResponseEntity<ApiData<Deletion>> status(Authentication caller, @PathVariable UUID documentId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiData.of(deletions.status(documentId, caller)));
    }

    @PostMapping("/{documentId}/deletion")
    public ResponseEntity<ApiData<Deletion>> claim(Authentication caller, @PathVariable UUID documentId,
                                                   @RequestHeader("Idempotency-Key") String key) {
        Deletion deletion = commands.claim(caller, key, documentId);
        return ResponseEntity.created(URI.create("/api/v1/documents/" + documentId + "/deletion"))
            .cacheControl(CacheControl.noStore())
            .body(ApiData.of(deletion));
    }

    @PostMapping("/{documentId}/deletion/restore")
    public ResponseEntity<ApiData<Deletion>> restore(Authentication caller, @PathVariable UUID documentId,
                                                     @RequestHeader("Idempotency-Key") String key) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiData.of(commands.restore(caller, key, documentId)));
    }
}
