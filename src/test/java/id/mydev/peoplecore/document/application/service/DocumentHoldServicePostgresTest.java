package id.mydev.peoplecore.document.application.service;

import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.document.infrastructure.persistence.JdbcDocumentHoldRepository;
import id.mydev.peoplecore.common.command.CommandConflictException;
import id.mydev.peoplecore.identity.application.service.AccountAccessService;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.organization.application.service.EmployeeService;
import id.mydev.peoplecore.support.PostgresFixture;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("postgres")
class DocumentHoldServicePostgresTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-10T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void holdIsRecordedOnceAndReleasedOnlyWithAReason() {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            fixture.grantRuntime();
            JdbcTemplate jdbc = fixture.runtime();
            Authentication caller = mock(Authentication.class);
            AccountAccessService access = mock(AccountAccessService.class);
            EmployeeService employees = mock(EmployeeService.class);
            UserAccount account = mock(UserAccount.class);
            long accountId = account(jdbc);
            when(account.getId()).thenReturn(accountId);
            when(access.requireActive(eq(caller), any())).thenReturn(account);
            long employee = employee(jdbc);
            when(employees.currentOrgUnit(eq(employee), any())).thenReturn(Optional.of("HQ"));
            UUID document = document(jdbc, employee);
            var service = new DocumentHoldService(new JdbcDocumentHoldRepository(jdbc), access, employees, CLOCK);
            var transactions = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
            UUID hold = UUID.randomUUID();

            transactions.executeWithoutResult(status -> service.place(hold, document, "Litigation hold", caller));

            assertEquals(1, count(jdbc, "SELECT count(id) FROM document_hold WHERE released_at IS NULL"));
            assertEquals("Litigation hold",
                jdbc.queryForObject("SELECT reason FROM document_hold WHERE public_id=?", String.class, hold));
            assertThrows(CommandConflictException.class, () -> transactions.executeWithoutResult(
                status -> service.place(UUID.randomUUID(), document, "Second hold", caller)));
            assertEquals(1, transactions.execute(status -> service.active(document, caller)).size());
            assertThrows(DataAccessException.class,
                () -> jdbc.update("UPDATE private_document SET deletion_state='CLAIMED' WHERE public_id=?", document));

            transactions.executeWithoutResult(status -> service.release(hold, document, "Matter closed", caller));

            assertEquals(0, transactions.execute(status -> service.active(document, caller)).size());
            jdbc.update("UPDATE private_document SET deletion_state='CLAIMED',deletion_requested_at=now(),"
                + "deletion_restore_deadline=now()+interval '30 days' WHERE public_id=?", document);

            assertNotNull(jdbc.queryForObject("SELECT released_at FROM document_hold WHERE public_id=?",
                java.sql.Timestamp.class, hold));
            assertEquals("Matter closed",
                jdbc.queryForObject("SELECT release_reason FROM document_hold WHERE public_id=?", String.class, hold));
            assertThrows(CommandConflictException.class, () -> transactions.executeWithoutResult(
                status -> service.release(hold, document, "Second release", caller)));
            assertThrows(DataAccessException.class,
                () -> jdbc.update("UPDATE document_hold SET reason='Tampered' WHERE public_id=?", hold));
        }
    }

    @Test
    void holdRequiresCurrentHrScopeAndANonBlankReason() {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            fixture.grantRuntime();
            JdbcTemplate jdbc = fixture.runtime();
            Authentication caller = mock(Authentication.class);
            AccountAccessService access = mock(AccountAccessService.class);
            EmployeeService employees = mock(EmployeeService.class);
            UserAccount account = mock(UserAccount.class);
            when(account.getId()).thenReturn(account(jdbc));
            when(access.requireActive(eq(caller), any())).thenReturn(account);
            long employee = employee(jdbc);
            when(employees.currentOrgUnit(eq(employee), any())).thenReturn(Optional.of("HQ"));
            UUID document = document(jdbc, employee);
            var service = new DocumentHoldService(new JdbcDocumentHoldRepository(jdbc), access, employees, CLOCK);
            var transactions = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));

            assertThrows(IllegalArgumentException.class, () -> transactions.executeWithoutResult(
                status -> service.place(UUID.randomUUID(), document, "  ", caller)));
            assertEquals(0, count(jdbc, "SELECT count(id) FROM document_hold"));

            doThrow(new AccessDeniedException("HR scope does not cover this organization unit"))
                .when(access).requireHrScope(eq(caller), eq("HQ"), any());
            assertThrows(ResourceNotFoundException.class, () -> transactions.executeWithoutResult(
                status -> service.place(UUID.randomUUID(), document, "Litigation hold", caller)));
            assertEquals(0, count(jdbc, "SELECT count(id) FROM document_hold"));
        }
    }

    private static int count(JdbcTemplate jdbc, String sql) {
        Integer count = jdbc.queryForObject(sql, Integer.class);
        return count == null ? 0 : count;
    }

    private static long account(JdbcTemplate jdbc) {
        return jdbc.queryForObject("INSERT INTO user_accounts(public_id,oidc_issuer,oidc_subject,created_at) "
            + "VALUES (gen_random_uuid(),'https://hold.test','holder',now()) RETURNING id", Long.class);
    }

    private static long employee(JdbcTemplate jdbc) {
        return jdbc.queryForObject("INSERT INTO employees(public_id,employee_number,created_at) "
            + "VALUES (gen_random_uuid(),?,now()) RETURNING id", Long.class, "HOLD-" + UUID.randomUUID());
    }

    private static UUID document(JdbcTemplate jdbc, long employee) {
        UUID publicId = UUID.randomUUID();
        jdbc.update("INSERT INTO private_document(public_id,employee_id,purpose,status,file_name,declared_media_type,"
                + "declared_size,current_generation,created_at,updated_at) "
                + "VALUES (?,?,'CLAIM_EVIDENCE','PENDING_UPLOAD','receipt.pdf','application/pdf',1,0,now(),now())",
            publicId, employee);
        return publicId;
    }
}
