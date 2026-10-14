package id.mydev.peoplecore.organization.application.service;

import id.mydev.peoplecore.approval.application.command.ApprovalPayloads.RulePayload;
import id.mydev.peoplecore.approval.application.service.ApprovalRuleService;
import id.mydev.peoplecore.common.api.InvalidPaginationException;
import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.identity.domain.model.AccountBinding;
import id.mydev.peoplecore.identity.domain.model.HrisRole;
import id.mydev.peoplecore.identity.domain.model.RoleAssignment;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.identity.domain.repository.AccountBindingRepository;
import id.mydev.peoplecore.identity.domain.repository.RoleAssignmentRepository;
import id.mydev.peoplecore.identity.domain.repository.UserAccountRepository;
import id.mydev.peoplecore.organization.application.command.ProfilePayloads.ChangePayload;
import id.mydev.peoplecore.organization.application.command.ProfileResults.ContactChange;
import id.mydev.peoplecore.organization.application.command.ProfileResults.ContactCorrectionResult;
import id.mydev.peoplecore.organization.application.command.ProfileResults.ContactProfileResult;
import id.mydev.peoplecore.organization.application.command.ProfileResults.ProposalDetailResult;
import id.mydev.peoplecore.organization.application.command.ProfileResults.ProposalHistoryResult;
import id.mydev.peoplecore.organization.application.command.ProfileResults.ProposalResult;
import id.mydev.peoplecore.organization.domain.exception.ProfileConflictException;
import id.mydev.peoplecore.organization.domain.exception.ProfileInvalidStateException;
import id.mydev.peoplecore.organization.domain.exception.ProfileNoChangeException;
import id.mydev.peoplecore.organization.domain.exception.ProfileOpenProposalException;
import id.mydev.peoplecore.organization.domain.exception.ProfileRemovalDisabledException;
import id.mydev.peoplecore.organization.domain.exception.ProfileValidationException;
import id.mydev.peoplecore.organization.domain.model.Employee;
import id.mydev.peoplecore.organization.domain.repository.EmployeeRepository;
import id.mydev.peoplecore.support.JwtAuthFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "peoplecore.organization.allowed-units=ENG,FIN,OPS"
})
@Transactional
class ProfileLifecycleServiceTest {

    static final String ISSUER = "https://idp.example";
    static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired ProfileProposalService proposals;
    @Autowired ApprovalRuleService rules;
    @Autowired EmployeeService employees;
    @Autowired UserAccountRepository accountRepository;
    @Autowired RoleAssignmentRepository roleRepository;
    @Autowired AccountBindingRepository bindingRepository;
    @Autowired EmployeeRepository employeeRepository;

    private Authentication hrCaller;
    private Authentication scopedCaller;
    private Authentication reviewerCaller;
    private Authentication memberCaller;
    private Authentication strangerCaller;
    private UUID reviewerAccountId;
    private UUID memberEmployeeId;
    private UUID strangerEmployeeId;

    @BeforeEach
    void seedAccess() {
        UserAccount hr = accountRepository.save(new UserAccount(UUID.randomUUID(), ISSUER, "hr-1", NOW));
        roleRepository.save(new RoleAssignment(hr.getId(), HrisRole.HR_ADMIN, null, NOW.minusSeconds(60), NOW));
        hrCaller = JwtAuthFixture.token(ISSUER, "hr-1");

        UserAccount scoped = accountRepository.save(new UserAccount(UUID.randomUUID(), ISSUER, "hr-eng", NOW));
        roleRepository.save(new RoleAssignment(scoped.getId(), HrisRole.HR_ADMIN, "ENG", NOW.minusSeconds(60), NOW));
        scopedCaller = JwtAuthFixture.token(ISSUER, "hr-eng");

        UserAccount reviewer = accountRepository.save(new UserAccount(UUID.randomUUID(), ISSUER, "rev-1", NOW));
        roleRepository.save(new RoleAssignment(reviewer.getId(), HrisRole.HR_ADMIN, "ENG", NOW.minusSeconds(60), NOW));
        reviewerAccountId = reviewer.getPublicId();
        reviewerCaller = JwtAuthFixture.token(ISSUER, "rev-1");

        UserAccount member = accountRepository.save(new UserAccount(UUID.randomUUID(), ISSUER, "emp-1", NOW));
        roleRepository.save(new RoleAssignment(member.getId(), HrisRole.EMPLOYEE, null, NOW.minusSeconds(60), NOW));
        memberCaller = JwtAuthFixture.token(ISSUER, "emp-1");

        UserAccount stranger = accountRepository.save(new UserAccount(UUID.randomUUID(), ISSUER, "emp-2", NOW));
        roleRepository.save(new RoleAssignment(stranger.getId(), HrisRole.EMPLOYEE, null, NOW.minusSeconds(60), NOW));
        strangerCaller = JwtAuthFixture.token(ISSUER, "emp-2");

        bindEmployee("EMP-REV", reviewer.getId(), "rev-1");
        memberEmployeeId = bindEmployee("EMP-OWN", member.getId(), "emp-1");
        strangerEmployeeId = bindEmployee("EMP-OTHER", stranger.getId(), "emp-2");
        employees.addAssignment(memberEmployeeId, UUID.randomUUID(), "ENG", null, "L3",
            Instant.parse("2026-01-01T00:00:00Z"), null, hrCaller);
    }

    private UUID bindEmployee(String number, Long accountId, String subject) {
        UUID employeePublicId = UUID.randomUUID();
        employees.create(employeePublicId, number, hrCaller);
        Employee entity = employeeRepository.findByPublicId(employeePublicId).orElseThrow();
        entity.linkAccount(accountId);
        employeeRepository.save(entity);
        bindingRepository.save(new AccountBinding(entity.getId(), employeePublicId, accountId,
            ISSUER, subject, NOW));
        return employeePublicId;
    }

    private void activateEngConfig() {
        UUID setId = UUID.randomUUID();
        rules.publishRuleSet(setId,
            List.of(new RulePayload("route-eng", 10, "ENG", "L3", reviewerAccountId)), hrCaller);
        rules.activateRuleSet(setId, hrCaller);
    }

    private static ChangePayload phone(String value) {
        return new ChangePayload("personalPhone", value);
    }

    @Test
    void childHistoryContinuationRetrievesEveryEvaluationWithoutCrossEmployeeAccess() {
        ProposalResult created = proposals.createProposal(memberEmployeeId, UUID.randomUUID(),
            List.of(phone("+62 812 345 678")), memberCaller);
        ProposalResult pending = proposals.submitProposal(created.proposalPublicId(), created.proposalVersion(), memberCaller);
        for (int i = 0; i < 105; i++) {
            pending = proposals.retryRoute(pending.proposalPublicId(), pending.proposalVersion(), hrCaller);
        }
        ProposalHistoryResult first = proposals.getHistory(created.proposalPublicId(), memberCaller, 0, 100, 0, 0, 0);
        ProposalHistoryResult second = proposals.getHistory(created.proposalPublicId(), memberCaller, 0, 100, 0, 1, 0);
        assertEquals(106, first.totalEvaluations());
        assertEquals(100, first.evaluations().size());
        assertEquals(6, second.evaluations().size());
        assertTrue(first.evaluations().stream().allMatch(evaluation -> pendingRevision(first).equals(evaluation.revisionPublicId())));
        assertTrue(second.evaluations().stream().allMatch(evaluation -> pendingRevision(first).equals(evaluation.revisionPublicId())));
        assertThrows(ResourceNotFoundException.class, () -> proposals.getHistory(created.proposalPublicId(),
            strangerCaller, 0, 100, 0, 1, 0));
        assertThrows(InvalidPaginationException.class, () -> proposals.getHistory(created.proposalPublicId(),
            memberCaller, 0, 100, 0, -1, 0));
    }

    private static UUID pendingRevision(ProposalHistoryResult history) {
        return history.revisions().getLast().revisionPublicId();
    }

    @Test
    void mixedExplicitNullIsRejectedRatherThanIgnored() {
        assertThrows(ProfileRemovalDisabledException.class, () -> proposals.createProposal(memberEmployeeId,
            UUID.randomUUID(), List.of(phone("+62 812 345 678"), new ChangePayload("personalEmail", null)), memberCaller));
        assertNull(proposals.getContactProfile(memberEmployeeId, memberCaller).personalPhone());
    }

    @Test
    void submissionPinsAssignmentLineageAndResubmissionUsesFreshContext() {
        activateEngConfig();
        UUID originalId = employees.listAssignments(memberEmployeeId, hrCaller).getFirst().getPublicId();
        ProposalResult draft = proposals.createProposal(memberEmployeeId, UUID.randomUUID(),
            List.of(phone("+62 812 345 678")), memberCaller);
        ProposalResult pending = proposals.submitProposal(draft.proposalPublicId(), draft.proposalVersion(), memberCaller);
        ProposalResult returned = proposals.decideProposal(pending.proposalPublicId(), pending.proposalVersion(),
            pending.revisionPublicId(), pending.assignmentPublicId(), "RETURN", "Reconfirm", reviewerCaller);
        UUID replacementId = UUID.randomUUID();
        employees.reviseAssignment(memberEmployeeId, originalId, replacementId, "ENG", null, "L3",
            NOW.minusSeconds(3600), null, hrCaller);
        ProposalResult resubmitted = proposals.resubmitProposal(returned.proposalPublicId(), returned.proposalVersion(),
            List.of(phone("+62 813 345 678")), memberCaller);
        ProposalHistoryResult history = proposals.getHistory(resubmitted.proposalPublicId(), memberCaller, 0, 20);
        assertEquals(originalId, history.revisions().get(1).orgAssignmentId());
        assertEquals(replacementId, history.revisions().getLast().orgAssignmentId());
        assertEquals(originalId, history.revisions().getLast().orgAssignmentPredecessorId());
        assertEquals("RESOLVED", history.revisions().getLast().organizationContextStatus());
        Employee employee = employeeRepository.findByPublicId(memberEmployeeId).orElseThrow();
        assertEquals(replacementId, employees.organizationContext(employee.getId(), NOW).orElseThrow().assignmentPublicId());
        assertFalse(employees.organizationContext(employee.getId(), NOW.minusSeconds(3601)).orElseThrow()
            .assignmentPublicId().equals(replacementId));
    }

    @AfterEach
    void clearCaller() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createDraftPersistsRevisionAndBlocksSecondOpenProposal() {
        ProposalResult created = proposals.createProposal(memberEmployeeId, UUID.randomUUID(),
            List.of(phone("+62 812 345 678")), memberCaller);
        assertEquals("DRAFT", created.state());
        assertEquals(1, created.revisionNumber());
        assertNull(created.assignmentPublicId());

        assertThrows(ProfileOpenProposalException.class, () -> proposals.createProposal(
            memberEmployeeId, UUID.randomUUID(), List.of(phone("+62 812 999 999")), memberCaller));
    }

    @Test
    void createRejectsInvalidContent() {
        assertThrows(ProfileRemovalDisabledException.class, () -> proposals.createProposal(
            memberEmployeeId, UUID.randomUUID(), List.of(phone("   ")), memberCaller));
        assertThrows(ProfileValidationException.class, () -> proposals.createProposal(
            memberEmployeeId, UUID.randomUUID(),
            List.of(new ChangePayload("salary", "100")), memberCaller));
        assertThrows(ProfileValidationException.class, () -> proposals.createProposal(
            memberEmployeeId, UUID.randomUUID(), List.of(), memberCaller));
        assertThrows(ProfileValidationException.class, () -> proposals.createProposal(
            memberEmployeeId, UUID.randomUUID(),
            List.of(new ChangePayload("emergencyContactName", "Siti")), memberCaller));
        proposals.correctContactProfile(memberEmployeeId, 0, 0, 0, 0,
            List.of(phone("+62 812 777 777")), "Seed active value", new AtomicReference<ContactChange>(), hrCaller);
        assertThrows(ProfileNoChangeException.class, () -> proposals.createProposal(
            memberEmployeeId, UUID.randomUUID(), List.of(phone("+62 812 777 777")), memberCaller));
        assertThrows(AccessDeniedException.class, () -> proposals.createProposal(
            memberEmployeeId, UUID.randomUUID(), List.of(phone("+62 812 345 678")), strangerCaller));
        assertThrows(ResourceNotFoundException.class, () -> proposals.createProposal(
            UUID.randomUUID(), UUID.randomUUID(), List.of(phone("+62 812 345 678")), memberCaller));
    }

    @Test
    void editDraftCreatesNewRevision() {
        ProposalResult created = proposals.createProposal(memberEmployeeId, UUID.randomUUID(),
            List.of(phone("+62 812 345 678")), memberCaller);
        ProposalResult edited = proposals.editProposal(created.proposalPublicId(), created.proposalVersion(),
            List.of(phone("+62 812 999 999")), memberCaller);
        assertEquals(2, edited.revisionNumber());
        assertEquals("DRAFT", edited.state());

        ProposalDetailResult detail = proposals.getProposal(created.proposalPublicId(), memberCaller);
        assertEquals(1, detail.changes().size());
        assertEquals("+62 812 999 999", detail.changes().get(0).proposedValue());
    }

    @Test
    void submitMatchesRouteAndAssignsReviewer() {
        activateEngConfig();
        ProposalResult created = proposals.createProposal(memberEmployeeId, UUID.randomUUID(),
            List.of(phone("+62 812 345 678")), memberCaller);
        ProposalResult submitted = proposals.submitProposal(created.proposalPublicId(),
            created.proposalVersion(), memberCaller);
        assertEquals("PENDING", submitted.state());
        assertNull(submitted.blockedReason());
        assertNotNull(submitted.assignmentPublicId());
        assertEquals(2, submitted.revisionNumber());
    }

    @Test
    void submitWithoutConfigurationStaysVisiblyBlocked() {
        ProposalResult created = proposals.createProposal(memberEmployeeId, UUID.randomUUID(),
            List.of(phone("+62 812 345 678")), memberCaller);
        ProposalResult submitted = proposals.submitProposal(created.proposalPublicId(),
            created.proposalVersion(), memberCaller);
        assertEquals("PENDING", submitted.state());
        assertEquals("NO_ACTIVE_CONFIGURATION", submitted.blockedReason());
        assertNull(submitted.assignmentPublicId());
    }

    @Test
    void submitRequiresActualChangeAgainstCurrentValues() {
        activateEngConfig();
        proposals.correctContactProfile(memberEmployeeId, 0, 0, 0, 0,
            List.of(phone("+62 812 111 111")), "Initial HR correction", new AtomicReference<ContactChange>(), hrCaller);
        ProposalResult created = proposals.createProposal(memberEmployeeId, UUID.randomUUID(),
            List.of(phone("+62 812 222 222")), memberCaller);
        proposals.correctContactProfile(memberEmployeeId, 1, 0, 0, 0,
            List.of(phone("+62 812 222 222")), "HR sets the same value", new AtomicReference<ContactChange>(), hrCaller);
        assertThrows(ProfileNoChangeException.class, () -> proposals.submitProposal(
            created.proposalPublicId(), created.proposalVersion(), memberCaller));
    }

    @Test
    void withdrawCancelsDraftAndPendingButNotApproved() {
        activateEngConfig();
        ProposalResult draft = proposals.createProposal(memberEmployeeId, UUID.randomUUID(),
            List.of(phone("+62 812 345 678")), memberCaller);
        ProposalResult cancelled = proposals.withdrawProposal(draft.proposalPublicId(),
            draft.proposalVersion(), memberCaller);
        assertEquals("CANCELLED", cancelled.state());

        ProposalResult created = proposals.createProposal(memberEmployeeId, UUID.randomUUID(),
            List.of(phone("+62 812 999 999")), memberCaller);
        ProposalResult submitted = proposals.submitProposal(created.proposalPublicId(),
            created.proposalVersion(), memberCaller);
        ProposalResult withdrawn = proposals.withdrawProposal(submitted.proposalPublicId(),
            submitted.proposalVersion(), memberCaller);
        assertEquals("CANCELLED", withdrawn.state());

        assertThrows(ProfileInvalidStateException.class, () -> proposals.editProposal(
            withdrawn.proposalPublicId(), withdrawn.proposalVersion(),
            List.of(phone("+62 812 000 000")), memberCaller));
    }

    @Test
    void fullReturnResubmitApproveFlow() {
        activateEngConfig();
        ProposalResult created = proposals.createProposal(memberEmployeeId, UUID.randomUUID(),
            List.of(phone("+62 812 345 678")), memberCaller);
        ProposalResult submitted = proposals.submitProposal(created.proposalPublicId(),
            created.proposalVersion(), memberCaller);
        ProposalResult returned = proposals.decideProposal(submitted.proposalPublicId(),
            submitted.proposalVersion(), submitted.revisionPublicId(), submitted.assignmentPublicId(),
            "RETURN", "Please use the new number format", reviewerCaller);
        assertEquals("RETURNED", returned.state());

        ProposalResult edited = proposals.editProposal(returned.proposalPublicId(),
            returned.proposalVersion(), List.of(phone("+62 813 000 111")), memberCaller);
        assertEquals("RETURNED", edited.state());
        assertEquals(3, edited.revisionNumber());
        assertNull(edited.assignmentPublicId());
        assertNull(proposals.getContactProfile(memberEmployeeId, memberCaller).personalPhone());
        assertThrows(id.mydev.peoplecore.organization.domain.exception.ProfileStaleVersionException.class,
            () -> proposals.editProposal(returned.proposalPublicId(), returned.proposalVersion(),
                List.of(phone("+62 814 111 222")), memberCaller));
        ProposalHistoryResult beforeResubmit = proposals.getHistory(returned.proposalPublicId(), memberCaller, 0, 20);
        assertEquals("+62 812 345 678", beforeResubmit.revisions().get(1).changes().getFirst().proposedValue());
        assertEquals(1, beforeResubmit.assignments().size());
        assertEquals("CLOSED", beforeResubmit.assignments().getFirst().status());
        assertNotNull(beforeResubmit.revisions().get(1).orgAssignmentId());

        ProposalResult resubmitted = proposals.resubmitProposal(returned.proposalPublicId(),
            edited.proposalVersion(), List.of(phone("+62 813 000 111")), memberCaller);
        assertEquals("PENDING", resubmitted.state());
        assertEquals(4, resubmitted.revisionNumber());

        ProposalResult approved = proposals.decideProposal(resubmitted.proposalPublicId(),
            resubmitted.proposalVersion(), resubmitted.revisionPublicId(),
            resubmitted.assignmentPublicId(), "APPROVE", null, reviewerCaller);
        assertEquals("APPROVED", approved.state());
        assertTrue(proposals.isTerminalAssignment(approved.proposalPublicId(),
            approved.assignmentPublicId()));
        assertFalse(proposals.isTerminalAssignment(submitted.proposalPublicId(),
            submitted.assignmentPublicId()));
        assertFalse(proposals.isTerminalAssignment(approved.proposalPublicId(), UUID.randomUUID()));
        assertThrows(ResourceNotFoundException.class, () -> proposals.isTerminalAssignment(
            UUID.randomUUID(), approved.assignmentPublicId()));
        assertThrows(NullPointerException.class, () -> proposals.isTerminalAssignment(
            approved.proposalPublicId(), null));

        ContactProfileResult contact = proposals.getContactProfile(memberEmployeeId, memberCaller);
        assertEquals("+62 813 000 111", contact.personalPhone());
        assertEquals(1, contact.phoneVersion());

        ProposalHistoryResult history = proposals.getHistory(created.proposalPublicId(), memberCaller,
            0, 20);
        assertEquals(4, history.revisions().size());
        assertEquals(2, history.decisions().size());
        assertTrue(history.evaluations().size() >= 2);
        assertEquals(4, history.totalRevisions());
        assertThrows(InvalidPaginationException.class, () -> proposals.getHistory(
            created.proposalPublicId(), memberCaller, 0, 0));
        assertThrows(InvalidPaginationException.class, () -> proposals.getHistory(
            created.proposalPublicId(), memberCaller, -1, 20));
        assertThrows(InvalidPaginationException.class, () -> proposals.getHistory(
            created.proposalPublicId(), memberCaller, 0, 101));
        ProposalHistoryResult secondPage = proposals.getHistory(created.proposalPublicId(), memberCaller,
            1, 2);
        assertEquals(2, secondPage.revisions().size());
        assertEquals(4, secondPage.totalRevisions());
        ProposalHistoryResult emptyPage = proposals.getHistory(created.proposalPublicId(), memberCaller,
            5, 2);
        assertTrue(emptyPage.revisions().isEmpty());
        assertEquals(4, emptyPage.totalRevisions());

        assertThrows(ProfileInvalidStateException.class, () -> proposals.withdrawProposal(
            approved.proposalPublicId(), approved.proposalVersion(), memberCaller));
    }

    @Test
    void detailDerivesCurrentAssignmentEligibility() {
        activateEngConfig();
        ProposalResult created = proposals.createProposal(memberEmployeeId, UUID.randomUUID(),
            List.of(phone("+62 812 345 678")), memberCaller);
        ProposalResult submitted = proposals.submitProposal(created.proposalPublicId(),
            created.proposalVersion(), memberCaller);
        assertTrue(proposals.getProposal(submitted.proposalPublicId(), memberCaller)
            .assignment().eligible());
        assertEquals(List.of("WITHDRAW"), proposals.getProposal(submitted.proposalPublicId(), memberCaller).availableActions());
        assertEquals(List.of("REASSIGN", "APPROVE", "REJECT", "RETURN"),
            proposals.getProposal(submitted.proposalPublicId(), reviewerCaller).availableActions());
        assertEquals(List.of("REASSIGN"), proposals.getProposal(submitted.proposalPublicId(), scopedCaller).availableActions());

        UserAccount reviewer = accountRepository.findByOidcIssuerAndOidcSubject(ISSUER, "rev-1").orElseThrow();
        reviewer.deactivate();
        accountRepository.save(reviewer);
        assertFalse(proposals.getProposal(submitted.proposalPublicId(), memberCaller)
            .assignment().eligible());
        reviewer.reactivate();
        accountRepository.save(reviewer);

        ProposalResult approved = proposals.decideProposal(submitted.proposalPublicId(),
            submitted.proposalVersion(), submitted.revisionPublicId(),
            submitted.assignmentPublicId(), "APPROVE", null, reviewerCaller);
        assertEquals("APPROVED", approved.state());
        assertTrue(proposals.getProposal(approved.proposalPublicId(), memberCaller)
            .assignment().eligible());
        assertTrue(proposals.getProposal(approved.proposalPublicId(), memberCaller).availableActions().isEmpty());
    }

    @Test
    void readsRestrictToOwnerAndScopedHr() {
        activateEngConfig();
        ProposalResult created = proposals.createProposal(memberEmployeeId, UUID.randomUUID(),
            List.of(phone("+62 812 345 678")), memberCaller);
        proposals.submitProposal(created.proposalPublicId(), created.proposalVersion(), memberCaller);

        assertNotNull(proposals.getProposal(created.proposalPublicId(), memberCaller));
        assertNotNull(proposals.getProposal(created.proposalPublicId(), scopedCaller));
        assertThrows(ResourceNotFoundException.class,
            () -> proposals.getProposal(created.proposalPublicId(), strangerCaller));
        assertThrows(ResourceNotFoundException.class,
            () -> proposals.getContactProfile(memberEmployeeId, strangerCaller));

        assertThrows(AccessDeniedException.class, () -> proposals.queueProposals(memberCaller, 0, 20));
        assertThrows(InvalidPaginationException.class, () -> proposals.queueProposals(scopedCaller, 0, 101));
        assertThrows(InvalidPaginationException.class, () -> proposals.queueProposals(scopedCaller, -1, 20));
    }

    @Test
    void createRejectsDuplicatesAndEmptyChanges() {
        activateEngConfig();
        List<ChangePayload> duplicated = new ArrayList<>();
        duplicated.add(phone("+62 812 345 678"));
        duplicated.add(phone("+62 812 999 999"));
        assertThrows(ProfileValidationException.class, () -> proposals.createProposal(
            memberEmployeeId, UUID.randomUUID(), duplicated, memberCaller));
        Map<String, String> allNull = new LinkedHashMap<>();
        allNull.put("personalPhone", null);
        assertThrows(ProfileRemovalDisabledException.class, () -> proposals.createProposal(
            memberEmployeeId, UUID.randomUUID(),
            List.of(new ChangePayload("personalPhone", null)), memberCaller));
        assertThrows(NullPointerException.class, () -> proposals.createProposal(
            memberEmployeeId, UUID.randomUUID(), null, memberCaller));
        assertThrows(ProfileValidationException.class, () -> proposals.createProposal(
            memberEmployeeId, UUID.randomUUID(),
            List.of(new ChangePayload("emergencyContactName", "Siti"),
                new ChangePayload("emergencyContactRelationship", "Saudara"),
                new ChangePayload("emergencyContactPhone", null)),
            memberCaller));
    }

    @Test
    void masterCorrectCoversEveryGroupWithVersionChecks() {
        ContactCorrectionResult corrected = proposals.correctContactProfile(memberEmployeeId, 0, 0, 0, 0,
            List.of(new ChangePayload("personalEmail", "member@example.id")), "HR correction", new AtomicReference<ContactChange>(), scopedCaller);
        assertEquals(1, corrected.emailVersion());
        assertEquals(memberEmployeeId, corrected.employeePublicId());
        ContactCorrectionResult addressed = proposals.correctContactProfile(memberEmployeeId, 0, 1, 0, 0,
            List.of(new ChangePayload("residentialAddress", "Jl. Merdeka 45")), "HR correction", new AtomicReference<ContactChange>(), scopedCaller);
        assertEquals(1, addressed.addressVersion());
        ContactCorrectionResult emergency = proposals.correctContactProfile(memberEmployeeId, 0, 1, 1, 0,
            List.of(new ChangePayload("emergencyContactName", "Siti"),
                new ChangePayload("emergencyContactRelationship", "Saudara"),
                new ChangePayload("emergencyContactPhone", "+62 812 000 111")),
            "HR correction", new AtomicReference<ContactChange>(), scopedCaller);
        assertEquals(1, emergency.emergencyVersion());
        assertThrows(ProfileConflictException.class, () -> proposals.correctContactProfile(
            memberEmployeeId, 0, 1, 1, 0,
            List.of(new ChangePayload("emergencyContactName", "Siti"),
                new ChangePayload("emergencyContactRelationship", "Saudara"),
                new ChangePayload("emergencyContactPhone", "+62 812 300 400")),
            "Stale correction", new AtomicReference<ContactChange>(), scopedCaller));
        assertThrows(ProfileValidationException.class, () -> proposals.correctContactProfile(
            memberEmployeeId, 0, 1, 1, 1,
            List.of(phone("+62 812 300 400")), null, new AtomicReference<ContactChange>(), scopedCaller));
        assertThrows(ProfileValidationException.class, () -> proposals.correctContactProfile(
            memberEmployeeId, 0, 1, 1, 1,
            List.of(phone("+62 812 300 400")), "x".repeat(1001), new AtomicReference<ContactChange>(), scopedCaller));
    }

    @Test
    void replayHelpersFollowCurrentAuthorization() {
        activateEngConfig();
        ProposalResult created = proposals.createProposal(memberEmployeeId, UUID.randomUUID(),
            List.of(phone("+62 812 345 678")), memberCaller);
        assertEquals(memberEmployeeId,
            proposals.requireOwnershipForReplay(created.proposalPublicId(), memberCaller));
        assertThrows(AccessDeniedException.class, () -> proposals.requireOwnershipForReplay(
            created.proposalPublicId(), strangerCaller));
        assertThrows(ResourceNotFoundException.class, () -> proposals.requireOwnershipForReplay(
            UUID.randomUUID(), memberCaller));
        proposals.requireHrScopeForReplay(created.proposalPublicId(), scopedCaller);
        assertThrows(ResourceNotFoundException.class, () -> proposals.requireHrScopeForReplay(
            UUID.randomUUID(), scopedCaller));
        assertNull(proposals.lockForReplay(created.proposalPublicId()).assigneeAccountPublicId());
        ProposalResult submitted = proposals.submitProposal(created.proposalPublicId(),
            created.proposalVersion(), memberCaller);
        assertNotNull(proposals.lockForReplay(submitted.proposalPublicId()).assigneeAccountPublicId());
        assertThrows(ResourceNotFoundException.class,
            () -> proposals.lockForReplay(UUID.randomUUID()));
        assertThrows(AccessDeniedException.class,
            () -> proposals.ownEmployeePublicId(scopedCaller));
    }

    @Test
    void rolelessCallerCannotReadAnotherProfile() {
        activateEngConfig();
        UserAccount norole = accountRepository.save(
            new UserAccount(UUID.randomUUID(), ISSUER, "norole-1", NOW));
        Authentication noroleCaller = JwtAuthFixture.token(ISSUER, "norole-1");
        assertThrows(ResourceNotFoundException.class,
            () -> proposals.getContactProfile(memberEmployeeId, noroleCaller));
        ProposalResult created = proposals.createProposal(memberEmployeeId, UUID.randomUUID(),
            List.of(phone("+62 812 345 678")), memberCaller);
        assertThrows(ResourceNotFoundException.class,
            () -> proposals.getProposal(created.proposalPublicId(), noroleCaller));
        assertEquals(norole.getPublicId(), accountRepository.findByPublicId(norole.getPublicId())
            .orElseThrow().getPublicId());
    }

    @Test
    void masterCorrectAppliesWithVersionCheck() {
        var changeRef = new AtomicReference<ContactChange>();
        ContactCorrectionResult corrected = proposals.correctContactProfile(memberEmployeeId, 0, 0, 0, 0,
            List.of(phone("+62 812 100 200")), "HR correction", changeRef, scopedCaller);
        assertEquals(1, corrected.phoneVersion());
        assertEquals("+62 812 100 200", proposals.getContactProfile(memberEmployeeId, scopedCaller)
            .personalPhone());
        assertNull(changeRef.get().before().get("personalPhone"));
        assertEquals("+62 812 100 200", changeRef.get().after().get("personalPhone"));
        assertThrows(ProfileNoChangeException.class, () -> proposals.correctContactProfile(
            memberEmployeeId, 1, 0, 0, 0, List.of(phone("+62 812 100 200")), "No-op correction",
            new AtomicReference<ContactChange>(), scopedCaller));
        assertThrows(AccessDeniedException.class, () -> proposals.correctContactProfile(
            memberEmployeeId, 1, 0, 0, 0, List.of(phone("+62 812 300 400")), "Member attempt",
            new AtomicReference<ContactChange>(), memberCaller));
    }
}
