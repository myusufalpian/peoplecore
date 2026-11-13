package id.mydev.peoplecore.infrastructure.migration;

import id.mydev.peoplecore.document.infrastructure.persistence.JdbcDocumentVerificationRepository;

import id.mydev.peoplecore.support.PostgresFixture;
import id.mydev.peoplecore.document.infrastructure.storage.DocumentCopyClaim;
import id.mydev.peoplecore.document.infrastructure.storage.PrivateDocumentStorage;
import id.mydev.peoplecore.document.infrastructure.scanner.ClamAvScanner;
import id.mydev.peoplecore.document.application.service.DocumentContentInspector;
import id.mydev.peoplecore.document.application.service.DocumentVerificationWorker;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("postgres")
class DocumentVerificationPostgresTest {
    @Test
    void blockedSourceCloseRetainsBoundedCapacityWithoutBlockingTheWorker() throws Exception {
        CountDownLatch closesStarted = new CountDownLatch(8);
        CountDownLatch release = new CountDownLatch(1);
        try (var fixture = new PostgresFixture(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            fixture.flyway().migrate();
            fixture.grantRuntime();
            JdbcTemplate runtime = fixture.runtime();
            PrivateDocumentStorage storage = mock(PrivateDocumentStorage.class);
            ClamAvScanner scanner = mock(ClamAvScanner.class);
            when(storage.copyToCandidate(any(), any(), any(), anyLong())).thenReturn("candidate");
            when(storage.openCandidate(any())).thenAnswer(invocation -> new InputStream() {
                @Override
                public int read() { return -1; }

                @Override
                public void close() throws IOException {
                    closesStarted.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IOException(exception);
                    }
                }
            });
            when(scanner.scan(any(), anyString(), anyLong())).thenThrow(new IOException("Scanner timed out"));
            var worker = new DocumentVerificationWorker(new JdbcDocumentVerificationRepository(runtime), new DataSourceTransactionManager(runtime.getDataSource()),
                storage, scanner, (source, type, size, sha) -> { }, Duration.ofMinutes(5));
            try {
                for (int i = 0; i < 8; i++) {
                    long document = createDocument(fixture.jdbc, "CLAIM_EVIDENCE", "application/pdf");
                    createAttempt(runtime, document, 1);
                    var result = executor.submit(() -> {
                        try {
                            worker.verifyNext();
                            return false;
                        } catch (IOException expected) {
                            return true;
                        }
                    });
                    assertTrue(result.get(5, TimeUnit.SECONDS));
                }
                assertTrue(closesStarted.await(5, TimeUnit.SECONDS));
                assertThrows(IOException.class, worker::verifyNext);
            } finally {
                release.countDown();
            }
        }
    }
    @Test
    void expiredCopyRecoveryCreatesOneNewAttemptAndFencesTheOldWorker() throws Exception {
        try (var fixture = new PostgresFixture(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            fixture.flyway().migrate();
            fixture.grantRuntime();
            JdbcTemplate runtime = fixture.runtime();
            long document = createDocument(fixture.jdbc, "CLAIM_EVIDENCE", "application/pdf");
            long attempt = createAttempt(runtime, document, 1);
            UUID token = UUID.randomUUID();
            runtime.update("update document_verification_attempt set status='CLAIMED',lease_token=?,"
                + "lease_expires_at=clock_timestamp()+interval '1 second' where id=?", token, attempt);
            runtime.update("update document_verification_attempt set copy_started_at=clock_timestamp() where id=?", attempt);
            UUID oldId = runtime.queryForObject("select public_id from document_verification_attempt where id=?", UUID.class, attempt);
            fixture.jdbc.queryForObject("select pg_sleep(1.1)", String.class);
            var worker = worker(runtime);
            CountDownLatch start = new CountDownLatch(1);
            var first = executor.submit(() -> { start.await(); return worker.recoverNext(Duration.ZERO); });
            var second = executor.submit(() -> { start.await(); return worker.recoverNext(Duration.ZERO); });
            start.countDown();

            assertEquals(1, (first.get() ? 1 : 0) + (second.get() ? 1 : 0));
            assertEquals("ABANDONED", runtime.queryForObject("select status from document_verification_attempt where id=?", String.class, attempt));
            assertEquals(1, runtime.queryForObject("select count(id) from document_verification_attempt "
                + "where document_id=? and generation=2 and status='QUEUED' and candidate_key<>?", Integer.class,
                document, "candidates/" + oldId));
            DocumentCopyClaim claim = new DocumentCopyClaim(runtime, new DataSourceTransactionManager(runtime.getDataSource()));
            assertThrows(IllegalStateException.class, () -> claim.complete(oldId, token));
            assertEquals(0, runtime.queryForObject("select count(id) from document_version where document_id=?", Integer.class, document));
        }
    }

    @Test
    void abandonedAttemptIsRequeuedOnlyAfterRetryDelay() {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            fixture.grantRuntime();
            JdbcTemplate runtime = fixture.runtime();
            long document = createDocument(fixture.jdbc, "CLAIM_EVIDENCE", "application/pdf");
            long attempt = createAttempt(runtime, document, 1);
            runtime.update("update document_verification_attempt set status='ABANDONED',completed_at=clock_timestamp() where id=?", attempt);
            var worker = worker(runtime);

            assertFalse(worker.recoverNext(Duration.ofHours(1)));
            assertTrue(worker.recoverNext(Duration.ZERO));
            assertEquals(1, runtime.queryForObject("select count(id) from document_verification_attempt "
                + "where document_id=? and generation=2 and status='QUEUED'", Integer.class, document));
        }
    }

    private static DocumentVerificationWorker worker(JdbcTemplate runtime) {
        return new DocumentVerificationWorker(new JdbcDocumentVerificationRepository(runtime), new DataSourceTransactionManager(runtime.getDataSource()),
            mock(PrivateDocumentStorage.class), mock(ClamAvScanner.class), (source, type, size, sha) -> { }, Duration.ofMinutes(5));
    }

    @Test
    void workerPublishesOnlyAfterSuccessfulCopyAndCleanScan() throws Exception {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            fixture.grantRuntime();
            JdbcTemplate runtime = fixture.runtime();
            long document = createDocument(fixture.jdbc, "CLAIM_EVIDENCE", "application/pdf");
            long attempt = createAttempt(runtime, document, 1);
            PrivateDocumentStorage storage = mock(PrivateDocumentStorage.class);
            ClamAvScanner scanner = mock(ClamAvScanner.class);
            when(storage.copyToCandidate(any(), any(), any(), anyLong())).thenAnswer(invocation -> {
                runtime.update("update document_verification_attempt set copy_started_at=clock_timestamp() where id=?", attempt);
                runtime.update("update document_verification_attempt set copy_completed_at=clock_timestamp() where id=?", attempt);
                return "candidate";
            });
            when(storage.openCandidate(any())).thenAnswer(invocation -> new ByteArrayInputStream(new byte[] {1}));
            when(scanner.scan(any(), anyString(), anyLong())).thenReturn(new ClamAvScanner.Result(
                new DocumentContentInspector.Result("application/pdf", 1, "a".repeat(64)), "engine", "definitions"));
            var worker = new DocumentVerificationWorker(new JdbcDocumentVerificationRepository(runtime), new DataSourceTransactionManager(runtime.getDataSource()),
                storage, scanner, (source, type, size, sha) -> { }, Duration.ofMinutes(5));

            assertTrue(worker.verifyNext());
            assertEquals("VERIFIED", runtime.queryForObject("select status from private_document where id=?", String.class, document));
            assertEquals(1, runtime.queryForObject("select count(id) from document_version where document_id=?", Integer.class, document));
        }
    }

    @Test
    void workerRejectsInvalidFormatEvenWhenScannerReportsClean() throws Exception {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            fixture.grantRuntime();
            JdbcTemplate runtime = fixture.runtime();
            long document = createDocument(fixture.jdbc, "CLAIM_EVIDENCE", "application/pdf");
            long attempt = createAttempt(runtime, document, 1);
            PrivateDocumentStorage storage = mock(PrivateDocumentStorage.class);
            ClamAvScanner scanner = mock(ClamAvScanner.class);
            when(storage.copyToCandidate(any(), any(), any(), anyLong())).thenAnswer(invocation -> {
                runtime.update("update document_verification_attempt set copy_started_at=clock_timestamp() where id=?", attempt);
                runtime.update("update document_verification_attempt set copy_completed_at=clock_timestamp() where id=?", attempt);
                return "candidate";
            });
            when(storage.openCandidate(any())).thenAnswer(invocation -> new ByteArrayInputStream(new byte[] {1}));
            when(scanner.scan(any(), anyString(), anyLong())).thenReturn(new ClamAvScanner.Result(
                new DocumentContentInspector.Result("application/pdf", 1, "a".repeat(64)), "engine", "definitions"));
            var worker = new DocumentVerificationWorker(new JdbcDocumentVerificationRepository(runtime), new DataSourceTransactionManager(runtime.getDataSource()),
                storage, scanner, (source, type, size, sha) -> {
                    throw new DocumentContentInspector.InvalidDocumentContentException("Invalid file format");
                }, Duration.ofMinutes(5));

            assertTrue(worker.verifyNext());
            assertEquals("REJECTED", runtime.queryForObject("select status from private_document where id=?", String.class, document));
            assertEquals(0, runtime.queryForObject("select count(id) from document_version where document_id=?", Integer.class, document));
            verify(storage).deleteCandidate(runtime.queryForObject(
                "select public_id from document_verification_attempt where id=?", UUID.class, attempt));
        }
    }

    @Test
    void copyClaimCommitsOnceAndCannotBeReusedAfterCompletion() {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            fixture.grantRuntime();
            JdbcTemplate runtime = fixture.runtime();
            long document = createDocument(fixture.jdbc, "CLAIM_EVIDENCE", "application/pdf");
            long attempt = createAttempt(runtime, document, 1);
            UUID token = UUID.randomUUID();
            runtime.update("update document_verification_attempt set status='CLAIMED',lease_token=?,"
                + "lease_expires_at=now()+interval '5 minutes' where id=?", token, attempt);
            UUID attemptId = runtime.queryForObject("select public_id from document_verification_attempt where id=?", UUID.class, attempt);
            UUID uploadId = runtime.queryForObject("select public_id from document_upload_attempt where document_id=?", UUID.class, document);
            DocumentCopyClaim claim = new DocumentCopyClaim(runtime, new DataSourceTransactionManager(runtime.getDataSource()));

            claim.start(uploadId, attemptId, token, 1);
            assertThrows(IllegalStateException.class, () -> claim.start(uploadId, attemptId, token, 1));
            claim.complete(attemptId, token);
            runtime.update("update document_verification_attempt set status='CLEAN',completed_at=now() where id=?", attempt);
            assertThrows(IllegalStateException.class, () -> claim.start(uploadId, attemptId, token, 1));
        }
    }

    @Test
    void onlyCurrentCleanLeaseCanCreateAnImmutableVersion() {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            fixture.grantRuntime();
            JdbcTemplate runtime = fixture.runtime();
            long document = createDocument(fixture.jdbc, "CLAIM_EVIDENCE", "application/pdf");
            UUID token = UUID.randomUUID();
            long attempt = createAttempt(runtime, document, 1);

            assertThrows(DataAccessException.class, () -> insertVersion(runtime, document, attempt, token, "application/pdf"));
            claim(runtime, attempt, token, true);
            runtime.update("update document_verification_attempt set status='CLEAN',completed_at=now() where id=?", attempt);
            assertThrows(DataAccessException.class, () -> insertVersion(runtime, document, attempt, UUID.randomUUID(), "application/pdf"));
            assertThrows(DataAccessException.class, () -> insertVersion(runtime, document, attempt, token, "image/png"));
            assertThrows(DataAccessException.class, () -> runtime.update("insert into document_version(public_id,document_id,attempt_id,lease_token,media_type,"
                + "byte_size,sha256,scanner_version,verified_at) values(gen_random_uuid(),?,?,?,'application/pdf',2,repeat('b',64),'test-scanner',now())",
                document, attempt, token));

            long version = insertVersion(runtime, document, attempt, token, "application/pdf");
            runtime.update("update private_document set status='VERIFIED',current_version_id=?,updated_at=now() where id=?", version, document);
            assertEquals(1, runtime.queryForObject("select count(id) from document_version where document_id=?", Integer.class, document));
            assertThrows(DataAccessException.class, () -> runtime.update("update document_version set byte_size=2 where id=?", version));
            assertThrows(DataAccessException.class, () -> runtime.update("delete from document_version where id=?", version));
            assertThrows(DataAccessException.class, () -> runtime.update("update document_verification_attempt set candidate_key='candidates/changed' where id=?", attempt));
        }
    }

    @Test
    void replacedUploadCannotBePublishedByAnOldAttempt() {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            fixture.grantRuntime();
            JdbcTemplate runtime = fixture.runtime();
            long document = createDocument(fixture.jdbc, "CLAIM_EVIDENCE", "application/pdf");
            UUID token = UUID.randomUUID();
            long attempt = createAttempt(runtime, document, 1);
            claim(runtime, attempt, token, true);
            runtime.update("update document_verification_attempt set status='CLEAN',completed_at=now() where id=?", attempt);
            long upload = reserveUpload(runtime, document, 2);
            runtime.update("update document_upload_attempt set status='COMPLETED',object_etag='replacement',completed_at=now() where id=?", upload);
            runtime.update("update private_document set current_upload_id=?,current_upload_generation=2 where id=?", upload, document);

            assertThrows(DataAccessException.class, () -> insertVersion(runtime, document, attempt, token, "application/pdf"));
        }
    }

    @Test
    void versionMustMatchDeclaredDocumentSize() {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            fixture.grantRuntime();
            JdbcTemplate runtime = fixture.runtime();
            long document = createDocument(fixture.jdbc, "CLAIM_EVIDENCE", "application/pdf");
            runtime.update("update private_document set declared_size=2 where id=?", document);
            UUID token = UUID.randomUUID();
            long attempt = createAttempt(runtime, document, 1);
            claim(runtime, attempt, token, true);
            runtime.update("update document_verification_attempt set status='CLEAN',completed_at=now() where id=?", attempt);

            assertThrows(DataAccessException.class, () -> insertVersion(runtime, document, attempt, token, "application/pdf"));
        }
    }

    @Test
    void staleGenerationAndExpiredLeaseCannotFinalize() {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            fixture.grantRuntime();
            JdbcTemplate runtime = fixture.runtime();
            long document = createDocument(fixture.jdbc, "CLAIM_EVIDENCE", "application/pdf");
            UUID oldToken = UUID.randomUUID();
            long oldAttempt = createAttempt(runtime, document, 1);
            claim(runtime, oldAttempt, oldToken, true);
            runtime.update("update document_verification_attempt set status='CLEAN',completed_at=now() where id=?", oldAttempt);
            runtime.update("update private_document set current_generation=2,updated_at=now() where id=?", document);
            assertThrows(DataAccessException.class, () -> insertVersion(runtime, document, oldAttempt, oldToken, "application/pdf"));

            long currentAttempt = createAttempt(runtime, document, 2);
            assertThrows(DataAccessException.class, () -> claim(runtime, currentAttempt, UUID.randomUUID(), false));
            assertEquals(0, runtime.queryForObject("select count(id) from document_version where document_id=?", Integer.class, document));
        }
    }

    @Test
    void unfinishedUploadCannotStartVerification() {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            fixture.grantRuntime();
            JdbcTemplate runtime = fixture.runtime();
            long document = createDocument(fixture.jdbc, "CLAIM_EVIDENCE", "application/pdf");
            long upload = reserveUpload(runtime, document, 2);
            runtime.update("update private_document set current_upload_id=?,current_upload_generation=2,"
                + "current_generation=2,updated_at=now() where id=?", upload, document);
            assertThrows(DataAccessException.class, () -> createAttempt(runtime, document, 2));
            assertEquals(0, runtime.queryForObject("select count(id) from document_verification_attempt where document_id=?", Integer.class, document));
        }
    }

    @Test
    void holdHistoryAllowsOnlyReasonedOneWayRelease() {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            fixture.grantRuntime();
            JdbcTemplate runtime = fixture.runtime();
            long document = createDocument(fixture.jdbc, "CLAIM_EVIDENCE", "application/pdf");
            long account = fixture.jdbc.queryForObject("insert into user_accounts(public_id,oidc_issuer,oidc_subject,created_at) "
                + "values(gen_random_uuid(),'https://identity.example','hold-test',now()) returning id", Long.class);
            long hold = runtime.queryForObject("insert into document_hold(public_id,document_id,reason,created_by,created_at) "
                + "values(gen_random_uuid(),?,'Legal hold',?,now()) returning id", Long.class, document, account);

            assertThrows(DataAccessException.class, () -> runtime.update("update document_hold set reason='Changed' where id=?", hold));
            assertThrows(DataAccessException.class, () -> runtime.update("update document_hold set released_at=now() where id=?", hold));
            runtime.update("update document_hold set released_at=now(),release_reason='Released' where id=?", hold);
            assertThrows(DataAccessException.class, () -> runtime.update("update document_hold set released_at=null,release_reason=null where id=?", hold));
            assertThrows(DataAccessException.class, () -> runtime.update("delete from document_hold where id=?", hold));
            assertEquals(1, runtime.queryForObject("select count(id) from document_hold where document_id=?", Integer.class, document));
        }
    }

    private static long createDocument(JdbcTemplate jdbc, String purpose, String mediaType) {
        long employee = jdbc.queryForObject("insert into employees(public_id,employee_number,created_at) "
            + "values(gen_random_uuid(),?,now()) returning id", Long.class, "DOC-" + UUID.randomUUID());
        long document = jdbc.queryForObject("insert into private_document(public_id,employee_id,purpose,status,file_name,"
            + "declared_media_type,declared_size,current_generation,created_at,updated_at) "
            + "values(gen_random_uuid(),?,?,'VERIFYING','file.pdf',?,1,1,now(),now()) returning id",
            Long.class, employee, purpose, mediaType);
        long upload = reserveUpload(jdbc, document, 1);
        jdbc.update("update document_upload_attempt set status='COMPLETED',object_etag='dummy-etag',completed_at=now() where id=?", upload);
        jdbc.update("update private_document set current_upload_id=?,current_upload_generation=1 where id=?", upload, document);
        return document;
    }

    private static long reserveUpload(JdbcTemplate jdbc, long document, long generation) {
        UUID publicId = UUID.randomUUID();
        return jdbc.queryForObject("insert into document_upload_attempt(public_id,document_id,generation,staging_key,"
            + "byte_size,status,created_at) values(?,?,?,?,1,'RESERVED',now()) returning id",
            Long.class, publicId, document, generation, "staging/" + publicId);
    }

    private static long createAttempt(JdbcTemplate jdbc, long document, long generation) {
        UUID publicId = UUID.randomUUID();
        return jdbc.queryForObject("insert into document_verification_attempt(public_id,document_id,upload_attempt_id,"
            + "generation,candidate_key,status,created_at) select ?,id,current_upload_id,?,?,'QUEUED',now() "
            + "from private_document where id=? returning id", Long.class, publicId, generation, "candidates/" + publicId, document);
    }

    private static void claim(JdbcTemplate jdbc, long attempt, UUID token, boolean future) {
        jdbc.update("update document_verification_attempt set status='CLAIMED',lease_token=?,lease_expires_at="
            + "case when ? then now() + interval '5 minutes' else now() - interval '1 second' end where id=?",
            token, future, attempt);
        jdbc.update("update document_verification_attempt set copy_started_at=clock_timestamp() where id=?", attempt);
        jdbc.update("update document_verification_attempt set copy_completed_at=clock_timestamp() where id=?", attempt);
    }

    private static long insertVersion(JdbcTemplate jdbc, long document, long attempt, UUID token, String mediaType) {
        return jdbc.queryForObject("insert into document_version(public_id,document_id,attempt_id,lease_token,media_type,"
            + "byte_size,sha256,scanner_version,verified_at) values(gen_random_uuid(),?,?,?,?,1,repeat('a',64),'test-scanner',now()) returning id",
            Long.class, document, attempt, token, mediaType);
    }
}
