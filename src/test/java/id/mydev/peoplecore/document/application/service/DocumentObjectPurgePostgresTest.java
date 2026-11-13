package id.mydev.peoplecore.document.application.service;

import id.mydev.peoplecore.document.infrastructure.persistence.JdbcDocumentDeletionRepository;
import id.mydev.peoplecore.document.infrastructure.storage.PrivateDocumentStorage;
import id.mydev.peoplecore.document.infrastructure.storage.DocumentCopyClaim;
import id.mydev.peoplecore.identity.application.service.AccountAccessService;
import id.mydev.peoplecore.support.PostgresFixture;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.HashSet;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.sql.SQLException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.core.exception.SdkClientException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Tag("postgres")
class DocumentObjectPurgePostgresTest {
    private static final Instant NOW = Instant.parse("2026-11-10T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void copyCompletingBetweenDeleteAndAcknowledgementCannotLeaveAnOrphan() {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            fixture.grantRuntime();
            JdbcTemplate jdbc = fixture.runtime();
            long document = document(jdbc);
            String key = candidate(jdbc, document, 1);
            CopyIdentity copy = claimCopy(jdbc, document);
            DocumentCopyClaim claims = copyClaims(jdbc);
            claims.start(copy.uploadId(), copy.attemptId(), copy.token(), 1);
            expire(jdbc, document);
            HashSet<String> objects = new HashSet<>();
            AtomicBoolean providerPaused = new AtomicBoolean(true);
            PrivateDocumentStorage storage = mock(PrivateDocumentStorage.class);
            doAnswer(invocation -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                objects.remove(invocation.getArgument(0, String.class));
                if (providerPaused.get()) {
                    objects.add(key);
                    claims.complete(copy.attemptId(), copy.token());
                }
                return null;
            }).when(storage).deleteObject(anyString());

            assertEquals(0, service(jdbc, storage).purgeExpired(10));
            assertPending(jdbc, document);
            verifyNoInteractions(storage);
            providerPaused.set(false);
            objects.add(key);
            claims.complete(copy.attemptId(), copy.token());

            assertEquals(1, service(jdbc, storage).purgeExpired(10));
            assertTrue(objects.isEmpty());
            assertNotNull(completedAt(jdbc, document));
            verify(storage).deleteObject(key);
        }
    }

    @Test
    void abandonedExpiredCopyRemainsPendingBecauseItsProviderOutcomeIsUnknown() {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            fixture.grantRuntime();
            JdbcTemplate jdbc = fixture.runtime();
            long document = document(jdbc);
            candidate(jdbc, document, 1);
            CopyIdentity copy = claimCopy(jdbc, document);
            DocumentCopyClaim claims = copyClaims(jdbc);
            claims.start(copy.uploadId(), copy.attemptId(), copy.token(), 1);
            jdbc.update("UPDATE document_verification_attempt SET status='ABANDONED',"
                + "lease_expires_at=clock_timestamp()-interval '1 second' WHERE public_id=?", copy.attemptId());
            expire(jdbc, document);
            PrivateDocumentStorage storage = mock(PrivateDocumentStorage.class);

            assertEquals(0, service(jdbc, storage).purgeExpired(10));
            assertEquals(0, service(jdbc, storage).purgeExpired(10));
            assertPending(jdbc, document);
            assertThrows(IllegalStateException.class, () -> claims.complete(copy.attemptId(), copy.token()));
        }
    }

    @Test
    void copyStartSerializesWithTheParentDeletionLockAndCannotStartAfterDeletionClaim() {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            fixture.grantRuntime();
            JdbcTemplate jdbc = fixture.runtime();
            long document = document(jdbc);
            candidate(jdbc, document, 1);
            CopyIdentity copy = claimCopy(jdbc, document);
            var source = new DriverManagerDataSource(fixture.url, fixture.runtimeUsername, fixture.runtimePassword);
            Properties properties = new Properties();
            properties.setProperty("options", "-c lock_timeout=200ms");
            source.setConnectionProperties(properties);
            DocumentCopyClaim claims = copyClaims(new JdbcTemplate(source));
            var transactions = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
            transactions.executeWithoutResult(status -> {
                expire(jdbc, document);
                DataAccessException failure = assertThrows(DataAccessException.class, () ->
                    claims.start(copy.uploadId(), copy.attemptId(), copy.token(), 1));
                assertTrue(failure.getMostSpecificCause() instanceof SQLException);
                assertEquals("55P03", ((SQLException) failure.getMostSpecificCause()).getSQLState());
            });

            assertThrows(IllegalStateException.class, () -> claims.start(copy.uploadId(), copy.attemptId(), copy.token(), 1));
            assertNull(jdbc.queryForObject("SELECT copy_started_at FROM document_verification_attempt WHERE public_id=?",
                Object.class, copy.attemptId()));
        }
    }

    @Test
    void aFullBatchOfUnsettledDeletedDocumentsDoesNotStarveReadyPurges() {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            fixture.grantRuntime();
            JdbcTemplate jdbc = fixture.runtime();
            long first = document(jdbc);
            String firstKey = candidate(jdbc, first, 1);
            CopyIdentity firstCopy = claimCopy(jdbc, first);
            copyClaims(jdbc).start(firstCopy.uploadId(), firstCopy.attemptId(), firstCopy.token(), 1);
            expire(jdbc, first);
            long second = document(jdbc);
            String secondKey = candidate(jdbc, second, 1);
            CopyIdentity secondCopy = claimCopy(jdbc, second);
            copyClaims(jdbc).start(secondCopy.uploadId(), secondCopy.attemptId(), secondCopy.token(), 1);
            expire(jdbc, second);
            long ready = document(jdbc);
            String readyKey = candidate(jdbc, ready, 1);
            expire(jdbc, ready);
            PrivateDocumentStorage storage = mock(PrivateDocumentStorage.class);

            assertEquals(0, service(jdbc, storage).purgeExpired(2));
            assertPending(jdbc, first);
            assertPending(jdbc, second);
            verifyNoInteractions(storage);
            assertEquals(1, service(jdbc, storage).purgeExpired(2));
            verify(storage).deleteObject(readyKey);
            assertNotNull(completedAt(jdbc, ready));

            copyClaims(jdbc).complete(firstCopy.attemptId(), firstCopy.token());
            assertEquals(1, service(jdbc, storage).purgeExpired(2));
            verify(storage).deleteObject(firstKey);
            verify(storage, never()).deleteObject(secondKey);
            assertPending(jdbc, second);
        }
    }

    @Test
    void failedObjectDeleteIsRetriedAfterRestartAndOnlyAcknowledgedAfterEveryKeyIsDeleted() {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            fixture.grantRuntime();
            JdbcTemplate jdbc = fixture.runtime();
            long document = document(jdbc);
            String first = candidate(jdbc, document, 1);
            String second = candidate(jdbc, document, 2);
            expire(jdbc, document);
            PrivateDocumentStorage storage = mock(PrivateDocumentStorage.class);
            AtomicInteger calls = new AtomicInteger();
            doAnswer(invocation -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                if (calls.getAndIncrement() == 0) {
                    throw SdkClientException.create("Object storage temporarily unavailable");
                }
                return null;
            }).when(storage).deleteObject(anyString());

            assertEquals(0, service(jdbc, storage).purgeExpired(10));
            assertPending(jdbc, document);
            assertEquals(1, auditCount(jdbc, document));

            assertEquals(1, service(jdbc, storage).purgeExpired(10));
            assertNotNull(completedAt(jdbc, document));
            assertEquals(1, auditCount(jdbc, document));
            assertEquals(0, service(jdbc, storage).purgeExpired(10));
            verify(storage, times(2)).deleteObject(first);
            verify(storage).deleteObject(second);
        }
    }

    @Test
    void restartAfterMetadataCommitResumesPendingObjectsWithoutDeletingLiveVersion() {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            fixture.grantRuntime();
            JdbcTemplate jdbc = fixture.runtime();
            long deleted = document(jdbc);
            String deletedKey = candidate(jdbc, deleted, 1);
            expire(jdbc, deleted);
            assertTrue(worker(jdbc).purge(deleted, NOW));
            assertPending(jdbc, deleted);
            long live = document(jdbc);
            candidate(jdbc, live, 1);
            verifyCandidate(jdbc, live);
            var repository = new JdbcDocumentDeletionRepository(jdbc);
            assertTrue(repository.candidateKeys(live).isEmpty());
            assertFalse(repository.completeObjectPurge(live, NOW));
            PrivateDocumentStorage storage = mock(PrivateDocumentStorage.class);

            assertEquals(1, service(jdbc, storage).purgeExpired(10));
            assertEquals(1, auditCount(jdbc, deleted));
            verify(storage).deleteObject(deletedKey);
            assertEquals("LIVE", jdbc.queryForObject("SELECT deletion_state FROM private_document WHERE id=?",
                String.class, live));
            assertFalse(worker(jdbc).completeObjectPurge(deleted, NOW));
        }
    }

    @Test
    void failedCompletionCommitLeavesTheObjectPurgePendingForAnotherPoll() {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            fixture.grantRuntime();
            JdbcTemplate jdbc = fixture.runtime();
            long document = document(jdbc);
            String key = candidate(jdbc, document, 1);
            expire(jdbc, document);
            fixture.jdbc.execute("CREATE FUNCTION fail_object_purge_ack() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN IF NEW.objects_purged_at IS NOT NULL THEN RAISE EXCEPTION 'temporary failure'; END IF; "
                + "RETURN NEW; END; $$");
            fixture.jdbc.execute("CREATE TRIGGER fail_object_purge_ack BEFORE UPDATE ON private_document "
                + "FOR EACH ROW EXECUTE FUNCTION fail_object_purge_ack()");
            PrivateDocumentStorage storage = mock(PrivateDocumentStorage.class);

            assertEquals(0, service(jdbc, storage).purgeExpired(10));
            assertPending(jdbc, document);
            fixture.jdbc.execute("DROP TRIGGER fail_object_purge_ack ON private_document");
            assertEquals(1, service(jdbc, storage).purgeExpired(10));
            assertEquals(1, auditCount(jdbc, document));
            verify(storage, times(2)).deleteObject(key);
        }
    }

    @Test
    void metadataDeletionAndItsAuditRollBackTogetherBeforeAnyObjectDelete() {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            fixture.grantRuntime();
            JdbcTemplate jdbc = fixture.runtime();
            long document = document(jdbc);
            candidate(jdbc, document, 1);
            expire(jdbc, document);
            fixture.jdbc.execute("CREATE FUNCTION fail_purge_audit() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN IF NEW.action='DOCUMENT_PURGED' THEN RAISE EXCEPTION 'temporary failure'; END IF; "
                + "RETURN NEW; END; $$");
            fixture.jdbc.execute("CREATE TRIGGER fail_purge_audit BEFORE INSERT ON audit_events "
                + "FOR EACH ROW EXECUTE FUNCTION fail_purge_audit()");

            assertThrows(DataAccessException.class, () -> worker(jdbc).purge(document, NOW));
            assertEquals("CLAIMED", jdbc.queryForObject("SELECT deletion_state FROM private_document WHERE id=?",
                String.class, document));
            assertNull(jdbc.queryForObject("SELECT purged_at FROM private_document WHERE id=?", Object.class, document));
            assertEquals(0, auditCount(jdbc, document));
        }
    }

    @Test
    void migrationQueuesLegacyDeletedDocumentsForObjectCleanup() {
        try (var fixture = new PostgresFixture()) {
            Flyway.configure().dataSource(fixture.url, fixture.username, fixture.password).schemas(fixture.schema)
                .target("26").load().migrate();
            fixture.grantRuntime();
            JdbcTemplate jdbc = fixture.runtime();
            long document = document(jdbc);
            String key = candidate(jdbc, document, 1);
            expire(jdbc, document);
            var repository = new JdbcDocumentDeletionRepository(jdbc);
            new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()))
                .executeWithoutResult(status -> {
                    assertTrue(repository.markPurged(document, NOW));
                    repository.recordPurgeAudit(document, NOW);
                });
            fixture.flyway().migrate();
            assertPending(jdbc, document);
            PrivateDocumentStorage storage = mock(PrivateDocumentStorage.class);

            assertEquals(1, service(jdbc, storage).purgeExpired(10));
            verify(storage).deleteObject(key);
            assertNotNull(completedAt(jdbc, document));
            assertEquals(1, auditCount(jdbc, document));
        }
    }

    @Test
    void liveAndUnexpiredClaimedDocumentsNeverYieldObjectsForDeletion() {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            fixture.grantRuntime();
            JdbcTemplate jdbc = fixture.runtime();
            long document = document(jdbc);
            candidate(jdbc, document, 1);
            var repository = new JdbcDocumentDeletionRepository(jdbc);
            assertTrue(repository.candidateKeys(document).isEmpty());
            assertTrue(repository.claim(document, NOW, NOW.plusSeconds(3600)));
            assertTrue(repository.candidateKeys(document).isEmpty());
            PrivateDocumentStorage storage = mock(PrivateDocumentStorage.class);

            assertEquals(0, service(jdbc, storage).purgeExpired(10));
            verifyNoInteractions(storage);
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE private_document SET objects_purged_at=now() WHERE id=?", document));
        }
    }

    private static void assertPending(JdbcTemplate jdbc, long document) {
        assertEquals("DELETED", jdbc.queryForObject("SELECT deletion_state FROM private_document WHERE id=?",
            String.class, document));
        assertNotNull(jdbc.queryForObject("SELECT purged_at FROM private_document WHERE id=?", Object.class, document));
        assertNull(completedAt(jdbc, document));
    }

    private static Object completedAt(JdbcTemplate jdbc, long document) {
        return jdbc.queryForObject("SELECT objects_purged_at FROM private_document WHERE id=?", Object.class, document);
    }

    private static int auditCount(JdbcTemplate jdbc, long document) {
        return jdbc.queryForObject("SELECT count(id) FROM audit_events WHERE aggregate_id=? "
            + "AND action='DOCUMENT_PURGED'", Integer.class, document);
    }

    private static DocumentPurgeWorker worker(JdbcTemplate jdbc) {
        var factory = new ProxyFactory(new DocumentPurgeWorker(new JdbcDocumentDeletionRepository(jdbc)));
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(jdbc.getDataSource()),
            new AnnotationTransactionAttributeSource()));
        return (DocumentPurgeWorker) factory.getProxy();
    }

    @SuppressWarnings("unchecked")
    private static DocumentDeletionService service(JdbcTemplate jdbc, PrivateDocumentStorage storage) {
        ObjectProvider<PrivateDocumentStorage> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(storage);
        return new DocumentDeletionService(new JdbcDocumentDeletionRepository(jdbc), worker(jdbc), provider,
            mock(AccountAccessService.class), CLOCK, Duration.ofDays(30));
    }

    private static void expire(JdbcTemplate jdbc, long document) {
        var repository = new JdbcDocumentDeletionRepository(jdbc);
        assertTrue(repository.claim(document, NOW.minusSeconds(7200), NOW.minusSeconds(3600)));
    }

    private static long document(JdbcTemplate jdbc) {
        Long employee = jdbc.queryForObject("INSERT INTO employees(public_id,employee_number,created_at) "
            + "VALUES (gen_random_uuid(),?,now()) RETURNING id", Long.class, "PURGE-" + UUID.randomUUID());
        return jdbc.queryForObject("INSERT INTO private_document(public_id,employee_id,purpose,status,file_name,"
            + "declared_media_type,declared_size,created_at,updated_at) "
            + "VALUES (gen_random_uuid(),?,'CLAIM_EVIDENCE','PENDING_UPLOAD','test.pdf','application/pdf',1,now(),now()) "
            + "RETURNING id", Long.class, employee);
    }

    private static String candidate(JdbcTemplate jdbc, long document, int generation) {
        jdbc.update("UPDATE document_verification_attempt SET status='ABANDONED' WHERE document_id=? AND status='QUEUED'", document);
        UUID uploadId = UUID.randomUUID();
        Long upload = jdbc.queryForObject("INSERT INTO document_upload_attempt(public_id,document_id,generation,"
            + "staging_key,byte_size,status,created_at) VALUES (?,?,?,?,1,'RESERVED',now()) RETURNING id",
            Long.class, uploadId, document, generation, "staging/" + uploadId);
        jdbc.update("UPDATE document_upload_attempt SET status='COMPLETED',object_etag='test',completed_at=now() WHERE id=?", upload);
        jdbc.update("UPDATE private_document SET status='VERIFYING',current_generation=?,current_upload_id=?,current_upload_generation=? WHERE id=?",
            generation, upload, generation, document);
        UUID attemptId = UUID.randomUUID();
        String key = "candidates/" + attemptId;
        jdbc.update("INSERT INTO document_verification_attempt(public_id,document_id,upload_attempt_id,generation,"
            + "candidate_key,status,created_at) VALUES (?,?,?,?,?,'QUEUED',now())", attemptId, document, upload, generation, key);
        return key;
    }

    private static CopyIdentity claimCopy(JdbcTemplate jdbc, long document) {
        UUID token = UUID.randomUUID();
        jdbc.update("UPDATE document_verification_attempt SET status='CLAIMED',lease_token=?,"
            + "lease_expires_at=clock_timestamp()+interval '5 minutes' WHERE document_id=?", token, document);
        return jdbc.queryForObject("SELECT u.public_id,a.public_id FROM document_verification_attempt a "
                + "JOIN document_upload_attempt u ON u.id=a.upload_attempt_id WHERE a.document_id=?",
            (rs, row) -> new CopyIdentity(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), token), document);
    }

    private static DocumentCopyClaim copyClaims(JdbcTemplate jdbc) {
        return new DocumentCopyClaim(jdbc, new DataSourceTransactionManager(jdbc.getDataSource()));
    }

    private record CopyIdentity(UUID uploadId, UUID attemptId, UUID token) { }

    private static void verifyCandidate(JdbcTemplate jdbc, long document) {
        UUID token = UUID.randomUUID();
        jdbc.update("UPDATE document_verification_attempt SET status='CLAIMED',lease_token=?,lease_expires_at=now()+interval '5 minutes' WHERE document_id=?",
            token, document);
        jdbc.update("UPDATE document_verification_attempt SET copy_started_at=now() WHERE document_id=?", document);
        jdbc.update("UPDATE document_verification_attempt SET copy_completed_at=now() WHERE document_id=?", document);
        jdbc.update("UPDATE document_verification_attempt SET status='CLEAN',completed_at=now() WHERE document_id=?", document);
        Long version = jdbc.queryForObject("INSERT INTO document_version(public_id,document_id,attempt_id,lease_token,"
            + "media_type,byte_size,sha256,scanner_version,verified_at) SELECT gen_random_uuid(),document_id,id,?,"
            + "'application/pdf',1,?,'test',now() FROM document_verification_attempt WHERE document_id=? RETURNING id",
            Long.class, token, "a".repeat(64), document);
        jdbc.update("UPDATE private_document SET status='VERIFIED',current_version_id=? WHERE id=?", version, document);
    }
}
