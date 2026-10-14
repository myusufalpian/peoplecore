package id.mydev.peoplecore.organization.application.service;

import id.mydev.peoplecore.approval.application.command.ApprovalPayloads.RulePayload;
import id.mydev.peoplecore.approval.application.service.ApprovalRuleService;
import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.identity.domain.model.AccountBinding;
import id.mydev.peoplecore.identity.domain.model.HrisRole;
import id.mydev.peoplecore.identity.domain.model.RoleAssignment;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.identity.domain.repository.AccountBindingRepository;
import id.mydev.peoplecore.identity.domain.repository.RoleAssignmentRepository;
import id.mydev.peoplecore.identity.domain.repository.UserAccountRepository;
import id.mydev.peoplecore.organization.application.command.ProfilePayloads.ChangePayload;
import id.mydev.peoplecore.organization.application.command.ProfileResults.ProposalHistoryResult;
import id.mydev.peoplecore.organization.application.command.ProfileResults.ContactChange;
import id.mydev.peoplecore.organization.application.command.ProfileResults.ContactProfileResult;
import id.mydev.peoplecore.organization.application.command.ProfileResults.ProposalResult;
import id.mydev.peoplecore.organization.domain.exception.ProfileAssignmentStaleException;
import id.mydev.peoplecore.organization.domain.exception.ProfileConflictException;
import id.mydev.peoplecore.organization.domain.exception.ProfileInvalidStateException;
import id.mydev.peoplecore.organization.domain.exception.ProfileReviewerIneligibleException;
import id.mydev.peoplecore.organization.domain.exception.ProfileStaleVersionException;
import id.mydev.peoplecore.organization.domain.exception.ProfileValidationException;
import id.mydev.peoplecore.organization.domain.model.ContactProfile;
import id.mydev.peoplecore.organization.domain.model.Employee;
import id.mydev.peoplecore.organization.domain.model.ProfileRevision;
import id.mydev.peoplecore.organization.domain.repository.ContactProfileRepository;
import id.mydev.peoplecore.organization.domain.repository.EmployeeRepository;
import id.mydev.peoplecore.organization.domain.repository.ProfileProposalRepository;
import id.mydev.peoplecore.organization.domain.repository.ProfileRevisionRepository;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(properties = {
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "peoplecore.organization.allowed-units=ENG,FIN,OPS"
})
@Transactional
class ProfileDecisionServiceTest {

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
    @Autowired ContactProfileRepository contactProfileRepository;
    @Autowired ProfileRevisionRepository revisionRepository;
    @Autowired ProfileProposalRepository proposalRepository;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    private Authentication hrCaller;
    private Authentication reviewerCaller;
    private Authentication reviewerTwoCaller;
    private Authentication memberCaller;
    private UUID reviewerAccountId;
    private UUID reviewerTwoAccountId;
    private UUID memberEmployeeId;

    @BeforeEach
    void seedAccess() {
        UserAccount hr = accountRepository.save(new UserAccount(UUID.randomUUID(), ISSUER, "hr-1", NOW));
        roleRepository.save(new RoleAssignment(hr.getId(), HrisRole.HR_ADMIN, null, NOW.minusSeconds(60), NOW));
        hrCaller = JwtAuthFixture.token(ISSUER, "hr-1");

        UserAccount reviewer = accountRepository.save(new UserAccount(UUID.randomUUID(), ISSUER, "rev-1", NOW));
        roleRepository.save(new RoleAssignment(reviewer.getId(), HrisRole.HR_ADMIN, "ENG", NOW.minusSeconds(60), NOW));
        roleRepository.save(new RoleAssignment(reviewer.getId(), HrisRole.EMPLOYEE, null, NOW.minusSeconds(60), NOW));
        reviewerAccountId = reviewer.getPublicId();
        reviewerCaller = JwtAuthFixture.token(ISSUER, "rev-1");

        UserAccount reviewerTwo = accountRepository.save(new UserAccount(UUID.randomUUID(), ISSUER, "rev-2", NOW));
        roleRepository.save(new RoleAssignment(reviewerTwo.getId(), HrisRole.HR_ADMIN, "ENG", NOW.minusSeconds(60), NOW));
        reviewerTwoAccountId = reviewerTwo.getPublicId();
        reviewerTwoCaller = JwtAuthFixture.token(ISSUER, "rev-2");

        UserAccount member = accountRepository.save(new UserAccount(UUID.randomUUID(), ISSUER, "emp-1", NOW));
        roleRepository.save(new RoleAssignment(member.getId(), HrisRole.EMPLOYEE, null, NOW.minusSeconds(60), NOW));
        memberCaller = JwtAuthFixture.token(ISSUER, "emp-1");

        bindEmployee("EMP-REV", reviewer.getId(), "rev-1");
        bindEmployee("EMP-REV2", reviewerTwo.getId(), "rev-2");
        memberEmployeeId = bindEmployee("EMP-OWN", member.getId(), "emp-1");
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

    private ProposalResult pendingPhoneProposal(String phone) {
        ProposalResult created = proposals.createProposal(memberEmployeeId, UUID.randomUUID(),
            List.of(new ChangePayload("personalPhone", phone)), memberCaller);
        return proposals.submitProposal(created.proposalPublicId(), created.proposalVersion(), memberCaller);
    }

    @AfterEach
    void clearCaller() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void approveAppliesAllTouchedFieldsAtomically() {
        activateEngConfig();
        ProposalResult created = proposals.createProposal(memberEmployeeId, UUID.randomUUID(), List.of(
            new ChangePayload("personalPhone", "+62 812 345 678"),
            new ChangePayload("personalEmail", "member@example.id")), memberCaller);
        ProposalResult submitted = proposals.submitProposal(created.proposalPublicId(),
            created.proposalVersion(), memberCaller);
        ProposalResult approved = proposals.decideProposal(submitted.proposalPublicId(),
            submitted.proposalVersion(), submitted.revisionPublicId(), submitted.assignmentPublicId(),
            "APPROVE", null, reviewerCaller);
        assertEquals("APPROVED", approved.state());

        ContactProfileResult contact = proposals.getContactProfile(memberEmployeeId, memberCaller);
        assertEquals("+62 812 345 678", contact.personalPhone());
        assertEquals("member@example.id", contact.personalEmail());
        assertEquals(1, contact.phoneVersion());
        assertEquals(1, contact.emailVersion());
        assertEquals(0, contact.addressVersion());
    }

    @Test
    void pagedHistoryPreservesMultipleReassignmentsAndDecisionLinks() {
        activateEngConfig();
        ProposalResult first = pendingPhoneProposal("+62 812 345 678");
        ProposalResult second = proposals.reassignProposal(first.proposalPublicId(), first.proposalVersion(),
            first.assignmentPublicId(), reviewerTwoAccountId, "Cover absence", hrCaller);
        ProposalResult third = proposals.reassignProposal(second.proposalPublicId(), second.proposalVersion(),
            second.assignmentPublicId(), reviewerAccountId, "Original reviewer returned", hrCaller);
        ProposalResult returned = proposals.decideProposal(third.proposalPublicId(), third.proposalVersion(),
            third.revisionPublicId(), third.assignmentPublicId(), "RETURN", "Confirm phone", reviewerCaller);
        ProposalResult resubmitted = proposals.resubmitProposal(returned.proposalPublicId(), returned.proposalVersion(),
            List.of(new ChangePayload("personalPhone", "+62 813 345 678")), memberCaller);
        proposals.decideProposal(resubmitted.proposalPublicId(), resubmitted.proposalVersion(),
            resubmitted.revisionPublicId(), resubmitted.assignmentPublicId(), "REJECT", "Not accepted", reviewerCaller);

        List<UUID> assignmentIds = List.of(first.assignmentPublicId(), second.assignmentPublicId(),
            third.assignmentPublicId(), resubmitted.assignmentPublicId());
        List<UUID> reviewerIds = List.of(reviewerAccountId, reviewerTwoAccountId,
            reviewerAccountId, reviewerAccountId);
        for (int page = 0; page < 4; page++) {
            ProposalHistoryResult history = proposals.getHistory(first.proposalPublicId(), memberCaller,
                0, 1, 0, 0, page);
            assertEquals(4, history.totalAssignments());
            assertEquals(1, history.assignments().size());
            var assignment = history.assignments().getFirst();
            assertEquals(assignmentIds.get(page), assignment.assignmentPublicId());
            assertEquals(reviewerIds.get(page), assignment.reviewerAccountId());
            assertEquals(page < 3 ? first.revisionPublicId() : resubmitted.revisionPublicId(), assignment.revisionPublicId());
            assertEquals(NOW, assignment.createdAt());
            assertEquals(NOW, assignment.closedAt());
            assertEquals(page < 2 ? "SUPERSEDED" : "CLOSED", assignment.status());
            if (page < 2) {
                assertEquals(page == 0 ? "Cover absence" : "Original reviewer returned", assignment.reason());
                assertEquals(hrCaller.getName(), assignment.actor());
            } else {
                assertNull(assignment.reason());
                assertNull(assignment.actor());
            }
        }
        assertEquals(0, proposals.getHistory(first.proposalPublicId(), memberCaller,
            0, 1, 0, 0, 4).assignments().size());
        ProposalHistoryResult finalDecision = proposals.getHistory(first.proposalPublicId(), memberCaller,
            0, 1, 0, 0, 0);
        ProposalHistoryResult earlierDecision = proposals.getHistory(first.proposalPublicId(), memberCaller,
            0, 1, 1, 0, 0);
        assertEquals(2, finalDecision.totalDecisions());
        assertEquals(1, finalDecision.decisions().size());
        assertEquals(1, earlierDecision.decisions().size());
        assertEquals("REJECT", finalDecision.decisions().getFirst().action());
        assertEquals(resubmitted.revisionPublicId(), finalDecision.decisions().getFirst().revisionPublicId());
        assertEquals(resubmitted.assignmentPublicId(), finalDecision.decisions().getFirst().assignmentPublicId());
        assertEquals("RETURN", earlierDecision.decisions().getFirst().action());
        assertEquals(third.revisionPublicId(), earlierDecision.decisions().getFirst().revisionPublicId());
        assertEquals(third.assignmentPublicId(), earlierDecision.decisions().getFirst().assignmentPublicId());
        assertEquals(0, proposals.getHistory(first.proposalPublicId(), memberCaller,
            0, 1, 2, 0, 0).decisions().size());
    }

    @Test
    void approveDetectsTouchedFieldConflict() {
        activateEngConfig();
        ProposalResult submitted = pendingPhoneProposal("+62 812 345 678");
        proposals.correctContactProfile(memberEmployeeId, 0, 0, 0, 0,
            List.of(new ChangePayload("personalPhone", "+62 812 999 999")), "HR correction", new AtomicReference<ContactChange>(), hrCaller);
        assertThrows(ProfileConflictException.class, () -> proposals.decideProposal(
            submitted.proposalPublicId(), submitted.proposalVersion(), submitted.revisionPublicId(),
            submitted.assignmentPublicId(), "APPROVE", null, reviewerCaller));

        ContactProfileResult contact = proposals.getContactProfile(memberEmployeeId, memberCaller);
        assertEquals("+62 812 999 999", contact.personalPhone());
        assertEquals("PENDING", proposals.getProposal(submitted.proposalPublicId(), memberCaller)
            .proposal().state());
    }

    @Test
    void approvePreservesUntouchedFieldChanges() {
        activateEngConfig();
        ProposalResult submitted = pendingPhoneProposal("+62 812 345 678");
        proposals.correctContactProfile(memberEmployeeId, 0, 0, 0, 0,
            List.of(new ChangePayload("personalEmail", "hr-set@example.id")), "HR correction", new AtomicReference<ContactChange>(), hrCaller);
        ProposalResult approved = proposals.decideProposal(submitted.proposalPublicId(),
            submitted.proposalVersion(), submitted.revisionPublicId(), submitted.assignmentPublicId(),
            "APPROVE", null, reviewerCaller);
        assertEquals("APPROVED", approved.state());
        ContactProfileResult contact = proposals.getContactProfile(memberEmployeeId, memberCaller);
        assertEquals("+62 812 345 678", contact.personalPhone());
        assertEquals("hr-set@example.id", contact.personalEmail());
    }

    @Test
    void approveDetectsInterveningSameValueChange() {
        activateEngConfig();
        proposals.correctContactProfile(memberEmployeeId, 0, 0, 0, 0,
            List.of(new ChangePayload("personalPhone", "+62 812 111 111")), "Seed", new AtomicReference<ContactChange>(), hrCaller);
        ProposalResult submitted = pendingPhoneProposal("+62 812 222 222");
        proposals.correctContactProfile(memberEmployeeId, 1, 0, 0, 0,
            List.of(new ChangePayload("personalPhone", "+62 812 333 333")), "Interim", new AtomicReference<ContactChange>(), hrCaller);
        proposals.correctContactProfile(memberEmployeeId, 2, 0, 0, 0,
            List.of(new ChangePayload("personalPhone", "+62 812 111 111")), "Revert", new AtomicReference<ContactChange>(), hrCaller);
        assertThrows(ProfileConflictException.class, () -> proposals.decideProposal(
            submitted.proposalPublicId(), submitted.proposalVersion(), submitted.revisionPublicId(),
            submitted.assignmentPublicId(), "APPROVE", null, reviewerCaller));
    }

    @Test
    void rejectLeavesActiveDataUnchanged() {
        activateEngConfig();
        ProposalResult submitted = pendingPhoneProposal("+62 812 345 678");
        assertThrows(ProfileValidationException.class, () -> proposals.decideProposal(
            submitted.proposalPublicId(), submitted.proposalVersion(), submitted.revisionPublicId(),
            submitted.assignmentPublicId(), "RETURN", "  ", reviewerCaller));
        ProposalResult rejected = proposals.decideProposal(submitted.proposalPublicId(),
            submitted.proposalVersion(), submitted.revisionPublicId(), submitted.assignmentPublicId(),
            "REJECT", "Number cannot be verified", reviewerCaller);
        assertEquals("REJECTED", rejected.state());
        assertNull(proposals.getContactProfile(memberEmployeeId, memberCaller).personalPhone());
        assertThrows(ProfileInvalidStateException.class, () -> proposals.decideProposal(
            rejected.proposalPublicId(), rejected.proposalVersion(), rejected.revisionPublicId(),
            rejected.assignmentPublicId(), "APPROVE", null, reviewerCaller));
    }

    @Test
    void decisionsEnforceCurrencyAndEligibility() {
        activateEngConfig();
        ProposalResult submitted = pendingPhoneProposal("+62 812 345 678");
        assertThrows(ProfileStaleVersionException.class, () -> proposals.decideProposal(
            submitted.proposalPublicId(), submitted.proposalVersion() + 1, submitted.revisionPublicId(),
            submitted.assignmentPublicId(), "APPROVE", null, reviewerCaller));
        assertThrows(ProfileAssignmentStaleException.class, () -> proposals.decideProposal(
            submitted.proposalPublicId(), submitted.proposalVersion(), UUID.randomUUID(),
            submitted.assignmentPublicId(), "APPROVE", null, reviewerCaller));
        assertThrows(ProfileAssignmentStaleException.class, () -> proposals.decideProposal(
            submitted.proposalPublicId(), submitted.proposalVersion(), submitted.revisionPublicId(),
            UUID.randomUUID(), "APPROVE", null, reviewerCaller));
        assertThrows(AccessDeniedException.class, () -> proposals.decideProposal(
            submitted.proposalPublicId(), submitted.proposalVersion(), submitted.revisionPublicId(),
            submitted.assignmentPublicId(), "APPROVE", null, reviewerTwoCaller));
        assertThrows(AccessDeniedException.class, () -> proposals.decideProposal(
            submitted.proposalPublicId(), submitted.proposalVersion(), submitted.revisionPublicId(),
            submitted.assignmentPublicId(), "APPROVE", null, memberCaller));
        assertThrows(ProfileValidationException.class, () -> proposals.decideProposal(
            submitted.proposalPublicId(), submitted.proposalVersion(), submitted.revisionPublicId(),
            submitted.assignmentPublicId(), "ESCALATE", null, reviewerCaller));
    }

    @Test
    void reassignMovesAuthorityWithReason() {
        activateEngConfig();
        ProposalResult submitted = pendingPhoneProposal("+62 812 345 678");
        ProposalResult reassigned = proposals.reassignProposal(submitted.proposalPublicId(),
            submitted.proposalVersion(), submitted.assignmentPublicId(), reviewerTwoAccountId,
            "Reviewer is on leave", hrCaller);
        assertEquals("PENDING", reassigned.state());

        assertThrows(ProfileAssignmentStaleException.class, () -> proposals.decideProposal(
            submitted.proposalPublicId(), reassigned.proposalVersion(), submitted.revisionPublicId(),
            submitted.assignmentPublicId(), "APPROVE", null, reviewerCaller));
        ProposalResult approved = proposals.decideProposal(reassigned.proposalPublicId(),
            reassigned.proposalVersion(), reassigned.revisionPublicId(),
            reassigned.assignmentPublicId(), "APPROVE", null, reviewerTwoCaller);
        assertEquals("APPROVED", approved.state());

        assertThrows(ProfileInvalidStateException.class, () -> proposals.reassignProposal(
            submitted.proposalPublicId(), approved.proposalVersion(), reassigned.assignmentPublicId(),
            reviewerAccountId, "Late move", hrCaller));
    }

    @Test
    void reassignRejectsIneligibleReplacement() {
        activateEngConfig();
        ProposalResult submitted = pendingPhoneProposal("+62 812 345 678");
        UserAccount member = accountRepository.findByPublicId(
            accountPublicIdOf("emp-1")).orElseThrow();
        assertThrows(ProfileReviewerIneligibleException.class, () -> proposals.reassignProposal(
            submitted.proposalPublicId(), submitted.proposalVersion(), submitted.assignmentPublicId(),
            member.getPublicId(), "Assign to member", hrCaller));
        assertThrows(ProfileValidationException.class, () -> proposals.reassignProposal(
            submitted.proposalPublicId(), submitted.proposalVersion(), submitted.assignmentPublicId(),
            reviewerTwoAccountId, "  ", hrCaller));
        assertThrows(ProfileReviewerIneligibleException.class, () -> proposals.reassignProposal(
            submitted.proposalPublicId(), submitted.proposalVersion(), submitted.assignmentPublicId(),
            UUID.randomUUID(), "Assign unknown", hrCaller));
    }

    @Test
    void retryRouteRecoversBlockedProposal() {
        UUID seniorSet = UUID.randomUUID();
        rules.publishRuleSet(seniorSet,
            List.of(new RulePayload("senior-only", 1, "ENG", "L9", reviewerAccountId)), hrCaller);
        rules.activateRuleSet(seniorSet, hrCaller);
        ProposalResult created = proposals.createProposal(memberEmployeeId, UUID.randomUUID(),
            List.of(new ChangePayload("personalPhone", "+62 812 345 678")), memberCaller);
        ProposalResult blocked = proposals.submitProposal(created.proposalPublicId(),
            created.proposalVersion(), memberCaller);
        assertEquals("NO_MATCH", blocked.blockedReason());

        activateEngConfig();
        ProposalResult recovered = proposals.retryRoute(blocked.proposalPublicId(),
            blocked.proposalVersion(), hrCaller);
        assertEquals("PENDING", recovered.state());
        assertNull(recovered.blockedReason());

        ProposalResult approved = proposals.decideProposal(recovered.proposalPublicId(),
            recovered.proposalVersion(), recovered.revisionPublicId(),
            recovered.assignmentPublicId(), "APPROVE", null, reviewerCaller);
        assertEquals("APPROVED", approved.state());

        assertThrows(ProfileInvalidStateException.class, () -> proposals.retryRoute(
            approved.proposalPublicId(), approved.proposalVersion(), hrCaller));
    }

    @Test
    void reassignValidatesAssignmentAndReplacement() {
        activateEngConfig();
        ProposalResult submitted = pendingPhoneProposal("+62 812 345 678");
        assertThrows(ProfileAssignmentStaleException.class, () -> proposals.reassignProposal(
            submitted.proposalPublicId(), submitted.proposalVersion(), UUID.randomUUID(),
            reviewerTwoAccountId, "Wrong assignment", hrCaller));
        UserAccount reviewerTwo = accountRepository.findByPublicId(reviewerTwoAccountId).orElseThrow();
        reviewerTwo.deactivate();
        accountRepository.save(reviewerTwo);
        assertThrows(AccessDeniedException.class, () -> proposals.reassignProposal(
            submitted.proposalPublicId(), submitted.proposalVersion(), submitted.assignmentPublicId(),
            reviewerTwoAccountId, "Inactive replacement", hrCaller));
    }

    @Test
    void retryRequiresBlockedPendingProposal() {
        activateEngConfig();
        ProposalResult submitted = pendingPhoneProposal("+62 812 345 678");
        assertThrows(ProfileInvalidStateException.class, () -> proposals.retryRoute(
            submitted.proposalPublicId(), submitted.proposalVersion(), hrCaller));
    }

    @Test
    void retryKeepsBlockageWhenConfigurationStillAmbiguous() {
        UUID tieSet = UUID.randomUUID();
        rules.publishRuleSet(tieSet, List.of(
            new RulePayload("tie-a", 10, "ENG", "L3", reviewerAccountId),
            new RulePayload("tie-b", 10, "ENG", null, reviewerAccountId)), hrCaller);
        rules.activateRuleSet(tieSet, hrCaller);
        ProposalResult created = proposals.createProposal(memberEmployeeId, UUID.randomUUID(),
            List.of(new ChangePayload("personalPhone", "+62 812 345 678")), memberCaller);
        ProposalResult blocked = proposals.submitProposal(created.proposalPublicId(),
            created.proposalVersion(), memberCaller);
        ProposalResult retried = proposals.retryRoute(blocked.proposalPublicId(),
            blocked.proposalVersion(), hrCaller);
        assertEquals("PENDING", retried.state());
        assertEquals("AMBIGUOUS_RULE", retried.blockedReason());
    }

    @Test
    void historyBoundsEvaluationsAndDecisionsToRequestedSize() {
        UUID tieSet = UUID.randomUUID();
        rules.publishRuleSet(tieSet, List.of(
            new RulePayload("tie-a", 10, "ENG", "L3", reviewerAccountId),
            new RulePayload("tie-b", 10, "ENG", null, reviewerAccountId)), hrCaller);
        rules.activateRuleSet(tieSet, hrCaller);
        ProposalResult created = proposals.createProposal(memberEmployeeId, UUID.randomUUID(),
            List.of(new ChangePayload("personalPhone", "+62 812 345 678")), memberCaller);
        ProposalResult blocked = proposals.submitProposal(created.proposalPublicId(),
            created.proposalVersion(), memberCaller);
        ProposalResult current = blocked;
        for (int attempt = 0; attempt < 4; attempt++) {
            current = proposals.retryRoute(current.proposalPublicId(), current.proposalVersion(),
                hrCaller);
        }
        ProposalHistoryResult bounded = proposals.getHistory(blocked.proposalPublicId(), memberCaller,
            0, 2);
        assertEquals(2, bounded.evaluations().size());
        assertEquals(5, bounded.totalEvaluations());
        ProposalHistoryResult full = proposals.getHistory(blocked.proposalPublicId(), memberCaller,
            0, 20);
        assertEquals(5, full.evaluations().size());
        assertEquals(5, full.totalEvaluations());
    }

    @Test
    void approveDetectsValueDriftWithoutVersionBump() {
        activateEngConfig();
        ProposalResult submitted = pendingPhoneProposal("+62 812 345 678");
        ContactProfile drifted = contactProfileRepository.findByEmployeeId(
            employeeRepository.findByPublicId(memberEmployeeId).orElseThrow().getId()).orElseThrow();
        org.springframework.test.util.ReflectionTestUtils.setField(
            drifted, "personalPhone", "+62 812 000 000");
        contactProfileRepository.saveAndFlush(drifted);
        assertThrows(ProfileConflictException.class, () -> proposals.decideProposal(
            submitted.proposalPublicId(), submitted.proposalVersion(), submitted.revisionPublicId(),
            submitted.assignmentPublicId(), "APPROVE", null, reviewerCaller));
    }

    @Test
    void blockedProposalHasNoActiveAssignment() {
        UUID tieSet = UUID.randomUUID();
        rules.publishRuleSet(tieSet, List.of(
            new RulePayload("tie-a", 10, "ENG", "L3", reviewerAccountId),
            new RulePayload("tie-b", 10, "ENG", null, reviewerAccountId)), hrCaller);
        rules.activateRuleSet(tieSet, hrCaller);
        ProposalResult created = proposals.createProposal(memberEmployeeId, UUID.randomUUID(),
            List.of(new ChangePayload("personalPhone", "+62 812 345 678")), memberCaller);
        ProposalResult blocked = proposals.submitProposal(created.proposalPublicId(),
            created.proposalVersion(), memberCaller);
        assertThrows(ProfileAssignmentStaleException.class, () -> proposals.decideProposal(
            blocked.proposalPublicId(), blocked.proposalVersion(), blocked.revisionPublicId(),
            UUID.randomUUID(), "APPROVE", null, reviewerCaller));
        assertThrows(ProfileAssignmentStaleException.class, () -> proposals.reassignProposal(
            blocked.proposalPublicId(), blocked.proposalVersion(), UUID.randomUUID(),
            reviewerTwoAccountId, "No assignment", hrCaller));
    }

    @Test
    void staleRevisionReferenceOnLiveAssignmentIsRejected() {
        activateEngConfig();
        ProposalResult submitted = pendingPhoneProposal("+62 812 345 678");
        List<ProfileRevision> revisions = revisionRepository.findByProposalIdOrderByRevisionNumberAsc(
            proposalRepository.findByPublicId(submitted.proposalPublicId()).orElseThrow().getId());
        assertEquals(2, revisions.size());
        jdbc.update("update profile_assignment set revision_id=? where proposal_id=?",
            revisions.get(0).getId(),
            proposalRepository.findByPublicId(submitted.proposalPublicId()).orElseThrow().getId());
        assertThrows(ProfileAssignmentStaleException.class, () -> proposals.decideProposal(
            submitted.proposalPublicId(), submitted.proposalVersion(), submitted.revisionPublicId(),
            submitted.assignmentPublicId(), "APPROVE", null, reviewerCaller));
    }

    @Test
    void reviewerCannotDecideOwnProposal() {
        activateEngConfig();
        UUID reviewerEmployee = employeeRepository.findByEmployeeNumber("EMP-REV")
            .orElseThrow().getPublicId();
        employees.addAssignment(reviewerEmployee, UUID.randomUUID(), "ENG", null, "L3",
            Instant.parse("2026-01-01T00:00:00Z"), null, hrCaller);
        ProposalResult created = proposals.createProposal(reviewerEmployee, UUID.randomUUID(),
            List.of(new ChangePayload("personalPhone", "+62 812 444 555")), reviewerCaller);
        ProposalResult blocked = proposals.submitProposal(created.proposalPublicId(),
            created.proposalVersion(), reviewerCaller);
        assertEquals("SELF_REVIEW_PROHIBITED", blocked.blockedReason());
        assertNull(blocked.assignmentPublicId());
    }

    @Test
    void expiredEmployeeGrantBlocksOwnerActions() {
        activateEngConfig();
        UserAccount member = accountRepository.findByOidcIssuerAndOidcSubject(ISSUER, "emp-1").orElseThrow();
        RoleAssignment grant = roleRepository.findByAccountId(member.getId()).stream()
            .filter(entry -> HrisRole.EMPLOYEE.equals(entry.getRole()))
            .findFirst().orElseThrow();
        grant.endAt(NOW.minusSeconds(1));
        roleRepository.save(grant);
        assertThrows(AccessDeniedException.class, () -> proposals.createProposal(
            memberEmployeeId, UUID.randomUUID(),
            List.of(new ChangePayload("personalPhone", "+62 812 345 678")), memberCaller));
    }

    @Test
    void changedBindingAfterAssignmentBlocksSelfDecision() {
        activateEngConfig();
        ProposalResult submitted = pendingPhoneProposal("+62 812 345 678");
        UserAccount reviewer = accountRepository.findByPublicId(reviewerAccountId).orElseThrow();
        bindingRepository.findByAccountIdAndRevokedAtIsNull(reviewer.getId())
            .ifPresent(bindingRepository::delete);
        Long memberInternalId = employeeRepository.findByPublicId(memberEmployeeId).orElseThrow().getId();
        bindingRepository.save(new AccountBinding(memberInternalId, memberEmployeeId,
            reviewer.getId(), ISSUER, "rev-1", NOW));
        assertThrows(AccessDeniedException.class, () -> proposals.decideProposal(
            submitted.proposalPublicId(), submitted.proposalVersion(), submitted.revisionPublicId(),
            submitted.assignmentPublicId(), "APPROVE", null, reviewerCaller));
    }

    private UUID accountPublicIdOf(String subject) {
        return accountRepository.findByOidcIssuerAndOidcSubject(ISSUER, subject).orElseThrow().getPublicId();
    }

    @Test
    void outOfScopeProposalsAreUniformlyInaccessible() {
        UserAccount scopedHr = accountRepository.save(new UserAccount(UUID.randomUUID(), ISSUER, "hr-eng", NOW));
        roleRepository.save(new RoleAssignment(scopedHr.getId(), HrisRole.HR_ADMIN, "ENG",
            NOW.minusSeconds(60), NOW));
        Authentication scopedCaller = JwtAuthFixture.token(ISSUER, "hr-eng");

        UserAccount finMember = accountRepository.save(new UserAccount(UUID.randomUUID(), ISSUER, "emp-fin", NOW));
        roleRepository.save(new RoleAssignment(finMember.getId(), HrisRole.EMPLOYEE, null,
            NOW.minusSeconds(60), NOW));
        Authentication finCaller = JwtAuthFixture.token(ISSUER, "emp-fin");
        UUID finEmployeeId = bindEmployee("EMP-FIN", finMember.getId(), "emp-fin");
        employees.addAssignment(finEmployeeId, UUID.randomUUID(), "FIN", null, "L3",
            Instant.parse("2026-01-01T00:00:00Z"), null, hrCaller);

        UUID finSet = UUID.randomUUID();
        rules.publishRuleSet(finSet,
            List.of(new RulePayload("route-fin", 10, "FIN", "L3", accountPublicIdOf("hr-1"))), hrCaller);
        rules.activateRuleSet(finSet, hrCaller);

        ProposalResult created = proposals.createProposal(finEmployeeId, UUID.randomUUID(),
            List.of(new ChangePayload("personalPhone", "+62 812 345 678")), finCaller);
        ProposalResult submitted = proposals.submitProposal(created.proposalPublicId(),
            created.proposalVersion(), finCaller);
        assertEquals("PENDING", submitted.state());

        UUID missing = UUID.randomUUID();
        assertThrows(ResourceNotFoundException.class, () -> proposals.decideProposal(
            missing, 0, submitted.revisionPublicId(), submitted.assignmentPublicId(),
            "APPROVE", null, scopedCaller));
        assertThrows(ResourceNotFoundException.class, () -> proposals.decideProposal(
            submitted.proposalPublicId(), submitted.proposalVersion(), submitted.revisionPublicId(),
            submitted.assignmentPublicId(), "APPROVE", null, scopedCaller));
        assertThrows(ResourceNotFoundException.class, () -> proposals.reassignProposal(
            submitted.proposalPublicId(), submitted.proposalVersion(), submitted.assignmentPublicId(),
            reviewerTwoAccountId, "Covering leave", scopedCaller));
        assertThrows(ResourceNotFoundException.class, () -> proposals.retryRoute(
            submitted.proposalPublicId(), submitted.proposalVersion(), scopedCaller));
        assertThrows(ResourceNotFoundException.class, () -> proposals.reassignProposal(
            missing, 0, submitted.assignmentPublicId(), reviewerTwoAccountId, "Covering leave",
            scopedCaller));
        assertThrows(ResourceNotFoundException.class, () -> proposals.retryRoute(
            missing, 0, scopedCaller));

        ProposalResult returned = proposals.decideProposal(submitted.proposalPublicId(),
            submitted.proposalVersion(), submitted.revisionPublicId(), submitted.assignmentPublicId(),
            "RETURN", "Fix the format", hrCaller);
        assertEquals("RETURNED", returned.state());
        assertThrows(ResourceNotFoundException.class, () -> proposals.decideProposal(
            returned.proposalPublicId(), returned.proposalVersion(), returned.revisionPublicId(),
            returned.assignmentPublicId(), "APPROVE", null, scopedCaller));

        ProposalResult resubmitted = proposals.resubmitProposal(returned.proposalPublicId(),
            returned.proposalVersion(),
            List.of(new ChangePayload("personalPhone", "+62 813 000 111")), finCaller);
        ProposalResult approved = proposals.decideProposal(resubmitted.proposalPublicId(),
            resubmitted.proposalVersion(), resubmitted.revisionPublicId(),
            resubmitted.assignmentPublicId(), "APPROVE", null, hrCaller);
        assertEquals("APPROVED", approved.state());
        assertThrows(ResourceNotFoundException.class, () -> proposals.decideProposal(
            approved.proposalPublicId(), approved.proposalVersion(), approved.revisionPublicId(),
            approved.assignmentPublicId(), "APPROVE", null, scopedCaller));

        ProposalResult unchanged = proposals.getProposal(approved.proposalPublicId(), hrCaller).proposal();
        assertEquals("APPROVED", unchanged.state());
        assertEquals(approved.proposalVersion(), unchanged.proposalVersion());
    }
}
