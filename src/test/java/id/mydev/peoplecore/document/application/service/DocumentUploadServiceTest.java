package id.mydev.peoplecore.document.application.service;

import id.mydev.peoplecore.document.application.service.DocumentUploadService.Request;
import id.mydev.peoplecore.document.application.service.VerifiedDocumentAccess.Purpose;
import id.mydev.peoplecore.organization.domain.model.Employee;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentUploadServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final EntityManager entities = mock(EntityManager.class);
    private final DocumentAccessPolicy access = mock(DocumentAccessPolicy.class);
    private final Authentication caller = mock(Authentication.class);
    private final DocumentUploadService service = new DocumentUploadService(jdbc, entities, access,
        Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));

    @Test
    void createsPendingMetadataForAuthorizedEmployee() {
        UUID employeeId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        Employee employee = mock(Employee.class);
        when(employee.getId()).thenReturn(1L);
        when(access.requireUpload(caller, employeeId, Purpose.CLAIM_EVIDENCE)).thenReturn(employee);
        Request request = new Request(employeeId, Purpose.CLAIM_EVIDENCE, "receipt.pdf", "application/pdf", 100);

        assertEquals(new DocumentUploadService.Upload(documentId, "PENDING_UPLOAD"),
            service.create(documentId, request, caller));
    }

    @Test
    void rejectsInvalidNamesSizesAndPayslipFormats() {
        UUID employeeId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> service.create(documentId,
            new Request(employeeId, Purpose.CLAIM_EVIDENCE, "a\nb.pdf", "application/pdf", 1), caller));
        assertThrows(IllegalArgumentException.class, () -> service.create(documentId,
            new Request(employeeId, Purpose.CLAIM_EVIDENCE, "a.pdf", "application/pdf", 0), caller));
        assertThrows(IllegalArgumentException.class, () -> service.create(documentId,
            new Request(employeeId, Purpose.CLAIM_EVIDENCE, "a.pdf", "application/pdf", DocumentContentInspector.MAX_BYTES + 1), caller));
        assertThrows(IllegalArgumentException.class, () -> service.create(documentId,
            new Request(employeeId, Purpose.PAYSLIP, "a.png", "image/png", 1), caller));
    }

    @Test
    void acceptsSupportedEvidenceFormatsAndBoundarySizes() {
        UUID employeeId = UUID.randomUUID();
        service.verifyCreate(new Request(employeeId, Purpose.CLAIM_EVIDENCE, "receipt.jpg", "image/jpeg", 1), caller);
        service.verifyCreate(new Request(employeeId, Purpose.CLAIM_EVIDENCE, "receipt.png", "image/png",
            DocumentContentInspector.MAX_BYTES), caller);
        service.verifyCreate(new Request(employeeId, Purpose.PAYSLIP, "payslip.pdf", "application/pdf", 1), caller);
    }

    @Test
    void rejectsBlankOversizedAndUnsupportedMetadata() {
        UUID employeeId = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> service.verifyCreate(
            new Request(employeeId, Purpose.CLAIM_EVIDENCE, " ", "application/pdf", 1), caller));
        assertThrows(IllegalArgumentException.class, () -> service.verifyCreate(
            new Request(employeeId, Purpose.CLAIM_EVIDENCE, "a".repeat(256), "application/pdf", 1), caller));
        assertThrows(IllegalArgumentException.class, () -> service.verifyCreate(
            new Request(employeeId, Purpose.CLAIM_EVIDENCE, "receipt.txt", "text/plain", 1), caller));
        assertThrows(IllegalArgumentException.class, () -> service.verifyCreate(
            new Request(employeeId, Purpose.PAYSLIP, "payslip.jpg", "image/jpeg", 1), caller));
    }
}
