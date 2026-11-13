package id.mydev.peoplecore.reimbursement.application.service;

import id.mydev.peoplecore.reimbursement.infrastructure.persistence.JdbcReimbursementWorkflowRepository;
import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.common.command.AdmissionGuard;
import id.mydev.peoplecore.identity.application.service.AccountAccessService;
import id.mydev.peoplecore.identity.domain.model.HrisRole;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.organization.application.service.EmployeeService;
import id.mydev.peoplecore.organization.domain.model.Employee;
import jakarta.persistence.EntityManager;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.core.Authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReimbursementReadAuthorizationTest {
    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");

    @Test
    void reviewerCannotReadWhenClaimBecomesTerminalDuringMaterialization() {
        Scenario scenario = new Scenario(true);
        assertThrows(ResourceNotFoundException.class, () -> scenario.service.get(scenario.claimId, scenario.caller));
    }

    @Test
    void currentReviewerCanReadWhileClaimRemainsPending() {
        Scenario scenario = new Scenario(false);
        assertEquals("PENDING", scenario.service.get(scenario.claimId, scenario.caller).status());
    }

    private static final class Scenario {
        private final UUID claimId = UUID.randomUUID();
        private final UUID ownerId = UUID.randomUUID();
        private final UUID managerId = UUID.randomUUID();
        private final UUID managerEmployeeId = UUID.randomUUID();
        private final UUID financeId = UUID.randomUUID();
        private final UUID financeEmployeeId = UUID.randomUUID();
        private final Authentication caller = mock(Authentication.class);
        private final UserAccount manager = mock(UserAccount.class);
        private final UserAccount finance = mock(UserAccount.class);
        private final Employee employee = mock(Employee.class);
        private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        private final EmployeeService employees = mock(EmployeeService.class);
        private final AccountAccessService access = mock(AccountAccessService.class);
        private final AtomicBoolean terminal = new AtomicBoolean();
        private final ReimbursementWorkflowService service;

        private Scenario(boolean becomeTerminal) {
            service = new ReimbursementWorkflowService(new JdbcReimbursementWorkflowRepository(jdbc), employees, access, mock(ReimbursementSubmissionService.class),
                mock(ReimbursementCategoryService.class), mock(EntityManager.class), mock(AdmissionGuard.class),
                Clock.fixed(NOW, ZoneOffset.UTC));
            when(employee.getId()).thenReturn(10L);
            when(employee.getPublicId()).thenReturn(ownerId);
            when(employees.requireInternalByPublicId(ownerId)).thenReturn(employee);
            when(employees.currentOrgUnit(10L, NOW)).thenReturn(Optional.of("ENG"));
            when(manager.getId()).thenReturn(20L);
            when(manager.getPublicId()).thenReturn(managerId);
            when(manager.isActiveAt(NOW)).thenReturn(true);
            when(finance.getId()).thenReturn(30L);
            when(finance.getPublicId()).thenReturn(financeId);
            when(finance.isActiveAt(NOW)).thenReturn(true);
            when(access.requireActive(caller, NOW)).thenReturn(manager);
            when(access.findAccountByPublicId(managerId)).thenReturn(Optional.of(manager));
            when(access.findAccountByPublicId(financeId)).thenReturn(Optional.of(finance));
            when(access.ownEmployeePublicId(manager)).thenReturn(Optional.of(managerEmployeeId));
            when(access.ownEmployeePublicId(finance)).thenReturn(Optional.of(financeEmployeeId));
            when(access.hasScopedRole(20L, HrisRole.MANAGER, "ENG", NOW)).thenReturn(true);
            when(access.hasScopedRole(30L, HrisRole.FINANCE_PAYROLL, "ENG", NOW)).thenReturn(true);
            doThrow(new ResourceNotFoundException()).when(access).requireEmployeeRead(manager, ownerId, "ENG", NOW);
            when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);
            doAnswer(invocation -> {
                String sql = invocation.getArgument(0);
                RowMapper<?> mapper = invocation.getArgument(1);
                if (sql.startsWith("SELECT e.public_id")) return List.of(ownerId);
                if (sql.startsWith("SELECT document_public_id")) {
                    if (becomeTerminal) terminal.set(true);
                    return List.of();
                }
                ResultSet row = mock(ResultSet.class);
                if (sql.startsWith("SELECT id,public_id,status")) {
                    when(row.getLong(1)).thenReturn(100L);
                    when(row.getObject(2, UUID.class)).thenReturn(claimId);
                    when(row.getString(3)).thenReturn(terminal.get() ? "REJECTED" : "PENDING");
                    when(row.getInt(4)).thenReturn(1);
                    when(row.getLong(5)).thenReturn(1L);
                    when(row.getString(6)).thenReturn("TRAVEL");
                    when(row.getObject(7, LocalDate.class)).thenReturn(LocalDate.of(2026, 11, 1));
                    when(row.getLong(8)).thenReturn(100L);
                    when(row.getString(9)).thenReturn("Travel");
                    return List.of(mapper.mapRow(row, 0));
                }
                if (sql.startsWith("SELECT reviewer_account_public_id")) {
                    int step = invocation.getArgument(4);
                    when(row.getObject(1, UUID.class)).thenReturn(step == 1 ? managerId : financeId);
                    when(row.getObject(2, UUID.class)).thenReturn(step == 1 ? managerEmployeeId : financeEmployeeId);
                    return List.of(mapper.mapRow(row, 0));
                }
                throw new AssertionError("Unexpected query: " + sql);
            }).when(jdbc).query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Object>>any(), any(Object[].class));
        }
    }
}
