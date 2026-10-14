package id.mydev.peoplecore.approval.application.service;

import id.mydev.peoplecore.approval.application.command.ApprovalPayloads.RulePayload;
import id.mydev.peoplecore.approval.application.command.ApprovalResults.ActivationResult;
import id.mydev.peoplecore.approval.application.command.ApprovalResults.RoutePreview;
import id.mydev.peoplecore.approval.application.command.ApprovalResults.RuleSetResult;
import id.mydev.peoplecore.approval.domain.exception.RuleActivationException;
import id.mydev.peoplecore.approval.domain.exception.RulePublishException;
import id.mydev.peoplecore.approval.domain.model.ProfileRuleSet;
import id.mydev.peoplecore.approval.domain.repository.ProfileRuleActivationRepository;
import id.mydev.peoplecore.approval.domain.repository.ProfileRuleSetRepository;
import id.mydev.peoplecore.approval.domain.validation.RouteBlockReasons;
import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.identity.domain.model.AccountBinding;
import id.mydev.peoplecore.identity.domain.model.HrisRole;
import id.mydev.peoplecore.identity.domain.model.RoleAssignment;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.identity.domain.repository.AccountBindingRepository;
import id.mydev.peoplecore.identity.domain.repository.RoleAssignmentRepository;
import id.mydev.peoplecore.identity.domain.repository.UserAccountRepository;
import id.mydev.peoplecore.organization.application.service.EmployeeService;
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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "peoplecore.organization.allowed-units=ENG,FIN,OPS"
})
@Transactional
class ApprovalRuleServiceTest {

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

    @Autowired ApprovalRuleService rules;
    @Autowired EmployeeService employees;
    @Autowired ProfileRuleSetRepository ruleSetRepository;
    @Autowired ProfileRuleActivationRepository activationRepository;
    @Autowired UserAccountRepository accountRepository;
    @Autowired RoleAssignmentRepository roleRepository;
    @Autowired AccountBindingRepository bindingRepository;
    @Autowired EmployeeRepository employeeRepository;

    private Authentication hrCaller;
    private Authentication scopedCaller;
    private Authentication memberCaller;
    private UUID hrAccountId;
    private UUID reviewerAccountId;
    private Long reviewerInternalId;
    private UUID reviewerEmployeeId;
    private UUID memberEmployeeId;

    @BeforeEach
    void seedAccess() {
        UserAccount hr = accountRepository.save(new UserAccount(UUID.randomUUID(), ISSUER, "hr-1", NOW));
        roleRepository.save(new RoleAssignment(hr.getId(), HrisRole.HR_ADMIN, null, NOW.minusSeconds(60), NOW));
        hrAccountId = hr.getPublicId();
        hrCaller = JwtAuthFixture.token(ISSUER, "hr-1");

        UserAccount scoped = accountRepository.save(new UserAccount(UUID.randomUUID(), ISSUER, "hr-eng", NOW));
        roleRepository.save(new RoleAssignment(scoped.getId(), HrisRole.HR_ADMIN, "ENG", NOW.minusSeconds(60), NOW));
        scopedCaller = JwtAuthFixture.token(ISSUER, "hr-eng");

        UserAccount reviewer = accountRepository.save(new UserAccount(UUID.randomUUID(), ISSUER, "rev-1", NOW));
        roleRepository.save(new RoleAssignment(reviewer.getId(), HrisRole.HR_ADMIN, "ENG", NOW.minusSeconds(60), NOW));
        reviewerAccountId = reviewer.getPublicId();
        reviewerInternalId = reviewer.getId();

        UserAccount member = accountRepository.save(new UserAccount(UUID.randomUUID(), ISSUER, "emp-1", NOW));
        roleRepository.save(new RoleAssignment(member.getId(), HrisRole.EMPLOYEE, null, NOW.minusSeconds(60), NOW));
        memberCaller = JwtAuthFixture.token(ISSUER, "emp-1");

        reviewerEmployeeId = bindEmployee("EMP-REV", reviewer.getId(), ISSUER, "rev-1");
        memberEmployeeId = bindEmployee("EMP-OWN", member.getId(), ISSUER, "emp-1");
    }

    private UUID bindEmployee(String number, Long accountId, String issuer, String subject) {
        UUID employeePublicId = UUID.randomUUID();
        employees.create(employeePublicId, number, hrCaller);
        Employee entity = employeeRepository.findByPublicId(employeePublicId).orElseThrow();
        entity.linkAccount(accountId);
        employeeRepository.save(entity);
        bindingRepository.save(new AccountBinding(entity.getId(), employeePublicId, accountId,
            issuer, subject, NOW));
        return employeePublicId;
    }

    @AfterEach
    void clearCaller() {
        SecurityContextHolder.clearContext();
    }

    private static RulePayload payload(String ruleId, int priority, String orgUnit, UUID reviewer) {
        return new RulePayload(ruleId, priority, orgUnit, "L3", reviewer);
    }

    @Test
    void publishAndActivateHappyPath() {
        UUID setId = UUID.randomUUID();
        RuleSetResult published = rules.publishRuleSet(setId,
            List.of(payload("route-eng", 10, "ENG", reviewerAccountId),
                payload("route-fin", 5, "FIN", hrAccountId)),
            hrCaller);
        assertEquals("PUBLISHED", published.status());
        assertEquals(2, published.ruleCount());

        ActivationResult activated = rules.activateRuleSet(setId, hrCaller);
        assertEquals(setId, activated.ruleSetPublicId());

        RoutePreview preview = rules.previewRoute("ENG", "L3", memberEmployeeId, hrCaller);
        assertTrue(preview.matched());
        assertEquals("route-eng", preview.ruleId());
        assertEquals(reviewerAccountId, preview.reviewerAccountPublicId());
    }

    @Test
    void publishRejectsInvalidSnapshots() {
        assertThrows(RulePublishException.class, () -> rules.publishRuleSet(UUID.randomUUID(),
            List.of(), hrCaller));
        List<RulePayload> tooMany = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            tooMany.add(payload("r" + i, i, "ENG", reviewerAccountId));
        }
        assertThrows(RulePublishException.class,
            () -> rules.publishRuleSet(UUID.randomUUID(), tooMany, hrCaller));
        assertThrows(RulePublishException.class, () -> rules.publishRuleSet(UUID.randomUUID(),
            List.of(payload("same", 1, "ENG", reviewerAccountId),
                payload("same", 2, "ENG", reviewerAccountId)),
            hrCaller));
        assertThrows(RulePublishException.class, () -> rules.publishRuleSet(UUID.randomUUID(),
            List.of(payload("no-reviewer", 1, "ENG", null)), hrCaller));
        assertThrows(RulePublishException.class, () -> rules.publishRuleSet(UUID.randomUUID(),
            List.of(payload("unknown-reviewer", 1, "ENG", UUID.randomUUID())), hrCaller));
        UUID first = UUID.randomUUID();
        rules.publishRuleSet(first, List.of(payload("r1", 1, "ENG", reviewerAccountId)), hrCaller);
        assertThrows(RulePublishException.class, () -> rules.publishRuleSet(first,
            List.of(payload("r1", 1, "ENG", reviewerAccountId)), hrCaller));
    }

    @Test
    void publishEnforcesPublisherAndDesignationScope() {
        assertThrows(RulePublishException.class, () -> rules.publishRuleSet(UUID.randomUUID(),
            List.of(payload("fin-rule", 1, "FIN", reviewerAccountId)), scopedCaller));
        assertThrows(RulePublishException.class, () -> rules.publishRuleSet(UUID.randomUUID(),
            List.of(new RulePayload("catch-all", 1, null, null, reviewerAccountId)), scopedCaller));
        RuleSetResult scoped = rules.publishRuleSet(UUID.randomUUID(),
            List.of(payload("eng-rule", 1, "ENG", reviewerAccountId)), scopedCaller);
        assertEquals("PUBLISHED", scoped.status());
        assertThrows(AccessDeniedException.class, () -> rules.publishRuleSet(UUID.randomUUID(),
            List.of(payload("eng-rule", 1, "ENG", reviewerAccountId)), memberCaller));
    }

    @Test
    void activateRequiresPublishedConfiguration() {
        assertThrows(ResourceNotFoundException.class,
            () -> rules.activateRuleSet(UUID.randomUUID(), hrCaller));
        UUID draftId = UUID.randomUUID();
        ruleSetRepository.save(new ProfileRuleSet(draftId, NOW));
        assertThrows(RuleActivationException.class, () -> rules.activateRuleSet(draftId, hrCaller));
        UUID setId = UUID.randomUUID();
        rules.publishRuleSet(setId, List.of(payload("r1", 1, "ENG", reviewerAccountId)), hrCaller);
        ActivationResult first = rules.activateRuleSet(setId, hrCaller);
        ActivationResult second = rules.activateRuleSet(setId, hrCaller);
        assertEquals(first.ruleSetPublicId(), second.ruleSetPublicId());
    }

    @Test
    void activateRequiresOrganizationWideAuthority() {
        UUID wideId = UUID.randomUUID();
        rules.publishRuleSet(wideId, List.of(
            payload("route-eng", 10, "ENG", reviewerAccountId),
            payload("route-fin", 5, "FIN", hrAccountId)), hrCaller);
        rules.activateRuleSet(wideId, hrCaller);
        long activationsBefore = activationRepository.count();

        UUID engOnlyId = UUID.randomUUID();
        rules.publishRuleSet(engOnlyId, List.of(payload("route-eng", 10, "ENG", reviewerAccountId)),
            scopedCaller);
        assertThrows(RuleActivationException.class,
            () -> rules.activateRuleSet(engOnlyId, scopedCaller));
        assertEquals(activationsBefore, activationRepository.count());

        RoutePreview finRoute = rules.previewRoute("FIN", "L3", memberEmployeeId, hrCaller);
        assertTrue(finRoute.matched());
        assertEquals("route-fin", finRoute.ruleId());
        assertEquals(wideId, finRoute.ruleSetPublicId());
    }

    @Test
    void activateReplayRequiresOrganizationWideAuthority() {
        UUID setId = UUID.randomUUID();
        rules.publishRuleSet(setId, List.of(payload("eng-route", 1, "ENG", reviewerAccountId)), hrCaller);
        rules.activateRuleSet(setId, hrCaller);
        rules.verifyActivateReplayScope(setId, hrCaller);
        assertThrows(AccessDeniedException.class,
            () -> rules.verifyActivateReplayScope(setId, scopedCaller));
        assertThrows(ResourceNotFoundException.class,
            () -> rules.verifyActivateReplayScope(UUID.randomUUID(), hrCaller));

        RoleAssignment grant = roleRepository.findByAccountId(hrAccountInternalId()).stream()
            .filter(entry -> HrisRole.HR_ADMIN.equals(entry.getRole()) && entry.getScope() == null)
            .findFirst().orElseThrow();
        roleRepository.save(new RoleAssignment(hrAccountInternalId(), HrisRole.HR_ADMIN, "ENG",
            NOW.minusSeconds(60), NOW));
        grant.endAt(NOW.minusSeconds(1));
        roleRepository.save(grant);
        assertThrows(AccessDeniedException.class,
            () -> rules.verifyActivateReplayScope(setId, hrCaller));
    }

    private Long hrAccountInternalId() {
        return accountRepository.findByPublicId(hrAccountId).orElseThrow().getId();
    }

    @Test
    void previewReportsBlockageWithoutReserving() {
        UUID setId = UUID.randomUUID();
        rules.publishRuleSet(setId, List.of(payload("eng-only", 1, "ENG", reviewerAccountId)), hrCaller);
        rules.activateRuleSet(setId, hrCaller);

        RoutePreview noMatch = rules.previewRoute("FIN", "L3", memberEmployeeId, hrCaller);
        assertFalse(noMatch.matched());
        assertEquals(RouteBlockReasons.NO_MATCH, noMatch.blockedReason());

        assertThrows(AccessDeniedException.class,
            () -> rules.previewRoute("OPS", "L2", memberEmployeeId, scopedCaller));
        assertThrows(AccessDeniedException.class,
            () -> rules.previewRoute("ENG", "L3", memberEmployeeId, memberCaller));
    }

    @Test
    void previewDetectsReviewerLosingEligibility() {
        UUID setId = UUID.randomUUID();
        rules.publishRuleSet(setId, List.of(payload("eng-route", 1, "ENG", reviewerAccountId)), hrCaller);
        rules.activateRuleSet(setId, hrCaller);
        RoleAssignment grant = roleRepository.findByAccountId(reviewerInternalId).stream()
            .filter(entry -> HrisRole.HR_ADMIN.equals(entry.getRole()))
            .findFirst().orElseThrow();
        grant.endAt(NOW.minusSeconds(1));
        roleRepository.save(grant);

        RoutePreview ineligible = rules.previewRoute("ENG", "L3", memberEmployeeId, hrCaller);
        assertFalse(ineligible.matched());
        assertEquals(RouteBlockReasons.REVIEWER_INELIGIBLE, ineligible.blockedReason());
    }

    @Test
    void previewBlocksSelfReview() {
        UUID setId = UUID.randomUUID();
        rules.publishRuleSet(setId, List.of(payload("eng-route", 1, "ENG", reviewerAccountId)), hrCaller);
        rules.activateRuleSet(setId, hrCaller);
        RoutePreview selfReview = rules.previewRoute("ENG", "L3", reviewerEmployeeId, hrCaller);
        assertFalse(selfReview.matched());
        assertEquals(RouteBlockReasons.SELF_REVIEW_PROHIBITED, selfReview.blockedReason());
    }

    @Test
    void inactiveReviewerBlocksRouting() {
        UUID setId = UUID.randomUUID();
        rules.publishRuleSet(setId, List.of(payload("eng-route", 1, "ENG", reviewerAccountId)), hrCaller);
        rules.activateRuleSet(setId, hrCaller);
        UserAccount reviewer = accountRepository.findByPublicId(reviewerAccountId).orElseThrow();
        reviewer.deactivate();
        accountRepository.save(reviewer);
        RoutePreview preview = rules.previewRoute("ENG", "L3", memberEmployeeId, hrCaller);
        assertFalse(preview.matched());
        assertEquals(RouteBlockReasons.NO_DESIGNATED_REVIEWER, preview.blockedReason());
    }

    @Test
    void previewWithoutActivationIsBlocked() {
        RoutePreview preview = rules.previewRoute("ENG", "L3", memberEmployeeId, hrCaller);
        assertFalse(preview.matched());
        assertEquals(RouteBlockReasons.NO_ACTIVE_CONFIGURATION, preview.blockedReason());
    }

    @Test
    void publishRejectsRemovedReviewerDesignation() {
        UUID setId = UUID.randomUUID();
        rules.publishRuleSet(setId, List.of(payload("eng-route", 1, "ENG", reviewerAccountId)), hrCaller);
        rules.activateRuleSet(setId, hrCaller);
        bindingRepository.findByAccountIdAndRevokedAtIsNull(
            accountRepository.findByPublicId(reviewerAccountId).orElseThrow().getId())
            .ifPresent(bindingRepository::delete);
        UserAccount reviewer = accountRepository.findByPublicId(reviewerAccountId).orElseThrow();
        roleRepository.findByAccountId(reviewer.getId()).forEach(roleRepository::delete);
        accountRepository.delete(reviewer);
        assertThrows(RulePublishException.class, () -> rules.publishRuleSet(UUID.randomUUID(),
            List.of(payload("orphan-rule", 1, "ENG", reviewerAccountId)), hrCaller));
        RoutePreview preview = rules.previewRoute("ENG", "L3", memberEmployeeId, hrCaller);
        assertFalse(preview.matched());
        assertEquals(RouteBlockReasons.NO_DESIGNATED_REVIEWER, preview.blockedReason());
    }

    @Test
    void publishValidatesRuleIdentifiersAndUnits() {
        assertThrows(RulePublishException.class, () -> rules.publishRuleSet(UUID.randomUUID(),
            List.of(payload("  ", 1, "ENG", reviewerAccountId)), hrCaller));
        List<RulePayload> nullElement = new ArrayList<>();
        nullElement.add(null);
        assertThrows(RulePublishException.class,
            () -> rules.publishRuleSet(UUID.randomUUID(), nullElement, hrCaller));
        assertThrows(RulePublishException.class, () -> rules.publishRuleSet(UUID.randomUUID(),
            List.of(payload("x".repeat(65), 1, "ENG", reviewerAccountId)), hrCaller));
        assertThrows(id.mydev.peoplecore.organization.domain.exception.UnknownOrgUnitException.class,
            () -> rules.publishRuleSet(UUID.randomUUID(),
                List.of(payload("nope-rule", 1, "NOPE", reviewerAccountId)), hrCaller));
        RuleSetResult wildcard = rules.publishRuleSet(UUID.randomUUID(),
            List.of(new RulePayload("catch-all", 1, null, null, hrAccountId)), hrCaller);
        assertEquals("PUBLISHED", wildcard.status());
    }

    @Test
    void publishRejectsInactiveAndIneligibleReviewers() {
        UserAccount reviewer = accountRepository.findByPublicId(reviewerAccountId).orElseThrow();
        reviewer.deactivate();
        accountRepository.save(reviewer);
        assertThrows(RulePublishException.class, () -> rules.publishRuleSet(UUID.randomUUID(),
            List.of(payload("stale-rule", 1, "ENG", reviewerAccountId)), hrCaller));
        reviewer.reactivate();
        accountRepository.save(reviewer);

        UserAccount member = accountRepository.findByOidcIssuerAndOidcSubject(ISSUER, "emp-1").orElseThrow();
        assertThrows(RulePublishException.class, () -> rules.publishRuleSet(UUID.randomUUID(),
            List.of(payload("member-rule", 1, "ENG", member.getPublicId())), hrCaller));
        assertThrows(RulePublishException.class, () -> rules.publishRuleSet(UUID.randomUUID(),
            List.of(payload("fin-rule", 1, "FIN", reviewerAccountId)), hrCaller));
    }

    @Test
    void previewWithoutRequesterSkipsSelfReview() {
        UUID setId = UUID.randomUUID();
        rules.publishRuleSet(setId, List.of(payload("eng-route", 1, "ENG", reviewerAccountId)), hrCaller);
        rules.activateRuleSet(setId, hrCaller);
        RoutePreview preview = rules.previewRoute("ENG", "L3", null, hrCaller);
        assertTrue(preview.matched());
        assertEquals("eng-route", preview.ruleId());
    }

    @Test
    void replayScopeFollowsCurrentConfigurationAuthority() {
        UUID setId = UUID.randomUUID();
        rules.publishRuleSet(setId, List.of(payload("eng-route", 1, "ENG", reviewerAccountId)), hrCaller);
        rules.activateRuleSet(setId, hrCaller);
        rules.verifyPublishReplayScope(setId, hrCaller);
        rules.verifyPublishReplayScope(setId, scopedCaller);
        assertThrows(ResourceNotFoundException.class,
            () -> rules.verifyPublishReplayScope(UUID.randomUUID(), hrCaller));

        UUID finSet = UUID.randomUUID();
        rules.publishRuleSet(finSet, List.of(payload("fin-route", 1, "FIN", hrAccountId)), hrCaller);
        assertThrows(AccessDeniedException.class,
            () -> rules.verifyPublishReplayScope(finSet, scopedCaller));

        UUID wildSet = UUID.randomUUID();
        rules.publishRuleSet(wildSet,
            List.of(new RulePayload("catch-all", 1, null, null, hrAccountId)), hrCaller);
        rules.verifyPublishReplayScope(wildSet, hrCaller);
        assertThrows(AccessDeniedException.class,
            () -> rules.verifyPublishReplayScope(wildSet, scopedCaller));
    }

    @Test
    void publishRejectsNullRuleIdentifier() {
        List<RulePayload> nullId = new ArrayList<>();
        nullId.add(new RulePayload(null, 1, "ENG", "L3", reviewerAccountId));
        assertThrows(RulePublishException.class,
            () -> rules.publishRuleSet(UUID.randomUUID(), nullId, hrCaller));
    }

    @Test
    void tiedRulesStayAmbiguous() {
        UUID setId = UUID.randomUUID();
        rules.publishRuleSet(setId, List.of(
            payload("tie-a", 10, "ENG", reviewerAccountId),
            payload("tie-b", 10, "ENG", reviewerAccountId)), hrCaller);
        rules.activateRuleSet(setId, hrCaller);
        RoutePreview preview = rules.previewRoute("ENG", "L3", memberEmployeeId, hrCaller);
        assertFalse(preview.matched());
        assertEquals(RouteBlockReasons.AMBIGUOUS_RULE, preview.blockedReason());
    }
}
