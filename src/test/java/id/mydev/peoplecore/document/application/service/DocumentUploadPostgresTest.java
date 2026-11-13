package id.mydev.peoplecore.document.application.service;

import id.mydev.peoplecore.common.command.CommandConflictException;
import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.document.application.service.DocumentUploadService.PendingUpload;
import id.mydev.peoplecore.document.application.service.DocumentUploadService.Request;
import id.mydev.peoplecore.document.domain.model.Purpose;
import id.mydev.peoplecore.document.infrastructure.persistence.JdbcDocumentUploadRepository;
import id.mydev.peoplecore.organization.domain.model.Employee;
import id.mydev.peoplecore.support.PostgresFixture;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("postgres")
class DocumentUploadPostgresTest {
    @Test
    void revokedAuthorityAfterGuardLockRollsBackDocumentAndGuardCreation() {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            fixture.grantRuntime();
            UUID employeePublicId = UUID.randomUUID();
            Long employeeId = fixture.jdbc.queryForObject("INSERT INTO employees(public_id,employee_number,created_at) "
                + "VALUES (?, ?, now()) RETURNING id", Long.class, employeePublicId, "DOC-" + UUID.randomUUID());
            Employee employee = mock(Employee.class);
            when(employee.getId()).thenReturn(employeeId);
            Authentication caller = mock(Authentication.class);
            DocumentAccessPolicy access = mock(DocumentAccessPolicy.class);
            when(access.requireUpload(caller, employeePublicId, Purpose.CLAIM_EVIDENCE))
                .thenReturn(employee).thenThrow(new ResourceNotFoundException());
            JdbcTemplate jdbc = fixture.runtime();
            var transactions = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
            var service = new DocumentUploadService(new JdbcDocumentUploadRepository(jdbc), mock(EntityManager.class),
                access, Clock.systemUTC());
            UUID document = UUID.randomUUID();

            assertThrows(ResourceNotFoundException.class, () -> transactions.execute(status -> service.create(document,
                new Request(employeePublicId, Purpose.CLAIM_EVIDENCE, "receipt.pdf", "application/pdf", 12), caller)));
            assertEquals(0, jdbc.queryForObject("SELECT count(id) FROM private_document WHERE public_id=?", Integer.class, document));
            assertEquals(0, jdbc.queryForObject("SELECT count(id) FROM document_account WHERE employee_id=?", Integer.class, employeeId));
        }
    }

    @Test
    void changedMetadataCannotQueueAnUploadOrAlterItsPendingState() {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            fixture.grantRuntime();
            UUID employeePublicId = UUID.randomUUID();
            Long employeeId = fixture.jdbc.queryForObject("INSERT INTO employees(public_id,employee_number,created_at) "
                + "VALUES (?, ?, now()) RETURNING id", Long.class, employeePublicId, "DOC-" + UUID.randomUUID());
            Employee employee = mock(Employee.class);
            when(employee.getId()).thenReturn(employeeId);
            Authentication caller = mock(Authentication.class);
            DocumentAccessPolicy access = mock(DocumentAccessPolicy.class);
            when(access.requireUpload(caller, employeePublicId, Purpose.CLAIM_EVIDENCE)).thenReturn(employee);
            JdbcTemplate jdbc = fixture.runtime();
            var transactions = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
            var service = new DocumentUploadService(new JdbcDocumentUploadRepository(jdbc), mock(EntityManager.class),
                access, Clock.systemUTC());
            UUID document = UUID.randomUUID();
            transactions.executeWithoutResult(status -> service.create(document,
                new Request(employeePublicId, Purpose.CLAIM_EVIDENCE, "receipt.pdf", "application/pdf", 12), caller));
            var metadata = transactions.execute(status -> service.status(document, caller));
            assertEquals(document, metadata.publicId());
            assertEquals(Purpose.CLAIM_EVIDENCE, metadata.purpose());
            assertEquals("PENDING_UPLOAD", metadata.status());
            assertNotNull(metadata.createdAt());
            assertNull(metadata.mediaType());
            assertNull(metadata.byteSize());
            assertNull(metadata.verifiedAt());
            var pending = transactions.execute(status -> service.reserve(document, UUID.randomUUID(), caller));
            var changed = new PendingUpload(document, employeePublicId, Purpose.CLAIM_EVIDENCE,
                pending.uploadPublicId(), "image/png", 12);

            assertThrows(CommandConflictException.class, () -> transactions.execute(status ->
                service.queueVerification(changed, UUID.randomUUID(), "etag", caller)));
            assertThrows(IllegalArgumentException.class, () -> transactions.execute(status ->
                service.queueVerification(pending, UUID.randomUUID(), " ", caller)));
            assertEquals("RESERVED", jdbc.queryForObject("SELECT status FROM document_upload_attempt WHERE public_id=?",
                String.class, pending.uploadPublicId()));
            assertEquals(0, jdbc.queryForObject("SELECT count(id) FROM document_verification_attempt WHERE document_id="
                + "(SELECT id FROM private_document WHERE public_id=?)", Integer.class, document));
        }
    }

    @Test
    void replacingUploadAbandonsOldAttemptAndOnlyCurrentUploadCanBeQueued() {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            fixture.grantRuntime();
            UUID employeePublicId = UUID.randomUUID();
            Long employeeId = fixture.jdbc.queryForObject("INSERT INTO employees(public_id,employee_number,created_at) "
                + "VALUES (?, ?, now()) RETURNING id", Long.class, employeePublicId, "DOC-" + UUID.randomUUID());
            Employee employee = mock(Employee.class);
            when(employee.getId()).thenReturn(employeeId);
            Authentication caller = mock(Authentication.class);
            DocumentAccessPolicy access = mock(DocumentAccessPolicy.class);
            when(access.requireUpload(caller, employeePublicId, Purpose.CLAIM_EVIDENCE)).thenReturn(employee);
            JdbcTemplate jdbc = fixture.runtime();
            var transactions = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
            var service = new DocumentUploadService(new JdbcDocumentUploadRepository(jdbc), mock(EntityManager.class),
                access, Clock.systemUTC());
            UUID document = UUID.randomUUID();
            UUID oldUpload = UUID.randomUUID();
            UUID newUpload = UUID.randomUUID();
            UUID verification = UUID.randomUUID();

            transactions.executeWithoutResult(status -> service.create(document,
                new Request(employeePublicId, Purpose.CLAIM_EVIDENCE, "receipt.pdf", "application/pdf", 12), caller));
            var previous = transactions.execute(status -> service.reserve(document, oldUpload, caller));
            var current = transactions.execute(status -> service.reserve(document, newUpload, caller));
            assertThrows(CommandConflictException.class, () -> transactions.execute(status ->
                service.queueVerification(previous, UUID.randomUUID(), "etag", caller)));
            var result = transactions.execute(status -> service.queueVerification(current, verification, "etag", caller));

            assertEquals("VERIFYING", result.status());
            assertEquals("ABANDONED", jdbc.queryForObject("SELECT status FROM document_upload_attempt WHERE public_id=?", String.class, oldUpload));
            assertEquals("COMPLETED", jdbc.queryForObject("SELECT status FROM document_upload_attempt WHERE public_id=?", String.class, newUpload));
            assertEquals("QUEUED", jdbc.queryForObject("SELECT status FROM document_verification_attempt WHERE public_id=?", String.class, verification));
        }
    }
}
