package id.mydev.peoplecore.document.api;

import id.mydev.peoplecore.document.application.service.DocumentContentQuery;
import id.mydev.peoplecore.document.application.service.DocumentTarget;
import id.mydev.peoplecore.document.application.service.DocumentVersionContentService;
import id.mydev.peoplecore.document.application.service.RevocableDocumentTransfer;
import id.mydev.peoplecore.document.domain.model.Purpose;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/v1/documents")
@ConditionalOnProperty(name = "peoplecore.documents.enabled", havingValue = "true")
public class DocumentContentController {
    private final DocumentContentQuery documents;
    private final DocumentVersionContentService content;
    private final ObjectProvider<RevocableDocumentTransfer> transfers;

    public DocumentContentController(DocumentContentQuery documents, DocumentVersionContentService content,
                                     ObjectProvider<RevocableDocumentTransfer> transfers) {
        this.documents = documents;
        this.content = content;
        this.transfers = transfers;
    }

    @GetMapping("/{documentId}/content")
    public ResponseEntity<StreamingResponseBody> content(Authentication caller, @PathVariable UUID documentId) {
        DocumentTarget target = documents.requireOwnedClaimEvidence(documentId, caller);
        return DocumentStream.attachment(transfers, content, target, Purpose.CLAIM_EVIDENCE, "evidence",
            () -> target.equals(documents.requireOwnedClaimEvidence(documentId, caller)));
    }
}
