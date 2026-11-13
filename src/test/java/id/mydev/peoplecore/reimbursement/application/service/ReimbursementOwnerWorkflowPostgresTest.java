package id.mydev.peoplecore.reimbursement.application.service;

import id.mydev.peoplecore.reimbursement.infrastructure.persistence.JdbcReimbursementWorkflowRepository;
import id.mydev.peoplecore.document.infrastructure.persistence.JdbcVerifiedDocumentRepository;

import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.common.command.AdmissionGuard;
import id.mydev.peoplecore.document.application.service.VerifiedDocumentAccess;
import id.mydev.peoplecore.identity.application.service.AccountAccessService;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.organization.application.service.EmployeeService;
import id.mydev.peoplecore.organization.domain.model.Employee;
import id.mydev.peoplecore.reimbursement.application.command.ReimbursementModels.Action;
import id.mydev.peoplecore.reimbursement.application.command.ReimbursementModels.Draft;
import id.mydev.peoplecore.reimbursement.infrastructure.persistence.JdbcReimbursementSubmissionRepository;
import id.mydev.peoplecore.support.PostgresFixture;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("postgres")
class ReimbursementOwnerWorkflowPostgresTest {
    @Test
    void ownerCanEditAndWithdrawWhileStaleOrForeignChangesAndMissingPolicyLeaveNoPartialState() throws Exception {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            fixture.grantRuntime();
            JdbcTemplate jdbc = fixture.runtime();
            UUID employeePublicId = UUID.randomUUID();
            long employeeId = fixture.jdbc.queryForObject("INSERT INTO employees(public_id,employee_number,created_at) "
                + "VALUES (?,?,now()) RETURNING id", Long.class, employeePublicId, "OWNER-" + UUID.randomUUID());
            Employee employee = mock(Employee.class);
            when(employee.getId()).thenReturn(employeeId);
            when(employee.getPublicId()).thenReturn(employeePublicId);
            EmployeeService employees = mock(EmployeeService.class);
            when(employees.requireInternalByPublicId(employeePublicId)).thenReturn(employee);

            Authentication ownerCaller = mock(Authentication.class);
            Authentication strangerCaller = mock(Authentication.class);
            UserAccount owner = mock(UserAccount.class);
            UserAccount stranger = mock(UserAccount.class);
            when(owner.getId()).thenReturn(101L);
            when(owner.getPublicId()).thenReturn(UUID.randomUUID());
            when(stranger.getId()).thenReturn(102L);
            AccountAccessService access = mock(AccountAccessService.class);
            when(access.requireActive(eq(ownerCaller), any())).thenReturn(owner);
            when(access.requireActive(eq(strangerCaller), any())).thenReturn(stranger);
            when(access.activeRoles(eq(101L), any())).thenReturn(List.of("EMPLOYEE"));
            when(access.activeRoles(eq(102L), any())).thenReturn(List.of("EMPLOYEE"));
            when(access.ownEmployeePublicId(owner)).thenReturn(Optional.of(employeePublicId));
            when(access.ownEmployeePublicId(stranger)).thenReturn(Optional.of(UUID.randomUUID()));

            Clock clock = Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC);
            EntityManager entities = mock(EntityManager.class);
            var submissions = new ReimbursementSubmissionService(new JdbcReimbursementSubmissionRepository(jdbc), access, employees,
                new VerifiedDocumentAccess(new JdbcVerifiedDocumentRepository(jdbc)), clock, entities);
            var workflow = new ReimbursementWorkflowService(new JdbcReimbursementWorkflowRepository(jdbc), employees, access, submissions,
                mock(ReimbursementCategoryService.class), entities, mock(AdmissionGuard.class), clock);
            var transactions = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
            UUID claim = UUID.randomUUID();
            UUID evidence = UUID.randomUUID();
            LocalDate transactionDate = LocalDate.of(2026, 10, 20);
            Draft initial = new Draft(employeePublicId, "TRAVEL", transactionDate, 100, "Taxi", List.of(evidence), null);

            var created = transactions.execute(status -> workflow.create(claim, initial, ownerCaller));
            assertEquals("DRAFT", created.status());
            assertEquals(0, created.version());
            Draft edit = new Draft(employeePublicId, "TRAVEL", transactionDate, 125, "Train", List.of(evidence), 0L);
            assertThrows(ResourceNotFoundException.class, () -> transactions.execute(status -> workflow.edit(claim, edit, strangerCaller)));
            var edited = transactions.execute(status -> workflow.edit(claim, edit, ownerCaller));
            assertEquals(1, edited.version());
            assertEquals(125, edited.amount());
            assertThrows(ReimbursementConflictException.class, () -> transactions.execute(status -> workflow.edit(claim, edit, ownerCaller)));

            Action submit = new Action(employeePublicId, 1L, null, null, null, null, null);
            when(access.activeRoles(eq(101L), any())).thenReturn(List.of("MANAGER"));
            var command = new ObjectMapper().readTree("{\"employeePublicId\":\"" + employeePublicId + "\"}");
            assertThrows(ResourceNotFoundException.class, () -> workflow.verifyCommand("REIMBURSEMENT_CREATE", command, ownerCaller));
            assertThrows(ResourceNotFoundException.class, () -> workflow.verifyReplay("REIMBURSEMENT_EDIT", command, null, ownerCaller));
            assertThrows(ResourceNotFoundException.class, () -> transactions.execute(status ->
                workflow.create(UUID.randomUUID(), initial, ownerCaller)));
            assertThrows(ResourceNotFoundException.class, () -> transactions.execute(status ->
                workflow.edit(claim, new Draft(employeePublicId, "TRAVEL", transactionDate, 150, "Bus", List.of(evidence), 1L), ownerCaller)));
            assertThrows(ResourceNotFoundException.class, () -> transactions.execute(status ->
                workflow.act(claim, "SUBMIT", submit, ownerCaller)));
            assertEquals(125L, jdbc.queryForObject("SELECT amount FROM reimbursement_claim WHERE public_id=?", Long.class, claim));
            assertEquals(1L, jdbc.queryForObject("SELECT version FROM reimbursement_claim WHERE public_id=?", Long.class, claim));
            when(access.activeRoles(eq(101L), any())).thenReturn(List.of("EMPLOYEE"));
            assertThrows(IllegalArgumentException.class, () -> transactions.execute(status -> workflow.act(claim, "SUBMIT", submit, ownerCaller)));
            assertEquals("DRAFT", jdbc.queryForObject("SELECT status FROM reimbursement_claim WHERE public_id=?", String.class, claim));
            assertEquals(0, jdbc.queryForObject("SELECT count(r.id) FROM reimbursement_revision r "
                + "JOIN reimbursement_claim c ON c.id=r.claim_id WHERE c.public_id=?", Integer.class, claim));

            var withdrawn = transactions.execute(status -> workflow.act(claim, "WITHDRAW", submit, ownerCaller));
            assertEquals("WITHDRAWN", withdrawn.status());
            assertEquals(2, withdrawn.version());
            assertThrows(ReimbursementConflictException.class, () -> transactions.execute(status ->
                workflow.edit(claim, new Draft(employeePublicId, "TRAVEL", transactionDate, 125, "Train", List.of(evidence), 2L), ownerCaller)));
            assertEquals(3, jdbc.queryForObject("SELECT count(h.id) FROM reimbursement_history h "
                + "JOIN reimbursement_claim c ON c.id=h.claim_id WHERE c.public_id=?", Integer.class, claim));
        }
    }
}
