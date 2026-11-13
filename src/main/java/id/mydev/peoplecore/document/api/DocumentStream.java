package id.mydev.peoplecore.document.api;

import id.mydev.peoplecore.document.application.service.DocumentTarget;
import id.mydev.peoplecore.document.application.service.DocumentVersionContentService;
import id.mydev.peoplecore.document.application.service.RevocableDocumentTransfer;
import id.mydev.peoplecore.document.domain.model.Purpose;
import java.io.IOException;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

public final class DocumentStream {
    private DocumentStream() { }

    public static ResponseEntity<StreamingResponseBody> attachment(
            ObjectProvider<RevocableDocumentTransfer> transfers, DocumentVersionContentService content,
            DocumentTarget target, Purpose purpose, String fileName, Supplier<Boolean> stillAuthorized) {
        Objects.requireNonNull(transfers, "transfers must not be null");
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(purpose, "purpose must not be null");
        Objects.requireNonNull(stillAuthorized, "stillAuthorized must not be null");
        String safeFileName = requireAttachmentFileName(fileName);
        RevocableDocumentTransfer transfer = transfers.getIfAvailable();
        if (transfer == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Document transfer unavailable");
        }
        StreamingResponseBody body = output -> {
            if (!Boolean.TRUE.equals(stillAuthorized.get())) {
                throw new IOException("Document authorization changed");
            }
            DocumentVersionContentService.Content opened = content.open(target.documentPublicId(),
                target.versionPublicId(), target.employeePublicId(), purpose);
            try (var source = opened.body()) {
                transfer.transfer(source, output, opened.byteSize(),
                    () -> Boolean.TRUE.equals(stillAuthorized.get()));
            }
        };
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(target.mediaType()))
            .cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + safeFileName + "\"")
            .header("X-Content-Type-Options", "nosniff")
            .body(body);
    }

    private static String requireAttachmentFileName(String fileName) {
        Objects.requireNonNull(fileName, "fileName must not be null");
        if (fileName.isBlank() || fileName.chars().anyMatch(character ->
            character < 0x20 || character == '"' || character == '\\' || character == 0x7f)) {
            throw new IllegalArgumentException("Attachment file name contains unsafe characters");
        }
        return fileName;
    }
}
