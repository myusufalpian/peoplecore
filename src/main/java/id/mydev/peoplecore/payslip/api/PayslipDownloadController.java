package id.mydev.peoplecore.payslip.api;

import id.mydev.peoplecore.document.application.service.DocumentVersionContentService;
import id.mydev.peoplecore.document.application.service.RevocableDocumentTransfer;
import id.mydev.peoplecore.document.application.service.VerifiedDocumentAccess.Purpose;
import id.mydev.peoplecore.payslip.application.PayslipModels.PublishedVersion;
import id.mydev.peoplecore.payslip.application.PayslipService;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@ConditionalOnProperty(name = {"peoplecore.payslip.enabled", "peoplecore.payslip.download.enabled"}, havingValue = "true")
@RequestMapping("/api/v1/payslips")
public class PayslipDownloadController {
    private final PayslipService payslips;
    private final DocumentVersionContentService content;
    private final ObjectProvider<RevocableDocumentTransfer> transfers;

    public PayslipDownloadController(PayslipService payslips, DocumentVersionContentService content,
                                     ObjectProvider<RevocableDocumentTransfer> transfers) {
        this.payslips = payslips;
        this.content = content;
        this.transfers = transfers;
    }

    @GetMapping("/{publicationId}/content")
    public ResponseEntity<StreamingResponseBody> content(Authentication caller, @PathVariable UUID publicationId) {
        PublishedVersion version = payslips.requirePublishedVersion(publicationId, caller);
        RevocableDocumentTransfer transfer = transfers.getIfAvailable();
        if (transfer == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Document transfer unavailable");
        }
        StreamingResponseBody body = output -> {
            if (!version.equals(payslips.requirePublishedVersion(publicationId, caller))) {
                throw new java.io.IOException("Payslip authorization changed");
            }
            var opened = content.open(version.documentPublicId(), version.versionPublicId(), version.employeePublicId(), Purpose.PAYSLIP);
            try (var source = opened.body()) {
                transfer.transfer(source, output, opened.byteSize(),
                    () -> version.equals(payslips.requirePublishedVersion(publicationId, caller)));
            }
        };
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"payslip.pdf\"")
            .header("X-Content-Type-Options", "nosniff")
            .body(body);
    }
}
