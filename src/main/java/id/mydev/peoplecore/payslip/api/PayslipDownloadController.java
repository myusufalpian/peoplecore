package id.mydev.peoplecore.payslip.api;

import id.mydev.peoplecore.document.api.DocumentStream;
import id.mydev.peoplecore.document.application.service.DocumentTarget;
import id.mydev.peoplecore.document.application.service.DocumentVersionContentService;
import id.mydev.peoplecore.document.application.service.RevocableDocumentTransfer;
import id.mydev.peoplecore.document.domain.model.Purpose;
import id.mydev.peoplecore.payslip.application.PayslipModels.PublishedVersion;
import id.mydev.peoplecore.payslip.application.PayslipService;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
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
        DocumentTarget target = new DocumentTarget(version.documentPublicId(), version.versionPublicId(),
            version.employeePublicId(), MediaType.APPLICATION_PDF_VALUE);
        return DocumentStream.attachment(transfers, content, target, Purpose.PAYSLIP, "payslip.pdf",
            () -> version.equals(payslips.requirePublishedVersion(publicationId, caller)));
    }
}
