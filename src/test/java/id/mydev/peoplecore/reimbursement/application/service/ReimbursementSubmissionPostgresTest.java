package id.mydev.peoplecore.reimbursement.application.service;

import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.common.command.AdmissionGuard;
import id.mydev.peoplecore.document.application.service.VerifiedDocumentAccess;
import id.mydev.peoplecore.identity.application.service.AccountAccessService;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.identity.domain.model.HrisRole;
import id.mydev.peoplecore.organization.application.service.EmployeeService;
import id.mydev.peoplecore.organization.domain.model.Employee;
import id.mydev.peoplecore.support.PostgresFixture;
import id.mydev.peoplecore.reimbursement.application.command.ReimbursementModels.Action;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

@Tag("postgres")
class ReimbursementSubmissionPostgresTest {
    @Test
    void submissionPinsVerifiedOwnedEvidenceAndMissingEvidenceRollsBack() throws Exception {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            fixture.grantRuntime();
            UUID employeePublicId = UUID.randomUUID();
            long employeeId = fixture.jdbc.queryForObject("INSERT INTO employees(public_id,employee_number,created_at) VALUES (?,?,now()) RETURNING id",
                Long.class, employeePublicId, "REIM-" + UUID.randomUUID());
            UUID documentId = verifiedDocument(fixture, employeeId);
            fixture.jdbc.update("INSERT INTO reimbursement_category_revision(public_id,category_code,effective_from,claim_limit,created_at) "
                + "VALUES (gen_random_uuid(),'TRAVEL','2026-01-01',1000,now())");
            UUID claim = draft(fixture, employeeId, documentId);
            UUID invalid = draft(fixture, employeeId, UUID.randomUUID());
            long otherEmployeeId = fixture.jdbc.queryForObject("INSERT INTO employees(public_id,employee_number,created_at) VALUES (?,?,now()) RETURNING id",
                Long.class, UUID.randomUUID(), "OTHER-" + UUID.randomUUID());
            UUID foreignEvidence = draft(fixture, employeeId, verifiedDocument(fixture, otherEmployeeId));

            Employee employee = mock(Employee.class);
            when(employee.getId()).thenReturn(employeeId);
            when(employee.getPublicId()).thenReturn(employeePublicId);
            when(employee.getEmploymentStartDate()).thenReturn(LocalDate.of(2025, 1, 1));
            EmployeeService employees = mock(EmployeeService.class);
            when(employees.requireInternalByPublicId(employeePublicId)).thenReturn(employee);
            when(employees.currentOrgUnit(eq(employeeId), any())).thenReturn(Optional.of("HQ"));
            when(employees.currentManagerPublicId(eq(employeeId), any())).thenReturn(Optional.empty());
            Authentication caller = mock(Authentication.class);
            UserAccount account = mock(UserAccount.class);
            when(account.getId()).thenReturn(10L);
            AccountAccessService access = mock(AccountAccessService.class);
            when(access.requireActive(eq(caller), any())).thenReturn(account);
            when(access.activeRoles(eq(10L), any())).thenReturn(List.of("EMPLOYEE"));
            when(access.ownEmployeePublicId(account)).thenReturn(Optional.of(employeePublicId));
            var service = new ReimbursementSubmissionService(fixture.jdbc, access, employees,
                new VerifiedDocumentAccess(fixture.jdbc), Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC),
                mock(EntityManager.class));
            var transactions = new TransactionTemplate(new DataSourceTransactionManager(fixture.jdbc.getDataSource()));

            transactions.executeWithoutResult(status -> service.submit(claim, employeePublicId, 0, caller));
            assertEquals("PENDING", fixture.jdbc.queryForObject("SELECT status FROM reimbursement_claim WHERE public_id=?", String.class, claim));
            assertEquals(1, fixture.jdbc.queryForObject("SELECT count(e.id) FROM reimbursement_evidence e JOIN reimbursement_claim c ON c.id=e.claim_id "
                + "JOIN reimbursement_revision r ON r.claim_id=c.id AND r.revision=e.revision WHERE c.public_id=? AND r.sealed", Integer.class, claim));
            assertThrows(ResourceNotFoundException.class,
                () -> transactions.executeWithoutResult(status -> service.submit(invalid, employeePublicId, 0, caller)));
            assertEquals("DRAFT", fixture.jdbc.queryForObject("SELECT status FROM reimbursement_claim WHERE public_id=?", String.class, invalid));
            assertThrows(ResourceNotFoundException.class,
                () -> transactions.executeWithoutResult(status -> service.submit(foreignEvidence, employeePublicId, 0, caller)));
            assertEquals("DRAFT", fixture.jdbc.queryForObject("SELECT status FROM reimbursement_claim WHERE public_id=?", String.class, foreignEvidence));
            assertEquals(0, fixture.jdbc.queryForObject("SELECT count(r.id) FROM reimbursement_revision r "
                + "JOIN reimbursement_claim c ON c.id=r.claim_id WHERE c.public_id=?", Integer.class, foreignEvidence));
            JdbcTemplate runtime = fixture.runtime();
            long claimId = fixture.jdbc.queryForObject("SELECT id FROM reimbursement_claim WHERE public_id=?", Long.class, claim);
            long invalidId = fixture.jdbc.queryForObject("SELECT id FROM reimbursement_claim WHERE public_id=?", Long.class, invalid);
            long categoryId = fixture.jdbc.queryForObject("SELECT id FROM reimbursement_category_revision WHERE category_code='TRAVEL'", Long.class);
            long documentVersion = fixture.jdbc.queryForObject("SELECT v.id FROM document_version v JOIN private_document d ON d.id=v.document_id WHERE d.public_id=?", Long.class, documentId);
            assertThrows(DataAccessException.class, () -> runtime.update("INSERT INTO reimbursement_revision(claim_id,revision,category_revision_id,transaction_date,amount,description,org_unit,submitted_at,sealed) "
                + "VALUES (?,1,?,'2026-10-01',100,'Trip','HQ',now(),TRUE)", invalidId, categoryId));
            assertThrows(DataAccessException.class, () -> runtime.update("INSERT INTO reimbursement_evidence(claim_id,revision,document_version_id) VALUES (?,1,?)", claimId, documentVersion));
            assertThrows(DataAccessException.class, () -> runtime.update("INSERT INTO reimbursement_decision(claim_id,revision,step,reviewer_account_public_id,reviewer_employee_public_id,outcome,created_at) "
                + "VALUES (?,1,1,?,?,'APPROVE',now())", claimId, UUID.randomUUID(), UUID.randomUUID()));
            assertThrows(DataAccessException.class, () -> runtime.update("INSERT INTO reimbursement_payment(public_id,claim_id,revision,amount,paid_date,payment_reference,created_by,created_at) "
                + "VALUES (gen_random_uuid(),?,1,100,'2026-11-01','ref',gen_random_uuid(),now())", claimId));
            assertThrows(DataAccessException.class, () -> runtime.update("UPDATE reimbursement_claim SET status='APPROVED',version=version+1 WHERE id=?", claimId));
            verifyReviewAndPayment(fixture, employeePublicId, claim, caller, account, employees, access, service, transactions);
        }
    }

    private static void verifyReviewAndPayment(PostgresFixture fixture, UUID employeeId, UUID claim, Authentication ownerCaller,
                                               UserAccount owner, EmployeeService employees, AccountAccessService access,
                                               ReimbursementSubmissionService submissions, TransactionTemplate transactions) throws Exception {
        Authentication hrCaller = mock(Authentication.class);
        Authentication managerCaller = mock(Authentication.class);
        Authentication financeCaller = mock(Authentication.class);
        UserAccount hr = mock(UserAccount.class);
        UserAccount manager = mock(UserAccount.class);
        UserAccount finance = mock(UserAccount.class);
        UUID managerAccount = UUID.randomUUID();
        UUID financeAccount = UUID.randomUUID();
        UUID ownerAccount = UUID.randomUUID();
        UUID managerEmployee = UUID.randomUUID();
        UUID financeEmployee = UUID.randomUUID();
        when(hr.getId()).thenReturn(11L);
        when(hr.getPublicId()).thenReturn(UUID.randomUUID());
        when(manager.getId()).thenReturn(12L);
        when(manager.getPublicId()).thenReturn(managerAccount);
        when(manager.isActiveAt(any())).thenReturn(true);
        when(finance.getId()).thenReturn(13L);
        when(finance.getPublicId()).thenReturn(financeAccount);
        when(finance.isActiveAt(any())).thenReturn(true);
        when(owner.getPublicId()).thenReturn(ownerAccount);
        when(owner.isActiveAt(any())).thenReturn(true);
        when(access.requireHrOperation(eq(hrCaller), any())).thenReturn(hr);
        when(access.requireActive(eq(managerCaller), any())).thenReturn(manager);
        when(access.requireActive(eq(financeCaller), any())).thenReturn(finance);
        when(access.findAccountByPublicId(managerAccount)).thenReturn(Optional.of(manager));
        when(access.findAccountByPublicId(financeAccount)).thenReturn(Optional.of(finance));
        when(access.findAccountByPublicId(ownerAccount)).thenReturn(Optional.of(owner));
        when(access.ownEmployeePublicId(manager)).thenReturn(Optional.of(managerEmployee));
        when(access.ownEmployeePublicId(finance)).thenReturn(Optional.of(financeEmployee));
        when(access.hasScopedRole(eq(12L), eq(HrisRole.MANAGER), eq("HQ"), any())).thenReturn(true);
        when(access.hasScopedRole(eq(13L), eq(HrisRole.FINANCE_PAYROLL), eq("HQ"), any())).thenReturn(true);
        doThrow(new ResourceNotFoundException()).when(access).requireEmployeeRead(eq(manager), eq(employeeId), eq("HQ"), any());
        Clock clock = Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC);
        var workflow = new ReimbursementWorkflowService(fixture.jdbc, employees, access, submissions,
            mock(ReimbursementCategoryService.class), mock(EntityManager.class), mock(AdmissionGuard.class), clock);

        verifyRouteRetry(fixture, employeeId, claim, ownerCaller, hrCaller, manager, managerEmployee,
            managerAccount, employees, access, submissions, workflow, transactions);
        verifySubmissionManagerEligibility(fixture, employeeId, claim, ownerCaller, manager, managerEmployee,
            managerAccount, employees, access, submissions, transactions);
        verifyRejection(fixture, employeeId, claim, ownerCaller, hrCaller, managerCaller, managerAccount,
            financeAccount, employees, submissions, workflow, transactions);
        assertThrows(ReimbursementConflictException.class, () -> transactions.executeWithoutResult(status -> workflow.act(claim, "APPROVE",
            action(employeeId, 1, null, null, null, null, null), ownerCaller)));
        assertThrows(IllegalArgumentException.class, () -> transactions.executeWithoutResult(status -> workflow.act(claim, "REJECT",
            action(employeeId, 1, " ", null, null, null, null), managerCaller)));
        assertThrows(IllegalArgumentException.class, () -> transactions.executeWithoutResult(status -> workflow.act(claim, "RETRY_ROUTE",
            action(employeeId, 1, " ", null, null, null, null), hrCaller)));
        assertThrows(ReimbursementConflictException.class, () -> transactions.executeWithoutResult(status -> workflow.act(claim, "RECORD_PAYMENT",
            action(employeeId, 1, null, null, null, LocalDate.of(2026, 11, 1), "premature"), financeCaller)));
        assertThrows(ReimbursementConflictException.class, () -> transactions.executeWithoutResult(status -> workflow.act(claim, "REASSIGN",
            action(employeeId, 1, "assign", 1, ownerAccount, null, null), hrCaller)));
        transactions.executeWithoutResult(status -> workflow.act(claim, "REASSIGN", action(employeeId, 1, "assign", 1, managerAccount, null, null), hrCaller));
        when(access.hasScopedRole(eq(12L), eq(HrisRole.FINANCE_PAYROLL), eq("HQ"), any())).thenReturn(true);
        assertThrows(ReimbursementConflictException.class, () -> transactions.executeWithoutResult(status -> workflow.act(claim, "REASSIGN",
            action(employeeId, 2, "assign same reviewer", 2, managerAccount, null, null), hrCaller)));
        transactions.executeWithoutResult(status -> workflow.act(claim, "REASSIGN", action(employeeId, 2, "assign", 2, financeAccount, null, null), hrCaller));
        assertThrows(ResourceNotFoundException.class, () -> transactions.executeWithoutResult(status -> workflow.act(claim, "APPROVE",
            action(employeeId, 3, null, null, null, null, null), financeCaller)));
        transactions.executeWithoutResult(status -> workflow.act(claim, "RETURN", action(employeeId, 3, "Needs correction", null, null, null, null), managerCaller));
        assertThrows(DataAccessException.class, () -> fixture.runtime().update(
            "UPDATE reimbursement_claim SET status='PENDING',version=version+1 WHERE public_id=?", claim));
        transactions.executeWithoutResult(status -> workflow.act(claim, "RESUBMIT", action(employeeId, 4, null, null, null, null, null), ownerCaller));
        transactions.executeWithoutResult(status -> workflow.act(claim, "REASSIGN", action(employeeId, 5, "assign", 1, managerAccount, null, null), hrCaller));
        transactions.executeWithoutResult(status -> workflow.act(claim, "REASSIGN", action(employeeId, 6, "assign", 2, financeAccount, null, null), hrCaller));
        when(access.hasScopedRole(eq(12L), eq(HrisRole.MANAGER), eq("HQ"), any())).thenReturn(false);
        assertThrows(ReimbursementConflictException.class, () -> transactions.executeWithoutResult(status -> workflow.act(claim, "APPROVE",
            action(employeeId, 7, null, null, null, null, null), managerCaller)));
        assertEquals(0, fixture.jdbc.queryForObject("SELECT count(d.id) FROM reimbursement_decision d "
            + "JOIN reimbursement_claim c ON c.id=d.claim_id WHERE c.public_id=? AND d.revision=2", Integer.class, claim));
        when(access.hasScopedRole(eq(12L), eq(HrisRole.MANAGER), eq("HQ"), any())).thenReturn(true);
        var decisionPayload = new ObjectMapper().readTree("{\"employeePublicId\":\"" + employeeId
            + "\",\"claimPublicId\":\"" + claim + "\"}");
        workflow.verifyCommand("REIMBURSEMENT_APPROVE", decisionPayload, managerCaller);
        raceReviewDecisions(workflow, transactions, employeeId, claim, managerCaller, financeCaller);
        assertThrows(ReimbursementConflictException.class, () -> transactions.executeWithoutResult(status -> workflow.act(claim, "REASSIGN",
            action(employeeId, 8, "already decided", 1, managerAccount, null, null), hrCaller)));
        assertThrows(ResourceNotFoundException.class,
            () -> workflow.verifyReplay("REIMBURSEMENT_APPROVE", decisionPayload, null, managerCaller));
        transactions.executeWithoutResult(status -> workflow.act(claim, "APPROVE", action(employeeId, 8, null, null, null, null, null), financeCaller));
        assertThrows(IllegalArgumentException.class, () -> transactions.executeWithoutResult(status -> workflow.act(claim, "RECORD_PAYMENT",
            action(employeeId, 9, null, null, null, null, "missing date"), financeCaller)));
        assertThrows(DataAccessException.class, () -> fixture.runtime().update(
            "UPDATE reimbursement_claim SET status='PAID',version=version+1 WHERE public_id=?", claim));
        LocalDate paid = LocalDate.of(2026, 11, 1);
        racePayment(workflow, transactions, employeeId, claim, financeCaller, paid);
        assertThrows(IllegalArgumentException.class, () -> transactions.executeWithoutResult(status -> workflow.act(claim, "CORRECT_PAYMENT",
            action(employeeId, 10, " ", null, null, paid, "missing reason"), financeCaller)));
        assertThrows(ReimbursementConflictException.class, () -> transactions.executeWithoutResult(status -> workflow.act(claim, "WITHDRAW",
            action(employeeId, 10, null, null, null, null, null), ownerCaller)));
        assertThrows(ReimbursementConflictException.class, () -> transactions.executeWithoutResult(status -> workflow.act(claim, "RECORD_PAYMENT",
            action(employeeId, 9, null, null, null, paid, "pay-1"), financeCaller)));
        transactions.executeWithoutResult(status -> workflow.act(claim, "CORRECT_PAYMENT", action(employeeId, 10, "fix", null, null, paid, "pay-2"), financeCaller));
        assertEquals(1, fixture.jdbc.queryForObject("SELECT count(id) FROM reimbursement_payment", Integer.class));
        assertEquals(1, fixture.jdbc.queryForObject("SELECT count(id) FROM reimbursement_payment_correction", Integer.class));
        assertEquals("PAID", fixture.jdbc.queryForObject("SELECT status FROM reimbursement_claim WHERE public_id=?", String.class, claim));
        raceCorrections(workflow, transactions, employeeId, claim, financeCaller, paid);
        assertEquals(1, fixture.jdbc.queryForObject("SELECT count(id) FROM reimbursement_payment", Integer.class));
        assertEquals(2, fixture.jdbc.queryForObject("SELECT count(id) FROM reimbursement_payment_correction", Integer.class));
        var first = transactions.execute(status -> workflow.history(claim, 2, null, financeCaller));
        assertEquals(2, first.items().size());
        assertEquals("CORRECT_PAYMENT", first.items().getFirst().action());
        assertNotNull(first.nextCursor());
        var second = transactions.execute(status -> workflow.history(claim, 2, first.nextCursor(), financeCaller));
        assertEquals(2, second.items().size());
        assertTrue(second.items().stream().noneMatch(item -> item.publicId().equals(first.items().getFirst().publicId())));
        assertThrows(ResourceNotFoundException.class, () -> transactions.execute(status ->
            workflow.history(claim, 2, UUID.randomUUID(), financeCaller)));
        Authentication strangerCaller = mock(Authentication.class);
        UserAccount stranger = mock(UserAccount.class);
        when(stranger.getId()).thenReturn(14L);
        when(access.requireActive(eq(strangerCaller), any())).thenReturn(stranger);
        doThrow(new ResourceNotFoundException()).when(access).requireEmployeeRead(eq(stranger), eq(employeeId), eq("HQ"), any());
        assertThrows(ResourceNotFoundException.class, () -> transactions.execute(status ->
            workflow.history(claim, 2, null, strangerCaller)));
        long claimId = fixture.jdbc.queryForObject("SELECT id FROM reimbursement_claim WHERE public_id=?", Long.class, claim);
        fixture.runtime().update("UPDATE reimbursement_review_assignment SET active=FALSE WHERE claim_id=? AND revision=1 AND step=2 AND active", claimId);
        assertThrows(DataAccessException.class, () -> fixture.runtime().update("INSERT INTO reimbursement_review_assignment(claim_id,revision,step,created_at) VALUES (?,1,2,now())", claimId));
    }

    private static void verifyRejection(PostgresFixture fixture, UUID employeePublicId, UUID existingClaim,
                                        Authentication owner, Authentication hr, Authentication manager,
                                        UUID managerAccount, UUID financeAccount, EmployeeService employees,
                                        ReimbursementSubmissionService submissions, ReimbursementWorkflowService workflow,
                                        TransactionTemplate transactions) {
        long employeeId = employees.requireInternalByPublicId(employeePublicId).getId();
        UUID document = fixture.jdbc.queryForObject("SELECT de.document_public_id FROM reimbursement_draft_evidence de "
            + "JOIN reimbursement_claim c ON c.id=de.claim_id WHERE c.public_id=?", UUID.class, existingClaim);
        UUID claim = draft(fixture, employeeId, document);
        transactions.executeWithoutResult(status -> submissions.submit(claim, employeePublicId, 0, owner));
        assertThrows(IllegalArgumentException.class, () -> transactions.executeWithoutResult(status -> workflow.act(claim, "REASSIGN",
            action(employeePublicId, 1, "Assign", 0, managerAccount, null, null), hr)));
        assertThrows(IllegalArgumentException.class, () -> transactions.executeWithoutResult(status -> workflow.act(claim, "REASSIGN",
            action(employeePublicId, 1, "Assign", null, managerAccount, null, null), hr)));
        assertThrows(IllegalArgumentException.class, () -> transactions.executeWithoutResult(status -> workflow.act(claim, "REASSIGN",
            action(employeePublicId, 1, "Assign", 3, managerAccount, null, null), hr)));
        assertThrows(IllegalArgumentException.class, () -> transactions.executeWithoutResult(status -> workflow.act(claim, "REASSIGN",
            action(employeePublicId, 1, "Assign", 1, null, null, null), hr)));
        assertThrows(IllegalArgumentException.class, () -> transactions.executeWithoutResult(status -> workflow.act(claim, "REASSIGN",
            action(employeePublicId, 1, " ", 1, managerAccount, null, null), hr)));
        transactions.executeWithoutResult(status -> workflow.act(claim, "REASSIGN",
            action(employeePublicId, 1, "Assign manager", 1, managerAccount, null, null), hr));
        transactions.executeWithoutResult(status -> workflow.act(claim, "REASSIGN",
            action(employeePublicId, 2, "Assign finance", 2, financeAccount, null, null), hr));
        var rejected = transactions.execute(status -> workflow.act(claim, "REJECT",
            action(employeePublicId, 3, "Ineligible expense", null, null, null, null), manager));
        assertEquals("REJECTED", rejected.status());
        assertEquals(4, rejected.version());
        assertEquals(1, fixture.jdbc.queryForObject("SELECT count(d.id) FROM reimbursement_decision d "
            + "JOIN reimbursement_claim c ON c.id=d.claim_id WHERE c.public_id=? AND d.outcome='REJECT' "
            + "AND d.reason='Ineligible expense'", Integer.class, claim));
        assertThrows(ReimbursementConflictException.class, () -> transactions.executeWithoutResult(status -> workflow.act(claim, "APPROVE",
            action(employeePublicId, 4, null, null, null, null, null), manager)));
    }

    private static void verifySubmissionManagerEligibility(PostgresFixture fixture, UUID employeePublicId, UUID existingClaim,
                                                           Authentication owner, UserAccount manager, UUID managerEmployee,
                                                           UUID managerAccount, EmployeeService employees, AccountAccessService access,
                                                           ReimbursementSubmissionService submissions, TransactionTemplate transactions) {
        long employeeId = employees.requireInternalByPublicId(employeePublicId).getId();
        UUID document = fixture.jdbc.queryForObject("SELECT de.document_public_id FROM reimbursement_draft_evidence de "
            + "JOIN reimbursement_claim c ON c.id=de.claim_id WHERE c.public_id=?", UUID.class, existingClaim);
        when(employees.currentManagerPublicId(eq(employeeId), any())).thenReturn(Optional.of(managerEmployee));
        when(access.accountBoundToEmployee(managerEmployee)).thenReturn(Optional.of(manager));

        when(manager.isActiveAt(any())).thenReturn(false);
        UUID inactiveClaim = draft(fixture, employeeId, document);
        transactions.executeWithoutResult(status -> submissions.submit(inactiveClaim, employeePublicId, 0, owner));
        assertNull(assignedManager(fixture, inactiveClaim));

        when(manager.isActiveAt(any())).thenReturn(true);
        when(access.hasScopedRole(eq(12L), eq(HrisRole.MANAGER), eq("HQ"), any())).thenReturn(false);
        UUID outOfScopeClaim = draft(fixture, employeeId, document);
        transactions.executeWithoutResult(status -> submissions.submit(outOfScopeClaim, employeePublicId, 0, owner));
        assertNull(assignedManager(fixture, outOfScopeClaim));

        when(access.hasScopedRole(eq(12L), eq(HrisRole.MANAGER), eq("HQ"), any())).thenReturn(true);
        UUID eligibleClaim = draft(fixture, employeeId, document);
        transactions.executeWithoutResult(status -> submissions.submit(eligibleClaim, employeePublicId, 0, owner));
        assertEquals(managerAccount, assignedManager(fixture, eligibleClaim));
        when(employees.currentManagerPublicId(eq(employeeId), any())).thenReturn(Optional.empty());
    }

    private static UUID assignedManager(PostgresFixture fixture, UUID claim) {
        return fixture.jdbc.queryForObject("SELECT a.reviewer_account_public_id FROM reimbursement_review_assignment a "
            + "JOIN reimbursement_claim c ON c.id=a.claim_id WHERE c.public_id=? AND a.step=1 AND a.active", UUID.class, claim);
    }

    private static void verifyRouteRetry(PostgresFixture fixture, UUID employeePublicId, UUID existingClaim,
                                         Authentication owner, Authentication hr, UserAccount manager,
                                         UUID managerEmployee, UUID managerAccount, EmployeeService employees,
                                         AccountAccessService access, ReimbursementSubmissionService submissions,
                                         ReimbursementWorkflowService workflow, TransactionTemplate transactions) {
        long employeeId = employees.requireInternalByPublicId(employeePublicId).getId();
        UUID document = fixture.jdbc.queryForObject("SELECT de.document_public_id FROM reimbursement_draft_evidence de "
            + "JOIN reimbursement_claim c ON c.id=de.claim_id WHERE c.public_id=?", UUID.class, existingClaim);
        UUID claim = draft(fixture, employeeId, document);
        transactions.executeWithoutResult(status -> submissions.submit(claim, employeePublicId, 0, owner));

        when(employees.currentManagerPublicId(eq(employeeId), any())).thenReturn(Optional.of(managerEmployee));
        when(access.accountBoundToEmployee(managerEmployee)).thenReturn(Optional.of(manager));
        var retried = transactions.execute(status -> workflow.act(claim, "RETRY_ROUTE",
            action(employeePublicId, 1, "Manager is available", null, null, null, null), hr));
        assertEquals(2, retried.version());
        assertEquals(managerAccount, fixture.jdbc.queryForObject("SELECT a.reviewer_account_public_id "
            + "FROM reimbursement_review_assignment a JOIN reimbursement_claim c ON c.id=a.claim_id "
            + "WHERE c.public_id=? AND a.step=1 AND a.active", UUID.class, claim));
        assertEquals(1, fixture.jdbc.queryForObject("SELECT count(h.id) FROM reimbursement_history h "
            + "JOIN reimbursement_claim c ON c.id=h.claim_id WHERE c.public_id=? AND h.action='RETRY_ROUTE' "
            + "AND h.reason='Manager is available'", Integer.class, claim));
        when(access.hasScopedRole(eq(12L), eq(HrisRole.MANAGER), eq("HQ"), any())).thenReturn(false);
        transactions.executeWithoutResult(status -> workflow.act(claim, "RETRY_ROUTE",
            action(employeePublicId, 2, "Manager scope revoked", null, null, null, null), hr));
        assertNull(assignedManager(fixture, claim));
        when(access.hasScopedRole(eq(12L), eq(HrisRole.MANAGER), eq("HQ"), any())).thenReturn(true);
        transactions.executeWithoutResult(status -> workflow.act(claim, "RETRY_ROUTE",
            action(employeePublicId, 3, "Manager scope restored", null, null, null, null), hr));
        assertEquals(managerAccount, assignedManager(fixture, claim));
        when(employees.currentManagerPublicId(eq(employeeId), any())).thenReturn(Optional.empty());
    }

    private static void raceReviewDecisions(ReimbursementWorkflowService workflow, TransactionTemplate transactions,
                                            UUID employeeId, UUID claim, Authentication manager, Authentication finance)
        throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var managerResult = executor.submit(() -> raceDecision(workflow, transactions, employeeId, claim, manager, start));
            var financeResult = executor.submit(() -> raceDecision(workflow, transactions, employeeId, claim, finance, start));
            start.countDown();
            assertTrue(managerResult.get());
            assertEquals(false, financeResult.get());
        }
    }

    private static boolean raceDecision(ReimbursementWorkflowService workflow, TransactionTemplate transactions,
                                        UUID employeeId, UUID claim, Authentication caller, CountDownLatch start)
        throws InterruptedException {
        start.await();
        try {
            transactions.executeWithoutResult(status -> workflow.act(claim, "APPROVE",
                action(employeeId, 7, null, null, null, null, null), caller));
            return true;
        } catch (ResourceNotFoundException | ReimbursementConflictException exception) {
            return false;
        }
    }

    private static void racePayment(ReimbursementWorkflowService workflow, TransactionTemplate transactions,
                                    UUID employeeId, UUID claim, Authentication caller, LocalDate paid) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> payment = () -> {
                start.await();
                try {
                    transactions.executeWithoutResult(status -> workflow.act(claim, "RECORD_PAYMENT",
                        action(employeeId, 9, null, null, null, paid, UUID.randomUUID().toString()), caller));
                    return true;
                } catch (ReimbursementConflictException exception) {
                    return false;
                }
            };
            var first = executor.submit(payment);
            var second = executor.submit(payment);
            start.countDown();
            assertEquals(1, (first.get() ? 1 : 0) + (second.get() ? 1 : 0));
        }
    }

    private static void raceCorrections(ReimbursementWorkflowService workflow, TransactionTemplate transactions,
                                        UUID employeeId, UUID claim, Authentication caller, LocalDate paid) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> correction = () -> {
                start.await();
                try {
                    transactions.executeWithoutResult(status -> workflow.act(claim, "CORRECT_PAYMENT",
                        action(employeeId, 11, "concurrent correction", null, null, paid, UUID.randomUUID().toString()), caller));
                    return true;
                } catch (ReimbursementConflictException exception) {
                    return false;
                }
            };
            var first = executor.submit(correction);
            var second = executor.submit(correction);
            start.countDown();
            assertEquals(1, (first.get() ? 1 : 0) + (second.get() ? 1 : 0));
        }
    }

    private static Action action(UUID employee, long version, String reason, Integer step, UUID reviewer, LocalDate paid, String reference) {
        return new Action(employee, version, reason, step, reviewer, paid, reference);
    }

    private static UUID draft(PostgresFixture fixture, long employee, UUID document) {
        UUID id = UUID.randomUUID();
        long internal = fixture.jdbc.queryForObject("INSERT INTO reimbursement_claim(public_id,employee_id,status,category_code,transaction_date,amount,description,created_at) "
            + "VALUES (?,?,'DRAFT','TRAVEL','2026-10-01',100,'Trip',now()) RETURNING id", Long.class, id, employee);
        fixture.jdbc.update("INSERT INTO reimbursement_draft_evidence(claim_id,document_public_id) VALUES (?,?)", internal, document);
        return id;
    }

    private static UUID verifiedDocument(PostgresFixture fixture, long employee) {
        UUID id = UUID.randomUUID();
        long document = fixture.jdbc.queryForObject("INSERT INTO private_document(public_id,employee_id,purpose,status,file_name,declared_media_type,declared_size,current_generation,created_at,updated_at) "
            + "VALUES (?,?,'CLAIM_EVIDENCE','VERIFYING','test.pdf','application/pdf',1,1,now(),now()) RETURNING id", Long.class, id, employee);
        long upload = fixture.jdbc.queryForObject("INSERT INTO document_upload_attempt(public_id,document_id,generation,staging_key,byte_size,status,created_at) "
            + "VALUES (gen_random_uuid(),?,1,?,1,'RESERVED',now()) RETURNING id", Long.class, document, "staging/" + UUID.randomUUID());
        fixture.jdbc.update("UPDATE document_upload_attempt SET status='COMPLETED',object_etag='test',completed_at=now() WHERE id=?", upload);
        fixture.jdbc.update("UPDATE private_document SET current_upload_id=?,current_upload_generation=1 WHERE id=?", upload, document);
        long attempt = fixture.jdbc.queryForObject("INSERT INTO document_verification_attempt(public_id,document_id,upload_attempt_id,generation,candidate_key,status,created_at) "
            + "VALUES (gen_random_uuid(),?,?,1,?,'QUEUED',now()) RETURNING id", Long.class, document, upload, "candidates/" + UUID.randomUUID());
        UUID token = UUID.randomUUID();
        fixture.jdbc.update("UPDATE document_verification_attempt SET status='CLAIMED',lease_token=?,lease_expires_at=now()+interval '5 minutes' WHERE id=?", token, attempt);
        fixture.jdbc.update("UPDATE document_verification_attempt SET copy_started_at=now() WHERE id=?", attempt);
        fixture.jdbc.update("UPDATE document_verification_attempt SET copy_completed_at=now() WHERE id=?", attempt);
        fixture.jdbc.update("UPDATE document_verification_attempt SET status='CLEAN',completed_at=now() WHERE id=?", attempt);
        long version = fixture.jdbc.queryForObject("INSERT INTO document_version(public_id,document_id,attempt_id,lease_token,media_type,byte_size,sha256,scanner_version,verified_at) "
            + "VALUES (gen_random_uuid(),?,?,?,'application/pdf',1,repeat('a',64),'test',now()) RETURNING id", Long.class, document, attempt, token);
        fixture.jdbc.update("UPDATE private_document SET status='VERIFIED',current_version_id=? WHERE id=?", version, document);
        return id;
    }
}
