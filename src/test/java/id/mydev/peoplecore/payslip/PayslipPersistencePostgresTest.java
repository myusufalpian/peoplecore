package id.mydev.peoplecore.payslip;

import id.mydev.peoplecore.support.PostgresFixture;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("postgres")
class PayslipPersistencePostgresTest {
    @Test
    void reviewSealsMappingsAndOnlyCurrentReviewedVersionCanPublish() {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            fixture.grantRuntime();
            JdbcTemplate jdbc = fixture.runtime();
            long actor = actor(jdbc);
            long employee = employee(jdbc);
            var document = verifiedDocument(jdbc, employee);
            long batch = jdbc.queryForObject("insert into payslip_batch(public_id,created_by,created_at,updated_at) "
                + "values(gen_random_uuid(),?,now(),now()) returning id", Long.class, actor);
            long revision = jdbc.queryForObject("insert into payslip_batch_revision(public_id,batch_id,revision_number,created_at) "
                + "values(gen_random_uuid(),?,1,now()) returning id", Long.class, batch);
            jdbc.update("update payslip_batch set current_revision_id=? where id=?", revision, batch);
            long item = item(jdbc, revision, employee, document);
            jdbc.update("update payslip_batch_revision set status='REVIEWED',reviewed_by=?,reviewed_at=now() where id=?", actor, revision);

            assertThrows(DataAccessException.class, () -> jdbc.update("delete from payslip_batch_item where id=?", item));
            assertThrows(DataAccessException.class, () -> item(jdbc, revision, employee, document));
            assertThrows(DataAccessException.class, () -> publication(jdbc, actor, revision, item, employee));
            jdbc.update("update payslip_batch set status='REVIEWED' where id=?", batch);
            assertThrows(DataAccessException.class, () -> jdbc.update("update payslip_batch set status='PUBLISHED' where id=?", batch));
            var transaction = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
            assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
                long interrupted = publication(jdbc, actor, revision, item, employee);
                jdbc.update("insert into payslip_current(employee_id,period_start,publication_id) values(?,'2026-11-01',?)", employee, interrupted);
                throw new IllegalStateException("interrupted publication");
            }));
            assertEquals(0, jdbc.queryForObject("select count(id) from payslip_publication where employee_id=?", Integer.class, employee));
            assertEquals(0, jdbc.queryForObject("select count(publication_id) from payslip_current where employee_id=?", Integer.class, employee));
            long published = transaction.execute(status -> {
                long publication = publication(jdbc, actor, revision, item, employee);
                jdbc.update("insert into payslip_current(employee_id,period_start,publication_id) values(?,'2026-11-01',?)", employee, publication);
                jdbc.update("update payslip_batch set status='PUBLISHED' where id=?", batch);
                return publication;
            });
            assertThrows(DataAccessException.class, () -> jdbc.update("delete from payslip_publication where id=?", published));
            assertEquals(1, jdbc.queryForObject("select count(id) from payslip_publication where employee_id=?", Integer.class, employee));
        }
    }

    @Test
    void foreignOrUnverifiedDocumentsCannotBeMapped() {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            fixture.grantRuntime();
            JdbcTemplate jdbc = fixture.runtime();
            long actor = actor(jdbc);
            long owner = employee(jdbc);
            long stranger = employee(jdbc);
            var document = verifiedDocument(jdbc, owner);
            long batch = jdbc.queryForObject("insert into payslip_batch(public_id,created_by,created_at,updated_at) "
                + "values(gen_random_uuid(),?,now(),now()) returning id", Long.class, actor);
            long revision = jdbc.queryForObject("insert into payslip_batch_revision(public_id,batch_id,revision_number,created_at) "
                + "values(gen_random_uuid(),?,1,now()) returning id", Long.class, batch);

            assertThrows(DataAccessException.class, () -> item(jdbc, revision, stranger, document));
            jdbc.update("update private_document set deletion_state='CLAIMED',deletion_requested_at=now(),deletion_restore_deadline=now()+interval '30 days' where id=?", document.id());
            assertThrows(DataAccessException.class, () -> item(jdbc, revision, owner, document));
        }
    }

    @Test
    void reviewedOrPublishedPayslipDocumentCannotBeClaimedForDeletion() {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            fixture.grantRuntime();
            JdbcTemplate jdbc = fixture.runtime();
            long actor = actor(jdbc);
            long employee = employee(jdbc);
            var document = verifiedDocument(jdbc, employee);
            long batch = jdbc.queryForObject("insert into payslip_batch(public_id,created_by,created_at,updated_at) "
                + "values(gen_random_uuid(),?,now(),now()) returning id", Long.class, actor);
            long revision = jdbc.queryForObject("insert into payslip_batch_revision(public_id,batch_id,revision_number,created_at) "
                + "values(gen_random_uuid(),?,1,now()) returning id", Long.class, batch);
            long item = item(jdbc, revision, employee, document);
            jdbc.update("update payslip_batch set current_revision_id=? where id=?", revision, batch);
            jdbc.update("update payslip_batch_revision set status='REVIEWED',reviewed_by=?,reviewed_at=now() where id=?",
                actor, revision);

            assertThrows(DataAccessException.class, () -> claim(jdbc, document.id()));

            jdbc.update("update payslip_batch set status='REVIEWED' where id=?", batch);
            publication(jdbc, actor, revision, item, employee);
            assertThrows(DataAccessException.class, () -> claim(jdbc, document.id()));
        }
    }

    private static void claim(JdbcTemplate jdbc, long documentId) {
        jdbc.update("update private_document set deletion_state='CLAIMED',deletion_requested_at=now(),"
            + "deletion_restore_deadline=now()+interval '30 days' where id=?", documentId);
    }

    private static long actor(JdbcTemplate jdbc) {
        return jdbc.queryForObject("insert into user_accounts(public_id,oidc_issuer,oidc_subject,created_at) "
            + "values(gen_random_uuid(),'https://identity.example',?,now()) returning id", Long.class, UUID.randomUUID().toString());
    }

    private static long employee(JdbcTemplate jdbc) {
        return jdbc.queryForObject("insert into employees(public_id,employee_number,created_at) values(gen_random_uuid(),?,now()) returning id",
            Long.class, "PAY-" + UUID.randomUUID());
    }

    static Document verifiedDocument(JdbcTemplate jdbc, long employee) {
        long document = jdbc.queryForObject("insert into private_document(public_id,employee_id,purpose,status,file_name,"
            + "declared_media_type,declared_size,current_generation,created_at,updated_at) "
            + "values(gen_random_uuid(),?,'PAYSLIP','VERIFYING','payslip.pdf','application/pdf',1,1,now(),now()) returning id", Long.class, employee);
        UUID uploadId = UUID.randomUUID();
        long upload = jdbc.queryForObject("insert into document_upload_attempt(public_id,document_id,generation,staging_key,byte_size,status,created_at) "
            + "values(?,?,1,?,1,'RESERVED',now()) returning id", Long.class, uploadId, document, "staging/" + uploadId);
        jdbc.update("update document_upload_attempt set status='COMPLETED',object_etag='etag',completed_at=now() where id=?", upload);
        jdbc.update("update private_document set current_upload_id=?,current_upload_generation=1 where id=?", upload, document);
        UUID attemptId = UUID.randomUUID();
        long attempt = jdbc.queryForObject("insert into document_verification_attempt(public_id,document_id,upload_attempt_id,generation,candidate_key,status,created_at) "
            + "values(?,?,?,1,?,'QUEUED',now()) returning id", Long.class, attemptId, document, upload, "candidates/" + attemptId);
        UUID token = UUID.randomUUID();
        jdbc.update("update document_verification_attempt set status='CLAIMED',lease_token=?,lease_expires_at=now()+interval '5 minutes' where id=?", token, attempt);
        jdbc.update("update document_verification_attempt set copy_started_at=clock_timestamp() where id=?", attempt);
        jdbc.update("update document_verification_attempt set copy_completed_at=clock_timestamp() where id=?", attempt);
        jdbc.update("update document_verification_attempt set status='CLEAN',completed_at=now() where id=?", attempt);
        long version = jdbc.queryForObject("insert into document_version(public_id,document_id,attempt_id,lease_token,media_type,byte_size,sha256,scanner_version,verified_at) "
            + "values(gen_random_uuid(),?,?,?,'application/pdf',1,repeat('a',64),'test',now()) returning id", Long.class, document, attempt, token);
        jdbc.update("update private_document set status='VERIFIED',current_version_id=? where id=?", version, document);
        return new Document(document, version);
    }

    private static long item(JdbcTemplate jdbc, long revision, long employee, Document document) {
        return jdbc.queryForObject("insert into payslip_batch_item(public_id,revision_id,employee_id,period_start,document_id,document_version_id) "
            + "values(gen_random_uuid(),?,?,'2026-11-01',?,?) returning id", Long.class, revision, employee, document.id(), document.version());
    }

    private static long publication(JdbcTemplate jdbc, long actor, long revision, long item, long employee) {
        return jdbc.queryForObject("insert into payslip_publication(public_id,revision_id,item_id,employee_id,period_start,published_by,published_at) "
            + "values(gen_random_uuid(),?,?,?,'2026-11-01',?,now()) returning id", Long.class, revision, item, employee, actor);
    }

    record Document(long id, long version) { }
}
