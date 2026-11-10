package id.mydev.peoplecore.reimbursement.application.service;

import id.mydev.peoplecore.common.command.AdmissionGuard;
import id.mydev.peoplecore.identity.application.service.AccountAccessService;
import id.mydev.peoplecore.identity.domain.model.HrisRole;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.organization.application.service.EmployeeService;
import id.mydev.peoplecore.organization.domain.model.Employee;
import id.mydev.peoplecore.support.PostgresFixture;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("postgres")
class ReimbursementListPostgresTest {
    @Test
    void listUsesCurrentScopeBeforePaginationAndRechecksAccount() {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            UUID actorId = UUID.randomUUID();
            long accountId = fixture.jdbc.queryForObject("INSERT INTO user_accounts(public_id,oidc_issuer,oidc_subject,created_at) "
                + "VALUES (?,'https://list.test',?,now()) RETURNING id", Long.class, actorId, UUID.randomUUID().toString());
            fixture.jdbc.update("INSERT INTO role_assignments(account_id,role,scope,effective_from,created_at) "
                + "VALUES (?,'FINANCE_PAYROLL','ENG','2026-01-01',now())", accountId);
            Target first = target(fixture, "ENG");
            Target second = target(fixture, "ENG");
            Target outside = target(fixture, "OTHER");
            UUID firstClaim = claim(fixture, first.id());
            UUID secondClaim = claim(fixture, second.id());
            UUID outsideClaim = claim(fixture, outside.id());
            Authentication caller = mock(Authentication.class);
            UserAccount actor = mock(UserAccount.class);
            when(actor.getId()).thenReturn(accountId);
            when(actor.getPublicId()).thenReturn(actorId);
            AccountAccessService access = mock(AccountAccessService.class);
            when(access.requireActive(eq(caller), any())).thenReturn(actor);
            when(access.activeRoles(eq(accountId), any())).thenReturn(List.of(HrisRole.FINANCE_PAYROLL));
            when(access.ownEmployeePublicId(actor)).thenReturn(Optional.empty());
            when(access.hasScopedRole(eq(accountId), eq(HrisRole.FINANCE_PAYROLL), eq("ENG"), any())).thenReturn(true);
            EmployeeService employees = mock(EmployeeService.class);
            stub(employees, first, "ENG");
            stub(employees, second, "ENG");
            stub(employees, outside, "OTHER");
            var service = new ReimbursementWorkflowService(fixture.jdbc, employees, access,
                mock(ReimbursementSubmissionService.class), mock(ReimbursementCategoryService.class),
                mock(EntityManager.class), mock(AdmissionGuard.class),
                Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC));
            var transactions = new TransactionTemplate(new DataSourceTransactionManager(fixture.jdbc.getDataSource()));

            var page = transactions.execute(status -> service.list(1, null, caller));
            assertEquals(1, page.items().size());
            assertNotNull(page.nextCursor());
            var next = transactions.execute(status -> service.list(1, page.nextCursor(), caller));
            var returned = new HashSet<UUID>();
            returned.add(page.items().getFirst().publicId());
            returned.add(next.items().getFirst().publicId());
            assertEquals(java.util.Set.of(firstClaim, secondClaim), returned);
            assertEquals(false, returned.contains(outsideClaim));
            when(access.requireActive(eq(caller), any())).thenReturn(actor).thenThrow(new AccessDeniedException("cutoff"));
            assertThrows(AccessDeniedException.class, () -> transactions.execute(status -> service.list(10, null, caller)));
        }
    }

    private static Target target(PostgresFixture fixture, String unit) {
        UUID publicId = UUID.randomUUID();
        long id = fixture.jdbc.queryForObject("INSERT INTO employees(public_id,employee_number,created_at) "
            + "VALUES (?,?,now()) RETURNING id", Long.class, publicId, "LIST-" + UUID.randomUUID());
        fixture.jdbc.update("INSERT INTO employee_assignments(public_id,employee_id,org_unit,job_level,valid_from,created_at) "
            + "VALUES (?,?,?,'STAFF','2026-01-01',now())", UUID.randomUUID(), id, unit);
        return new Target(id, publicId);
    }

    private static UUID claim(PostgresFixture fixture, long employeeId) {
        UUID id = UUID.randomUUID();
        fixture.jdbc.update("INSERT INTO reimbursement_claim(public_id,employee_id,status,category_code,transaction_date,amount,description,created_at) "
            + "VALUES (?,?,'DRAFT','TRAVEL','2026-10-01',100,'Private description',now())", id, employeeId);
        return id;
    }

    private static void stub(EmployeeService employees, Target target, String unit) {
        Employee employee = mock(Employee.class);
        when(employee.getId()).thenReturn(target.id());
        when(employee.getPublicId()).thenReturn(target.publicId());
        when(employees.requireInternalByPublicId(target.publicId())).thenReturn(employee);
        when(employees.currentOrgUnit(eq(target.id()), any())).thenReturn(Optional.of(unit));
    }

    private record Target(long id, UUID publicId) { }
}
