package id.mydev.peoplecore.document.application.service;

import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.document.application.service.VerifiedDocumentAccess.Purpose;
import id.mydev.peoplecore.document.application.service.VerifiedDocumentAccess.Request;
import id.mydev.peoplecore.support.PostgresFixture;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("postgres")
class VerifiedDocumentAccessPostgresTest {
    @Test
    void onlyLiveVerifiedVersionsForTheOwnerAndPurposeCanBeLocked() {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            fixture.grantRuntime();
            JdbcTemplate jdbc = fixture.runtime();
            UUID owner = UUID.randomUUID();
            UUID stranger = UUID.randomUUID();
            long employee = employee(jdbc, owner);
            employee(jdbc, stranger);
            UUID first = verifiedDocument(jdbc, employee);
            UUID second = verifiedDocument(jdbc, employee);
            var service = new VerifiedDocumentAccess(jdbc);
            var transactions = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));

            var versions = transactions.execute(status -> service.requireAllForUpdate(List.of(
                new Request(second, owner, Purpose.CLAIM_EVIDENCE),
                new Request(first, owner, Purpose.CLAIM_EVIDENCE))));
            assertEquals(List.of(first, second), versions.stream().map(VerifiedDocumentAccess.VerifiedVersion::documentPublicId).toList());
            assertThrows(ResourceNotFoundException.class, () -> transactions.execute(status ->
                service.requireForUpdate(first, stranger, Purpose.CLAIM_EVIDENCE)));
            assertThrows(ResourceNotFoundException.class, () -> transactions.execute(status ->
                service.requireForUpdate(first, owner, Purpose.PAYSLIP)));
            jdbc.update("UPDATE private_document SET deletion_state='CLAIMED' WHERE public_id=?", first);
            assertThrows(ResourceNotFoundException.class, () -> transactions.execute(status ->
                service.requireForUpdate(first, owner, Purpose.CLAIM_EVIDENCE)));
        }
    }

    private static long employee(JdbcTemplate jdbc, UUID publicId) {
        return jdbc.queryForObject("INSERT INTO employees(public_id,employee_number,created_at) VALUES (?,?,now()) RETURNING id",
            Long.class, publicId, "VERIFY-" + UUID.randomUUID());
    }

    private static UUID verifiedDocument(JdbcTemplate jdbc, long employee) {
        UUID publicId = UUID.randomUUID();
        long document = jdbc.queryForObject("INSERT INTO private_document(public_id,employee_id,purpose,status,file_name,"
                + "declared_media_type,declared_size,current_generation,created_at,updated_at) "
                + "VALUES (?,?,'CLAIM_EVIDENCE','VERIFYING','receipt.pdf','application/pdf',1,1,now(),now()) RETURNING id",
            Long.class, publicId, employee);
        UUID uploadPublicId = UUID.randomUUID();
        long upload = jdbc.queryForObject("INSERT INTO document_upload_attempt(public_id,document_id,generation,staging_key,byte_size,status,created_at) "
                + "VALUES (?,?,1,?,1,'RESERVED',now()) RETURNING id", Long.class, uploadPublicId, document, "staging/" + uploadPublicId);
        jdbc.update("UPDATE document_upload_attempt SET status='COMPLETED',object_etag='etag',completed_at=now() WHERE id=?", upload);
        jdbc.update("UPDATE private_document SET current_upload_id=?,current_upload_generation=1 WHERE id=?", upload, document);
        UUID attemptPublicId = UUID.randomUUID();
        long attempt = jdbc.queryForObject("INSERT INTO document_verification_attempt(public_id,document_id,upload_attempt_id,generation,"
                + "candidate_key,status,created_at) VALUES (?,?,?,1,?,'QUEUED',now()) RETURNING id", Long.class,
            attemptPublicId, document, upload, "candidates/" + attemptPublicId);
        UUID token = UUID.randomUUID();
        jdbc.update("UPDATE document_verification_attempt SET status='CLAIMED',lease_token=?,lease_expires_at=now()+interval '5 minutes' WHERE id=?", token, attempt);
        jdbc.update("UPDATE document_verification_attempt SET copy_started_at=clock_timestamp() WHERE id=?", attempt);
        jdbc.update("UPDATE document_verification_attempt SET copy_completed_at=clock_timestamp() WHERE id=?", attempt);
        jdbc.update("UPDATE document_verification_attempt SET status='CLEAN',completed_at=now() WHERE id=?", attempt);
        long version = jdbc.queryForObject("INSERT INTO document_version(public_id,document_id,attempt_id,lease_token,media_type,byte_size,sha256,scanner_version,verified_at) "
                + "VALUES (gen_random_uuid(),?,?,?,'application/pdf',1,repeat('a',64),'test',now()) RETURNING id",
            Long.class, document, attempt, token);
        jdbc.update("UPDATE private_document SET status='VERIFIED',current_version_id=? WHERE id=?", version, document);
        return publicId;
    }
}
