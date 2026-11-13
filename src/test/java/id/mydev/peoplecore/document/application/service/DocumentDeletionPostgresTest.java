package id.mydev.peoplecore.document.application.service;

import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.common.command.CommandConflictException;
import id.mydev.peoplecore.document.infrastructure.persistence.JdbcDocumentDeletionRepository;
import id.mydev.peoplecore.document.infrastructure.storage.PrivateDocumentStorage;
import id.mydev.peoplecore.identity.application.service.AccountAccessService;
import id.mydev.peoplecore.identity.domain.model.HrisRole;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.support.PostgresFixture;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("postgres")
class DocumentDeletionPostgresTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-10T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void ownerClaimsDeletionAndRestoresItInsideTheWindow() {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            fixture.grantRuntime();
            JdbcTemplate jdbc = fixture.runtime();
            UUID employee = employee(jdbc);
            UUID document = document(jdbc, employee);
            var service = service(jdbc, employee);
            var transactions = transactions(jdbc);

            DocumentDeletionService.Deletion claimed = transactions.execute(status ->
                service.claim(document, caller()));

            assertEquals("CLAIMED", claimed.state());
            assertEquals(1, count(jdbc, "SELECT count(id) FROM private_document WHERE public_id='" + document
                + "' AND deletion_state='CLAIMED' AND deletion_requested_at IS NOT NULL"
                + " AND deletion_restore_deadline IS NOT NULL"));

            DocumentDeletionService.Deletion restored = transactions.execute(status ->
                service.restore(document, caller()));

            assertEquals("LIVE", restored.state());
            assertEquals(1, count(jdbc, "SELECT count(id) FROM private_document WHERE public_id='" + document
                + "' AND deletion_state='LIVE' AND deletion_requested_at IS NULL"
                + " AND deletion_restore_deadline IS NULL"));
            assertThrows(CommandConflictException.class, () -> transactions.executeWithoutResult(status ->
                service.restore(document, caller())));
        }
    }

    @Test
    void activeHoldAndForeignCallerCannotClaimDeletion() {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            fixture.grantRuntime();
            JdbcTemplate jdbc = fixture.runtime();
            UUID employee = employee(jdbc);
            UUID document = document(jdbc, employee);
            var service = service(jdbc, employee);
            var transactions = transactions(jdbc);
            jdbc.update("INSERT INTO document_hold(public_id,document_id,reason,created_by,created_at)"
                + " VALUES (gen_random_uuid(),(SELECT id FROM private_document WHERE public_id=?),'Litigation',"
                + "(SELECT id FROM user_accounts ORDER BY id LIMIT 1),now())", document);

            assertThrows(CommandConflictException.class, () -> transactions.executeWithoutResult(status ->
                service.claim(document, caller())));
            assertEquals(1, count(jdbc, "SELECT count(id) FROM private_document WHERE public_id='" + document
                + "' AND deletion_state='LIVE'"));

            UUID other = employee(jdbc);
            var foreign = service(jdbc, other);
            assertThrows(ResourceNotFoundException.class, () -> transactions.executeWithoutResult(status ->
                foreign.claim(document, caller())));
        }
    }

    @Test
    void expiredClaimIsPurgedAndCanNoLongerBeRestored() {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            fixture.grantRuntime();
            JdbcTemplate jdbc = fixture.runtime();
            UUID employee = employee(jdbc);
            UUID document = document(jdbc, employee);
            PrivateDocumentStorage storage = mock(PrivateDocumentStorage.class);
            var service = service(jdbc, employee, storage);
            var transactions = transactions(jdbc);

            transactions.executeWithoutResult(status -> service.claim(document, caller()));
            jdbc.update("UPDATE private_document SET deletion_restore_deadline='2026-11-10T09:00:00Z' "
                + "WHERE public_id=?", document);

            int purged = transactions.execute(status -> service.purgeExpired(10));

            assertEquals(1, purged);
            assertEquals(1, count(jdbc, "SELECT count(id) FROM private_document WHERE public_id='" + document
                + "' AND deletion_state='DELETED' AND purged_at IS NOT NULL"));
            assertEquals(1, count(jdbc, "SELECT count(id) FROM audit_events WHERE action='DOCUMENT_PURGED' "
                + "AND actor_id='system:document-purge' AND aggregate_public_id='" + document + "'"));
            assertThrows(CommandConflictException.class, () -> transactions.executeWithoutResult(status ->
                service.restore(document, caller())));
            assertThrows(CommandConflictException.class, () -> transactions.executeWithoutResult(status ->
                service.claim(document, caller())));
        }
    }

    @Test
    void expiredClaimUnderHoldIsNotPurged() {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            fixture.grantRuntime();
            JdbcTemplate jdbc = fixture.runtime();
            UUID employee = employee(jdbc);
            UUID document = document(jdbc, employee);
            var service = service(jdbc, employee);
            var transactions = transactions(jdbc);

            transactions.executeWithoutResult(status -> service.claim(document, caller()));
            jdbc.update("UPDATE private_document SET deletion_restore_deadline='2026-11-10T09:00:00Z' "
                + "WHERE public_id=?", document);
            jdbc.update("INSERT INTO document_hold(public_id,document_id,reason,created_by,created_at) "
                + "VALUES (gen_random_uuid(),(SELECT id FROM private_document WHERE public_id=?),'Litigation',"
                + "(SELECT id FROM user_accounts ORDER BY id LIMIT 1),now())", document);

            assertEquals(0, (int) transactions.execute(status -> service.purgeExpired(10)));
            assertEquals(1, count(jdbc, "SELECT count(id) FROM private_document WHERE public_id='" + document
                + "' AND deletion_state='CLAIMED'"));

            jdbc.update("UPDATE document_hold SET released_at=now(),release_reason='Matter closed' "
                + "WHERE document_id=(SELECT id FROM private_document WHERE public_id=?)", document);

            assertEquals(1, (int) transactions.execute(status -> service.purgeExpired(10)));
            assertEquals(1, count(jdbc, "SELECT count(id) FROM private_document WHERE public_id='" + document
                + "' AND deletion_state='DELETED' AND purged_at IS NOT NULL"));
        }
    }

    @Test
    void deletionStateRequiresItsWindowColumns() {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            fixture.grantRuntime();
            JdbcTemplate jdbc = fixture.runtime();
            UUID employee = employee(jdbc);
            UUID document = document(jdbc, employee);

            assertThrows(DataAccessException.class, () -> jdbc.update(
                "UPDATE private_document SET deletion_state='CLAIMED' WHERE public_id=?", document));
            assertTrue(count(jdbc, "SELECT count(id) FROM private_document WHERE public_id='" + document
                + "' AND deletion_state='LIVE'") == 1);
        }
    }

    private static DocumentDeletionService service(JdbcTemplate jdbc, UUID employeePublicId) {
        return service(jdbc, employeePublicId, mock(PrivateDocumentStorage.class));
    }

    private static DocumentDeletionService service(JdbcTemplate jdbc, UUID employeePublicId,
                                                   PrivateDocumentStorage storage) {
        AccountAccessService access = mock(AccountAccessService.class);
        UserAccount account = mock(UserAccount.class);
        when(account.getId()).thenReturn(accountId(jdbc));
        when(access.requireActive(org.mockito.ArgumentMatchers.any(Authentication.class),
            org.mockito.ArgumentMatchers.any(Instant.class))).thenReturn(account);
        when(access.ownEmployeePublicId(account)).thenReturn(Optional.of(employeePublicId));
        when(access.activeRoles(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any()))
            .thenReturn(List.of(HrisRole.EMPLOYEE));
        ObjectProvider<PrivateDocumentStorage> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(storage);
        JdbcDocumentDeletionRepository repository = new JdbcDocumentDeletionRepository(jdbc);
        return new DocumentDeletionService(repository, new DocumentPurgeWorker(repository), provider, access, CLOCK,
            Duration.ofDays(30));
    }

    private static TransactionTemplate transactions(JdbcTemplate jdbc) {
        return new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    }

    private static Authentication caller() {
        return mock(Authentication.class);
    }

    private static int count(JdbcTemplate jdbc, String sql) {
        Integer count = jdbc.queryForObject(sql, Integer.class);
        return count == null ? 0 : count;
    }

    private static long accountId(JdbcTemplate jdbc) {
        Integer accounts = jdbc.queryForObject("SELECT count(id) FROM user_accounts", Integer.class);
        if (accounts == null || accounts == 0) {
            return jdbc.queryForObject("INSERT INTO user_accounts(public_id,oidc_issuer,oidc_subject,created_at) "
                + "VALUES (gen_random_uuid(),'https://deletion.test','owner',now()) RETURNING id", Long.class);
        }
        return jdbc.queryForObject("SELECT id FROM user_accounts ORDER BY id LIMIT 1", Long.class);
    }

    private static UUID employee(JdbcTemplate jdbc) {
        UUID publicId = UUID.randomUUID();
        jdbc.update("INSERT INTO employees(public_id,employee_number,created_at) VALUES (?,?,now())",
            publicId, "DEL-" + UUID.randomUUID());
        return publicId;
    }

    private static UUID document(JdbcTemplate jdbc, UUID employeePublicId) {
        UUID publicId = UUID.randomUUID();
        jdbc.update("INSERT INTO private_document(public_id,employee_id,purpose,status,file_name,declared_media_type,"
                + "declared_size,current_generation,created_at,updated_at) "
                + "SELECT ?,id,'CLAIM_EVIDENCE','PENDING_UPLOAD','receipt.pdf','application/pdf',1,0,now(),now() "
                + "FROM employees WHERE public_id=?",
            publicId, employeePublicId);
        assertNotNull(publicId);
        return publicId;
    }
}
