package id.mydev.peoplecore.reimbursement.application.service;

import id.mydev.peoplecore.identity.application.service.AccountAccessService;
import id.mydev.peoplecore.identity.domain.model.HrisRole;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.reimbursement.application.service.ReimbursementCategoryService.Category;
import id.mydev.peoplecore.support.PostgresFixture;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("postgres")
class ReimbursementCategoryPostgresTest {
    @Test
    void financeCanPublishAndReadButCannotExtendAReplacementBeyondItsOriginalInterval() {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            Authentication caller = mock(Authentication.class);
            AccountAccessService access = authorizedAccess(caller);
            when(access.hasScopedRole(eq(1L), eq(HrisRole.HR_ADMIN), isNull(), any())).thenReturn(false);
            when(access.hasScopedRole(eq(1L), eq(HrisRole.FINANCE_PAYROLL), isNull(), any())).thenReturn(true);
            when(access.hasRole(eq(1L), eq(HrisRole.FINANCE_PAYROLL), any())).thenReturn(true);
            var service = categoryService(fixture, access);
            var transactions = new TransactionTemplate(new DataSourceTransactionManager(fixture.jdbc.getDataSource()));
            transactions.executeWithoutResult(status -> service.publish(UUID.randomUUID(),
                new Category("TRAVEL", LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1), 100), caller));
            assertThrows(ReimbursementConflictException.class, () -> transactions.executeWithoutResult(status -> service.publish(
                UUID.randomUUID(), new Category("TRAVEL", LocalDate.of(2026, 7, 1), null, 200), caller)));
            assertThrows(ReimbursementConflictException.class, () -> transactions.executeWithoutResult(status -> service.publish(
                UUID.randomUUID(), new Category("TRAVEL", LocalDate.of(2026, 7, 1), LocalDate.of(2027, 2, 1), 200), caller)));
            assertEquals(100L, activeLimit(fixture, LocalDate.of(2026, 11, 1)));
            transactions.executeWithoutResult(status -> service.publish(UUID.randomUUID(),
                new Category("TRAVEL", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 7, 1), 200), caller));
            assertEquals(200L, activeLimit(fixture, LocalDate.of(2026, 6, 30)));
            assertEquals(100L, activeLimit(fixture, LocalDate.of(2026, 7, 1)));
            assertEquals(1, transactions.execute(status -> service.applicable(LocalDate.of(2026, 11, 1), 10, null, caller)).items().size());

            when(access.hasScopedRole(eq(1L), eq(HrisRole.FINANCE_PAYROLL), isNull(), any())).thenReturn(false);
            assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> transactions.executeWithoutResult(status ->
                service.publish(UUID.randomUUID(), new Category("MEAL", LocalDate.of(2026, 1, 1), null, 100), caller)));
        }
    }

    @Test
    void finiteReplacementPreservesBothSidesAndCrossingChangeRollsBack() {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            Authentication caller = mock(Authentication.class);
            AccountAccessService access = authorizedAccess(caller);
            var service = categoryService(fixture, access);
            var transactions = new TransactionTemplate(new DataSourceTransactionManager(fixture.jdbc.getDataSource()));
            transactions.executeWithoutResult(status -> service.publish(UUID.randomUUID(),
                new Category("TRAVEL", LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1), 100), caller));
            transactions.executeWithoutResult(status -> service.publish(UUID.randomUUID(),
                new Category("TRAVEL", LocalDate.of(2026, 3, 1), LocalDate.of(2026, 5, 1), 200), caller));

            assertEquals(100, activeLimit(fixture, LocalDate.of(2026, 2, 28)));
            assertEquals(200, activeLimit(fixture, LocalDate.of(2026, 3, 1)));
            assertEquals(200, activeLimit(fixture, LocalDate.of(2026, 4, 30)));
            assertEquals(100, activeLimit(fixture, LocalDate.of(2026, 5, 1)));
            assertThrows(ReimbursementConflictException.class, () -> transactions.executeWithoutResult(status -> service.publish(
                UUID.randomUUID(), new Category("TRAVEL", LocalDate.of(2026, 2, 1), LocalDate.of(2026, 6, 1), 300), caller)));
            assertEquals(3, fixture.jdbc.queryForObject("SELECT count(id) FROM reimbursement_category_revision "
                + "WHERE category_code='TRAVEL' AND NOT superseded", Integer.class));
            assertEquals(200, activeLimit(fixture, LocalDate.of(2026, 4, 1)));
        }
    }

    @Test
    void categoryPagingHasNoDuplicatesAndRechecksAuthorityBeforeReturning() {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            Authentication caller = mock(Authentication.class);
            AccountAccessService access = authorizedAccess(caller);
            when(access.hasRole(eq(1L), eq(HrisRole.HR_ADMIN), any())).thenReturn(true);
            var service = categoryService(fixture, access);
            var transactions = new TransactionTemplate(new DataSourceTransactionManager(fixture.jdbc.getDataSource()));
            for (String code : List.of("TRAVEL", "MEAL", "MEDICAL")) {
                transactions.executeWithoutResult(status -> service.publish(UUID.randomUUID(),
                    new Category(code, LocalDate.of(2026, 1, 1), null, 100), caller));
            }
            var first = transactions.execute(status -> service.applicable(LocalDate.of(2026, 11, 1), 2, null, caller));
            assertEquals(2, first.items().size());
            assertNotNull(first.nextCursor());
            var second = transactions.execute(status -> service.applicable(LocalDate.of(2026, 11, 1), 2, first.nextCursor(), caller));
            assertEquals(1, second.items().size());
            assertNull(second.nextCursor());
            var ids = new HashSet<UUID>();
            first.items().forEach(item -> ids.add(item.publicId()));
            second.items().forEach(item -> ids.add(item.publicId()));
            assertEquals(3, ids.size());
            assertTrue(transactions.execute(status -> service.applicable(LocalDate.of(2025, 12, 31), 2, null, caller)).items().isEmpty());

            when(access.hasRole(eq(1L), eq(HrisRole.HR_ADMIN), any())).thenReturn(true, false);
            assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> transactions.execute(status -> service.applicable(LocalDate.of(2026, 11, 1), 2, null, caller)));
        }
    }

    @Test
    void replacingOpenEndedPolicyPreservesPinnedRevisionAndPriorInterval() {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            Authentication caller = mock(Authentication.class);
            AccountAccessService access = mock(AccountAccessService.class);
            UserAccount account = mock(UserAccount.class);
            when(account.getId()).thenReturn(1L);
            when(access.requireActive(eq(caller), any())).thenReturn(account);
            when(access.hasScopedRole(eq(1L), eq(HrisRole.HR_ADMIN), isNull(), any())).thenReturn(true);
            var service = new ReimbursementCategoryService(fixture.jdbc, access,
                Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC),
                mock(id.mydev.peoplecore.common.command.AdmissionGuard.class), mock(jakarta.persistence.EntityManager.class));
            var transactions = new TransactionTemplate(new DataSourceTransactionManager(fixture.jdbc.getDataSource()));
            UUID original = UUID.randomUUID();
            UUID replacement = UUID.randomUUID();
            transactions.executeWithoutResult(status -> service.publish(original,
                new Category("TRAVEL", LocalDate.of(2026, 1, 1), null, 100), caller));
            long oldId = fixture.jdbc.queryForObject("SELECT id FROM reimbursement_category_revision WHERE public_id=?", Long.class, original);
            long employee = fixture.jdbc.queryForObject("INSERT INTO employees(public_id,employee_number,created_at) "
                + "VALUES (gen_random_uuid(),?,now()) RETURNING id", Long.class, "REIM-" + UUID.randomUUID());
            long claim = fixture.jdbc.queryForObject("INSERT INTO reimbursement_claim(public_id,employee_id,status,category_code,transaction_date,amount,description,created_at) "
                + "VALUES (gen_random_uuid(),?,'DRAFT','TRAVEL','2026-03-01',50,'Trip',now()) RETURNING id", Long.class, employee);
            fixture.jdbc.update("INSERT INTO reimbursement_revision(claim_id,revision,category_revision_id,transaction_date,amount,description,org_unit,submitted_at) "
                + "VALUES (?,1,?,'2026-03-01',50,'Trip','HQ',now())", claim, oldId);

            transactions.executeWithoutResult(status -> service.publish(replacement,
                new Category("TRAVEL", LocalDate.of(2026, 7, 1), null, 200), caller));

            assertEquals(oldId, fixture.jdbc.queryForObject("SELECT category_revision_id FROM reimbursement_revision WHERE claim_id=?", Long.class, claim));
            assertEquals(100L, activeLimit(fixture, LocalDate.of(2026, 6, 30)));
            assertEquals(200L, activeLimit(fixture, LocalDate.of(2026, 7, 1)));
            assertEquals(1, fixture.jdbc.queryForObject("SELECT count(id) FROM reimbursement_category_revision WHERE id=? AND superseded", Integer.class, oldId));
            when(access.hasRole(eq(1L), eq(HrisRole.HR_ADMIN), any())).thenReturn(true);
            var oldPage = transactions.execute(status -> service.applicable(LocalDate.of(2026, 6, 30), 1, null, caller));
            var newPage = transactions.execute(status -> service.applicable(LocalDate.of(2026, 7, 1), 1, null, caller));
            assertEquals(100, oldPage.items().getFirst().limit());
            assertEquals(200, newPage.items().getFirst().limit());
            assertThrows(IllegalArgumentException.class,
                () -> transactions.execute(status -> service.applicable(LocalDate.of(2026, 7, 1), 101, null, caller)));
            when(access.hasRole(eq(1L), eq(HrisRole.HR_ADMIN), any())).thenReturn(false);
            assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> transactions.execute(status -> service.applicable(LocalDate.of(2026, 7, 1), 1, null, caller)));
            assertThrows(ReimbursementConflictException.class, () -> transactions.executeWithoutResult(status -> service.publish(UUID.randomUUID(),
                new Category("TRAVEL", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 8, 1), 300), caller)));
        }
    }

    private static long activeLimit(PostgresFixture fixture, LocalDate date) {
        return fixture.jdbc.queryForObject("SELECT claim_limit FROM reimbursement_category_revision WHERE category_code='TRAVEL' AND NOT superseded "
            + "AND effective_from<=? AND (effective_to IS NULL OR effective_to>?)", Long.class, date, date);
    }

    private static AccountAccessService authorizedAccess(Authentication caller) {
        AccountAccessService access = mock(AccountAccessService.class);
        UserAccount account = mock(UserAccount.class);
        when(account.getId()).thenReturn(1L);
        when(access.requireActive(eq(caller), any())).thenReturn(account);
        when(access.hasScopedRole(eq(1L), eq(HrisRole.HR_ADMIN), isNull(), any())).thenReturn(true);
        return access;
    }

    private static ReimbursementCategoryService categoryService(PostgresFixture fixture, AccountAccessService access) {
        return new ReimbursementCategoryService(fixture.jdbc, access,
            Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC),
            mock(id.mydev.peoplecore.common.command.AdmissionGuard.class), mock(jakarta.persistence.EntityManager.class));
    }
}
