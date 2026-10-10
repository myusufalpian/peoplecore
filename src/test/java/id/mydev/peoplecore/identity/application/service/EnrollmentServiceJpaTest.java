package id.mydev.peoplecore.identity.application.service;

import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.identity.application.command.EnrollmentResults.BindingResult;
import id.mydev.peoplecore.identity.application.command.EnrollmentResults.InvitationResult;
import id.mydev.peoplecore.identity.application.command.EnrollmentResults.IssuedInvitation;
import id.mydev.peoplecore.identity.application.mapper.JwtIdentityMapper;
import id.mydev.peoplecore.identity.domain.exception.ActivationThrottledException;
import id.mydev.peoplecore.identity.domain.exception.BindingConflictException;
import id.mydev.peoplecore.identity.domain.exception.InvalidRoleException;
import id.mydev.peoplecore.identity.domain.exception.InvitationConflictException;
import id.mydev.peoplecore.identity.domain.exception.InvitationExpiredException;
import id.mydev.peoplecore.identity.domain.exception.LifecycleConflictException;
import id.mydev.peoplecore.identity.domain.model.AccountBinding;
import id.mydev.peoplecore.identity.domain.model.EnrollmentInvitation;
import id.mydev.peoplecore.identity.domain.model.HrisRole;
import id.mydev.peoplecore.identity.domain.model.RoleAssignment;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.identity.domain.repository.AccountBindingRepository;
import id.mydev.peoplecore.identity.domain.repository.ActivationAttemptRepository;
import id.mydev.peoplecore.identity.domain.repository.ActivationBudgetRepository;
import id.mydev.peoplecore.identity.domain.repository.EnrollmentInvitationRepository;
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
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "peoplecore.organization.allowed-units=ENG,FIN,OPS"
})
@Transactional
class EnrollmentServiceJpaTest {

    static final String ISSUER = "https://idp.example";
    static final Instant NOW = Instant.parse("2026-10-08T10:00:00Z");

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired EnrollmentService enrollments;
    @Autowired ActivationAttemptService attemptRecorder;
    @Autowired EmployeeService employees;
    @Autowired AccountAccessService access;
    @Autowired AuthGenerationService generations;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired UserAccountRepository accountRepository;
    @Autowired RoleAssignmentRepository roleRepository;
    @Autowired EnrollmentInvitationRepository invitationRepository;
    @Autowired AccountBindingRepository bindingRepository;
    @Autowired ActivationAttemptRepository activationAttempts;
    @Autowired ActivationBudgetRepository activationBudgets;

    private Authentication hrCaller;
    private Authentication scopedCaller;
    private UUID employeeId;

    @BeforeEach
    void seedAccess() {
        UserAccount hr = accountRepository.save(new UserAccount(UUID.randomUUID(), ISSUER, "hr-1", NOW));
        roleRepository.save(new RoleAssignment(hr.getId(), HrisRole.HR_ADMIN, null, NOW.minusSeconds(60), NOW));
        roleRepository.save(new RoleAssignment(hr.getId(), HrisRole.SYSTEM_ADMIN, null, NOW.minusSeconds(60), NOW));
        hrCaller = JwtAuthFixture.token(ISSUER, "hr-1");
        UserAccount scoped = accountRepository.save(new UserAccount(UUID.randomUUID(), ISSUER, "hr-eng", NOW));
        roleRepository.save(new RoleAssignment(scoped.getId(), HrisRole.HR_ADMIN, "ENG", NOW.minusSeconds(60), NOW));
        scopedCaller = JwtAuthFixture.token(ISSUER, "hr-eng");
        employeeId = employees.create(UUID.randomUUID(), "EMP-900", hrCaller).employeeId();
    }

    @AfterEach
    void clearCaller() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    private IssuedInvitation issueFresh() {
        return enrollments.issue(employeeId, UUID.randomUUID(), "hrd@example.id",
            ISSUER, "emp-1", null, hrCaller);
    }

    private Authentication memberCaller() {
        return JwtAuthFixture.token(ISSUER, "emp-1");
    }

    @Test
    void designatedOperatorCanRestoreExplicitPrivilegedRoles() {
        IssuedInvitation issued = issueFresh();
        enrollments.activate(issued.result().invitationId(), issued.secret(), memberCaller());
        enrollments.offboard(employeeId, NOW.minusSeconds(10), null, null, hrCaller);
        enrollments.rehire(employeeId, hrCaller);
        List<String> granted = List.of(HrisRole.HR_ADMIN, HrisRole.FINANCE_PAYROLL, HrisRole.SYSTEM_ADMIN);
        var restored = enrollments.restoreAccess(employeeId, granted, null, hrCaller);
        assertEquals(granted, restored.roles());
        UserAccount account = access.requireActive(memberCaller(), NOW);
        for (String role : granted) {
            assertTrue(access.hasRole(account.getId(), role, NOW));
        }
    }

    @Test
    void ordinaryOrganizationWideHrCannotVerifyIdentityOrDelegatePrivilegedRoles() {
        IssuedInvitation issued = issueFresh();
        enrollments.activate(issued.result().invitationId(), issued.secret(), memberCaller());
        UserAccount ordinary = accountRepository.save(new UserAccount(UUID.randomUUID(), ISSUER, "ordinary-hr", NOW));
        roleRepository.save(new RoleAssignment(ordinary.getId(), HrisRole.HR_ADMIN, null, NOW.minusSeconds(60), NOW));
        Authentication caller = JwtAuthFixture.token(ISSUER, "ordinary-hr");
        assertThrows(AccessDeniedException.class, () -> enrollments.issue(employeeId, UUID.randomUUID(),
            "IDV-100", ISSUER, "other-identity", null, caller));
        assertThrows(AccessDeniedException.class, () -> enrollments.reissue(employeeId, UUID.randomUUID(),
            "IDV-101", ISSUER, "other-identity", caller));
        assertThrows(AccessDeniedException.class, () -> enrollments.rebind(employeeId, ISSUER, "other-identity", caller));
        enrollments.offboard(employeeId, NOW.minusSeconds(10), null, null, caller);
        enrollments.rehire(employeeId, caller);
        for (String role : List.of(HrisRole.HR_ADMIN, HrisRole.FINANCE_PAYROLL, HrisRole.SYSTEM_ADMIN)) {
            assertThrows(AccessDeniedException.class, () -> enrollments.restoreAccess(employeeId, List.of(role), null, caller));
        }
        enrollments.restoreAccess(employeeId, List.of(HrisRole.EMPLOYEE), null, caller);
    }

    @Test
    void issuePersistsDeliveryIntentAndRevokesOutstanding() {
        IssuedInvitation first = issueFresh();
        assertEquals(EnrollmentInvitation.DELIVERY_PENDING, first.result().deliveryStatus());
        assertTrue(EnrollmentService.matchesSecret(first.secret(),
            invitationRepository.findByPublicId(first.result().invitationId()).orElseThrow().getSecretHash()));

        IssuedInvitation second = enrollments.issue(employeeId, UUID.randomUUID(), "hrd@example.id",
            ISSUER, "emp-1", Duration.ofDays(1), hrCaller);
        assertNotEquals(first.result().invitationId(), second.result().invitationId());
        assertEquals(EnrollmentInvitation.STATUS_REVOKED,
            invitationRepository.findByPublicId(first.result().invitationId()).orElseThrow().getStatus());
        assertEquals(NOW, invitationRepository.findByPublicId(first.result().invitationId()).orElseThrow().getRevokedAt());

        IssuedInvitation renewed = enrollments.reissue(employeeId, UUID.randomUUID(), "hrd@example.id",
            ISSUER, "emp-1", hrCaller);
        assertEquals(EnrollmentInvitation.STATUS_ISSUED, renewed.result().status());
        assertEquals(EnrollmentInvitation.STATUS_REVOKED,
            invitationRepository.findByPublicId(second.result().invitationId()).orElseThrow().getStatus());

        assertThrows(IllegalArgumentException.class, () -> enrollments.issue(
            employeeId, UUID.randomUUID(), "  ", ISSUER, "emp-1", null, hrCaller));
        assertThrows(IllegalArgumentException.class, () -> enrollments.issue(
            employeeId, UUID.randomUUID(), "hrd@example.id", " ", "emp-1", null, hrCaller));
        assertThrows(IllegalArgumentException.class, () -> enrollments.issue(
            employeeId, UUID.randomUUID(), "hrd@example.id", ISSUER, " ", null, hrCaller));
        assertThrows(ResourceNotFoundException.class, () -> enrollments.issue(
            UUID.randomUUID(), UUID.randomUUID(), "hrd@example.id", ISSUER, "emp-1", null, hrCaller));
        Authentication plain = JwtAuthFixture.token(ISSUER, "nobody");
        assertThrows(AccessDeniedException.class, () -> enrollments.issue(
            employeeId, UUID.randomUUID(), "hrd@example.id", ISSUER, "emp-1", null, plain));
    }

    @Test
    void activationBindsOnlyTheExpectedPrincipal() {
        IssuedInvitation issued = issueFresh();
        long generationBefore = generations.current();
        BindingResult binding = enrollments.activate(
            issued.result().invitationId(), issued.secret(), memberCaller());
        assertEquals(employeeId, binding.employeeId());
        assertEquals(issued.result().invitationId(), binding.invitationId());
        assertTrue(generations.current() > generationBefore);
        assertTrue(access.hasRole(
            bindingRepository.findByEmployeeIdAndRevokedAtIsNull(
                employeeRepository.findByPublicId(employeeId).orElseThrow().getId())
                .orElseThrow().getAccountId(), HrisRole.EMPLOYEE, NOW));

        assertThrows(InvitationConflictException.class, () -> enrollments.activate(
            issued.result().invitationId(), issued.secret(), memberCaller()));
        assertTrue(enrollments.activationReplayAllowed(
            issued.result().invitationId(), ISSUER, "emp-1", employeeId));
        assertFalse(enrollments.activationReplayAllowed(
            issued.result().invitationId(), ISSUER, "intruder", employeeId));
        assertFalse(enrollments.activationReplayAllowed(UUID.randomUUID(), ISSUER, "emp-1", employeeId));
        IssuedInvitation pending = enrollments.issue(employeeId, UUID.randomUUID(), "hrd@example.id",
            ISSUER, "emp-2", null, hrCaller);
        assertFalse(enrollments.activationReplayAllowed(
            pending.result().invitationId(), ISSUER, "emp-2", employeeId));
    }

    @Test
    void activationRejectsForwardedExpiredAndConflictingCases() {
        IssuedInvitation issued = issueFresh();
        Authentication intruder = JwtAuthFixture.token(ISSUER, "intruder");

        assertThrows(InvitationConflictException.class, () -> enrollments.activate(
            UUID.randomUUID(), issued.secret(), intruder));
        assertThrows(InvitationConflictException.class, () -> enrollments.activate(
            issued.result().invitationId(), "wrong-secret", memberCaller()));
        assertThrows(InvitationConflictException.class, () -> enrollments.activate(
            issued.result().invitationId(), "  ", memberCaller()));
        assertThrows(InvitationConflictException.class, () -> enrollments.activate(
            issued.result().invitationId(), issued.secret(),
            JwtAuthFixture.token("https://other.example", "emp-1")));
        assertThrows(InvitationConflictException.class, () -> enrollments.activate(
            issued.result().invitationId(), issued.secret(), intruder));
        assertThrows(AuthenticationCredentialsNotFoundException.class, () -> enrollments.activate(
            issued.result().invitationId(), issued.secret(), null));

        IssuedInvitation expired = enrollments.issue(employeeId, UUID.randomUUID(), "hrd@example.id",
            ISSUER, "emp-1", Duration.ofSeconds(-1), hrCaller);
        assertThrows(InvitationExpiredException.class, () -> enrollments.activate(
            expired.result().invitationId(), expired.secret(), memberCaller()));
    }

    @Test
    void activationRejectsDoubleBindingAndTakenIdentity() {
        IssuedInvitation first = issueFresh();
        enrollments.activate(first.result().invitationId(), first.secret(), memberCaller());

        IssuedInvitation second = enrollments.issue(employeeId, UUID.randomUUID(), "hrd@example.id",
            ISSUER, "emp-9", null, hrCaller);
        assertThrows(BindingConflictException.class, () -> enrollments.activate(
            second.result().invitationId(), second.secret(),
            JwtAuthFixture.token(ISSUER, "emp-9")));

        UUID otherEmployee = employees.create(UUID.randomUUID(), "EMP-901", hrCaller).employeeId();
        IssuedInvitation other = enrollments.issue(otherEmployee, UUID.randomUUID(), "other@example.id",
            ISSUER, "emp-1", null, hrCaller);
        assertThrows(BindingConflictException.class, () -> enrollments.activate(
            other.result().invitationId(), other.secret(), memberCaller()));
    }

    @Test
    void offboardRevokesInvitesCutsAccessAndBlocksLateActivation() {
        IssuedInvitation issued = issueFresh();
        enrollments.activate(issued.result().invitationId(), issued.secret(), memberCaller());
        AccountBinding binding = bindingRepository
            .findByEmployeeIdAndRevokedAtIsNull(
                employeeRepository.findByPublicId(employeeId).orElseThrow().getId())
            .orElseThrow();

        IssuedInvitation pending = enrollments.issue(employeeId, UUID.randomUUID(), "hrd@example.id",
            ISSUER, "emp-1", null, hrCaller);

        Instant future = NOW.plusSeconds(3600);
        var offboarded = enrollments.offboard(employeeId, future, LocalTime.of(17, 0), "Asia/Jakarta", hrCaller);
        assertEquals(employeeId, offboarded.employeeId());
        assertEquals(LocalTime.of(17, 0),
            accountRepository.findById(binding.getAccountId()).orElseThrow().getCutoffTime());
        assertEquals("Asia/Jakarta",
            accountRepository.findById(binding.getAccountId()).orElseThrow().getCutoffTimezone());
        assertFalse(employeeRepository.findByPublicId(employeeId).orElseThrow().isActive());
        assertEquals(EnrollmentInvitation.STATUS_REVOKED,
            invitationRepository.findByPublicId(pending.result().invitationId()).orElseThrow().getStatus());
        assertEquals(NOW, invitationRepository.findByPublicId(pending.result().invitationId()).orElseThrow().getRevokedAt());
        access.requireActive(memberCaller(), NOW);

        enrollments.offboard(employeeId, NOW.minusSeconds(10), null, null, hrCaller);
        assertThrows(AccessDeniedException.class, () -> access.requireActive(memberCaller(), NOW));
        assertThrows(InvitationConflictException.class, () -> enrollments.activate(
            pending.result().invitationId(), pending.secret(), memberCaller()));
        assertThrows(InvitationConflictException.class, () -> enrollments.issue(
            employeeId, UUID.randomUUID(), "hrd@example.id", ISSUER, "emp-1", null, hrCaller));

        EnrollmentInvitation resurrected = invitationRepository
            .findByPublicId(pending.result().invitationId()).orElseThrow();
        org.springframework.test.util.ReflectionTestUtils.setField(resurrected, "status",
            EnrollmentInvitation.STATUS_ISSUED);
        invitationRepository.save(resurrected);
        assertThrows(InvitationConflictException.class, () -> enrollments.activate(
            pending.result().invitationId(), pending.secret(), memberCaller()));

        assertThrows(LifecycleConflictException.class, () -> enrollments.offboard(
            employeeId, NOW.plusSeconds(7200), null, null, hrCaller));
        assertThrows(AccessDeniedException.class, () -> access.requireActive(memberCaller(), NOW));
    }

    @Test
    void offboardReschedulesStillFutureCutoff() {
        IssuedInvitation issued = issueFresh();
        enrollments.activate(issued.result().invitationId(), issued.secret(), memberCaller());
        enrollments.offboard(employeeId, NOW.plusSeconds(3600), null, null, hrCaller);
        enrollments.offboard(employeeId, NOW.plusSeconds(7200), null, null, hrCaller);
        Long accountId = bindingRepository.findByEmployeeIdAndRevokedAtIsNull(
            employeeRepository.findByPublicId(employeeId).orElseThrow().getId())
            .orElseThrow().getAccountId();
        assertEquals(NOW.plusSeconds(7200),
            accountRepository.findById(accountId).orElseThrow().getAccessEndsAt());
        access.requireActive(memberCaller(), NOW);
    }

    @Test
    void offboardLeavesAlreadyInactiveAccountUntouched() {
        IssuedInvitation issued = issueFresh();
        enrollments.activate(issued.result().invitationId(), issued.secret(), memberCaller());
        enrollments.offboard(employeeId, NOW.minusSeconds(10), null, null, hrCaller);
        enrollments.rehire(employeeId, hrCaller);
        enrollments.offboard(employeeId, NOW.plusSeconds(3600), null, null, hrCaller);
        assertFalse(employeeRepository.findByPublicId(employeeId).orElseThrow().isActive());
        assertThrows(AccessDeniedException.class, () -> access.requireActive(memberCaller(), NOW));
    }

    @Test
    void rehireRestoresEmploymentOnlyAndRestoreGrantsReviewedRoles() {
        IssuedInvitation issued = issueFresh();
        BindingResult binding = enrollments.activate(
            issued.result().invitationId(), issued.secret(), memberCaller());
        Long accountId = bindingRepository.findByEmployeeIdAndRevokedAtIsNull(
            employeeRepository.findByPublicId(employeeId).orElseThrow().getId()).orElseThrow().getAccountId();
        roleRepository.save(new RoleAssignment(accountId, HrisRole.HR_ADMIN, null, NOW.minusSeconds(60), NOW));
        roleRepository.save(new RoleAssignment(accountId, HrisRole.MANAGER, null,
            NOW.plusSeconds(60), NOW.plusSeconds(7200)));

        enrollments.offboard(employeeId, NOW.minusSeconds(10), null, null, hrCaller);
        enrollments.rehire(employeeId, hrCaller);
        assertTrue(employeeRepository.findByPublicId(employeeId).orElseThrow().isActive());
        assertThrows(AccessDeniedException.class, () -> access.requireActive(memberCaller(), NOW));

        var restored = enrollments.restoreAccess(employeeId, List.of(HrisRole.EMPLOYEE), null, hrCaller);
        assertEquals(accountRepository.findById(accountId).orElseThrow().getPublicId(), restored.accountId());
        access.requireActive(memberCaller(), NOW);
        assertTrue(access.hasRole(accountId, HrisRole.EMPLOYEE, NOW));
        assertTrue(!access.hasRole(accountId, HrisRole.HR_ADMIN, NOW));
        assertTrue(!access.hasRole(accountId, HrisRole.MANAGER, NOW.plusSeconds(3600)));
        assertTrue(!access.hasRole(accountId, HrisRole.MANAGER, NOW));

        assertThrows(BindingConflictException.class,
            () -> enrollments.restoreAccess(employeeId, List.of(HrisRole.EMPLOYEE), null, hrCaller));
        assertThrows(InvalidRoleException.class,
            () -> enrollments.restoreAccess(employeeId, List.of("SUPERUSER"), null, hrCaller));
        assertThrows(InvalidRoleException.class,
            () -> enrollments.restoreAccess(employeeId, List.of(), null, hrCaller));
        assertThrows(InvalidRoleException.class,
            () -> enrollments.restoreAccess(employeeId, null, null, hrCaller));
    }

    @Test
    void restoreRequiresActiveEmployment() {
        IssuedInvitation issued = issueFresh();
        enrollments.activate(issued.result().invitationId(), issued.secret(), memberCaller());
        enrollments.offboard(employeeId, NOW.minusSeconds(10), null, null, hrCaller);
        assertThrows(BindingConflictException.class, () -> enrollments.restoreAccess(
            employeeId, List.of(HrisRole.EMPLOYEE), null, hrCaller));
    }

    @Test
    void scopedHrCannotEscalateThroughRestore() {
        IssuedInvitation issued = issueFresh();
        enrollments.activate(issued.result().invitationId(), issued.secret(), memberCaller());
        employees.addAssignment(employeeId, UUID.randomUUID(), "ENG", null, "L3",
            Instant.parse("2026-01-01T00:00:00Z"), null, hrCaller);
        enrollments.offboard(employeeId, NOW.minusSeconds(10), null, null, hrCaller);
        enrollments.rehire(employeeId, hrCaller);

        assertThrows(AccessDeniedException.class, () -> enrollments.restoreAccess(
            employeeId, List.of(HrisRole.HR_ADMIN), "ENG", scopedCaller));
        assertThrows(AccessDeniedException.class, () -> enrollments.restoreAccess(
            employeeId, List.of(HrisRole.EMPLOYEE), null, scopedCaller));
        assertThrows(AccessDeniedException.class, () -> enrollments.restoreAccess(
            employeeId, List.of(HrisRole.EMPLOYEE), "FIN", scopedCaller));

        var restored = enrollments.restoreAccess(
            employeeId, List.of(HrisRole.EMPLOYEE), "ENG", scopedCaller);
        Long restoredAccount = bindingRepository.findByEmployeeIdAndRevokedAtIsNull(
            employeeRepository.findByPublicId(employeeId).orElseThrow().getId())
            .orElseThrow().getAccountId();
        assertEquals(List.of("ENG"), roleRepository.findByAccountId(restoredAccount).stream()
            .filter(role -> HrisRole.EMPLOYEE.equals(role.getRole()) && role.getEffectiveTo() == null)
            .map(RoleAssignment::getScope)
            .toList());
        assertEquals(List.of(HrisRole.EMPLOYEE), restored.roles());
    }

    @Test
    void activationAttemptsAreBoundedAndSurviveRollback() {
        IssuedInvitation issued = issueFresh();
        JwtIdentityMapper.OidcIdentity member =
            new JwtIdentityMapper.OidcIdentity("https://idp.example", "emp-1");
        for (int attempt = 0; attempt < ActivationAttemptService.MAX_PER_PRINCIPAL_INVITATION; attempt++) {
            attemptRecorder.reserveAdmission(issued.result().invitationId(), member);
        }
        ActivationThrottledException throttled = assertThrows(ActivationThrottledException.class,
            () -> attemptRecorder.reserveAdmission(issued.result().invitationId(), member));
        assertTrue(throttled.getRetryAfterSeconds() > 0);
    }

    @Test
    void activationBudgetIsPerInvitationAndClearedOnSuccess() {
        IssuedInvitation first = issueFresh();
        JwtIdentityMapper.OidcIdentity member =
            new JwtIdentityMapper.OidcIdentity("https://idp.example", "emp-1");
        for (int attempt = 0; attempt < 4; attempt++) {
            attemptRecorder.reserveAdmission(first.result().invitationId(), member);
        }
        enrollments.activate(first.result().invitationId(), first.secret(), memberCaller());
        assertEquals(0, activationAttempts.findByInvitationPublicIdOrderByCreatedAtAsc(
            first.result().invitationId()).size());
        assertThrows(InvitationConflictException.class, () -> enrollments.activate(
            first.result().invitationId(), "wrong-again", memberCaller()));

        UUID otherEmployee = employees.create(UUID.randomUUID(), "EMP-ISO", hrCaller).employeeId();
        IssuedInvitation other = enrollments.issue(otherEmployee, UUID.randomUUID(), "other@example.id",
            ISSUER, "other-1", null, hrCaller);
        enrollments.activate(other.result().invitationId(), other.secret(),
            JwtAuthFixture.token(ISSUER, "other-1"));
    }

    @Test
    void outsiderCannotSpendRecipientBudget() {
        IssuedInvitation issued = issueFresh();
        JwtIdentityMapper.OidcIdentity outsider =
            new JwtIdentityMapper.OidcIdentity("https://idp.example", "outsider-1");
        for (int attempt = 0; attempt < ActivationAttemptService.MAX_PER_PRINCIPAL_INVITATION; attempt++) {
            attemptRecorder.reserveAdmission(issued.result().invitationId(), outsider);
        }
        JwtIdentityMapper.OidcIdentity outsiderIdentity =
            new JwtIdentityMapper.OidcIdentity(ISSUER, "outsider-1");
        assertThrows(ActivationThrottledException.class, () -> attemptRecorder.reserveAdmission(
            issued.result().invitationId(), outsiderIdentity));
        enrollments.activate(issued.result().invitationId(), issued.secret(), memberCaller());
    }

    @Test
    void deniedAdmissionSpendsNoBudget() {
        IssuedInvitation issued = issueFresh();
        JwtIdentityMapper.OidcIdentity outsider =
            new JwtIdentityMapper.OidcIdentity(ISSUER, "outsider-1");
        for (int attempt = 0; attempt < ActivationAttemptService.MAX_PER_PRINCIPAL_INVITATION; attempt++) {
            attemptRecorder.reserveAdmission(issued.result().invitationId(), outsider);
        }
        assertThrows(ActivationThrottledException.class, () ->
            attemptRecorder.reserveAdmission(issued.result().invitationId(), outsider));
        long aggregate = 0;
        for (var row : activationBudgets.findAll()) {
            if ("INVITATION".equals(row.getScope())) {
                aggregate = row.getUsed();
            }
        }
        assertEquals(ActivationAttemptService.MAX_PER_PRINCIPAL_INVITATION, aggregate);
    }

    @Test
    void sharedBudgetAdmitsVerifiedRecipient() {
        IssuedInvitation issued = issueFresh();
        for (int outsider = 0; outsider < 6; outsider++) {
            String outsiderSubject = "outsider-" + outsider;
            JwtIdentityMapper.OidcIdentity identity = new JwtIdentityMapper.OidcIdentity(
                ISSUER, outsiderSubject);
            for (int attempt = 0; attempt < ActivationAttemptService.MAX_PER_PRINCIPAL_INVITATION; attempt++) {
                attemptRecorder.reserveAdmission(issued.result().invitationId(), identity);
            }
            JwtIdentityMapper.OidcIdentity spent =
                new JwtIdentityMapper.OidcIdentity(ISSUER, outsiderSubject);
            assertThrows(ActivationThrottledException.class, () ->
                attemptRecorder.reserveAdmission(issued.result().invitationId(), spent));
        }
        enrollments.activate(issued.result().invitationId(), issued.secret(), memberCaller());
    }

    @Test
    void rotatingInvitationIdsHitPrincipalBudget() {
        JwtIdentityMapper.OidcIdentity drifter =
            new JwtIdentityMapper.OidcIdentity("https://idp.example", "drifter-1");
        int allowed = ActivationAttemptService.MAX_PER_PRINCIPAL_GLOBAL;
        for (int attempt = 0; attempt < allowed; attempt++) {
            attemptRecorder.reserveAdmission(UUID.randomUUID(), drifter);
        }
        JwtIdentityMapper.OidcIdentity drifterIdentity =
            new JwtIdentityMapper.OidcIdentity(ISSUER, "drifter-1");
        assertThrows(ActivationThrottledException.class, () ->
            attemptRecorder.reserveAdmission(UUID.randomUUID(), drifterIdentity));
        JwtIdentityMapper.OidcIdentity freshIdentity =
            new JwtIdentityMapper.OidcIdentity(ISSUER, "fresh-1");
        attemptRecorder.reserveAdmission(UUID.randomUUID(), freshIdentity);
    }

    @Test
    void scopedHrSeesSameNotFoundForOutOfScopeEmployee() {
        UUID finEmployee = employees.create(UUID.randomUUID(), "EMP-SCP", hrCaller).employeeId();
        employees.addAssignment(finEmployee, UUID.randomUUID(), "FIN", null, "L3",
            Instant.parse("2026-01-01T00:00:00Z"), null, hrCaller);
        assertThrows(ResourceNotFoundException.class, () -> enrollments.issue(
            finEmployee, UUID.randomUUID(), "hrd@example.id", ISSUER, "x-1", null, scopedCaller));
        assertThrows(ResourceNotFoundException.class, () -> enrollments.offboard(
            finEmployee, NOW.plusSeconds(60), null, null, scopedCaller));
    }

    @Test
    void rebindTransfersAccessAndKeepsAuditedHistory() {
        IssuedInvitation issued = issueFresh();
        BindingResult original = enrollments.activate(
            issued.result().invitationId(), issued.secret(), memberCaller());
        Long originalAccount = bindingRepository.findByEmployeeIdAndRevokedAtIsNull(
            employeeRepository.findByPublicId(employeeId).orElseThrow().getId())
            .orElseThrow().getAccountId();
        roleRepository.save(new RoleAssignment(originalAccount, HrisRole.HR_ADMIN, null,
            NOW.minusSeconds(60), NOW));

        var rebound = enrollments.rebind(employeeId, ISSUER, "emp-1b", hrCaller);
        Employee employee = employeeRepository.findByPublicId(employeeId).orElseThrow();
        assertEquals(2, bindingRepository.findByEmployeeId(employee.getId()).size());
        AccountBinding current = bindingRepository.findByEmployeeIdAndRevokedAtIsNull(employee.getId()).orElseThrow();
        assertEquals("emp-1b", current.getOidcSubject());
        assertEquals(current.getAccountId(), employee.getUserAccountId());
        assertEquals(current.getAccountId(),
            accountRepository.findByOidcIssuerAndOidcSubject(ISSUER, "emp-1b").orElseThrow().getId());
        assertEquals(rebound.accountId(), accountRepository.findById(current.getAccountId())
            .orElseThrow().getPublicId());
        assertThrows(AccessDeniedException.class,
            () -> access.requireActive(memberCaller(), NOW));
        Authentication replacement = JwtAuthFixture.token(ISSUER, "emp-1b");
        access.requireActive(replacement, NOW);
        assertTrue(access.hasRole(current.getAccountId(), HrisRole.EMPLOYEE, NOW));
        assertTrue(!access.hasRole(originalAccount, HrisRole.HR_ADMIN, NOW));
    }

    @Test
    void rebindRefusesOffboardedEmployee() {
        IssuedInvitation issued = issueFresh();
        enrollments.activate(issued.result().invitationId(), issued.secret(), memberCaller());
        enrollments.offboard(employeeId, NOW.minusSeconds(10), null, null, hrCaller);
        assertThrows(BindingConflictException.class, () -> enrollments.rebind(
            employeeId, ISSUER, "emp-1b", hrCaller));
    }

    @Test
    void rebindRefusesTakenAndInactiveIdentities() {
        IssuedInvitation first = issueFresh();
        enrollments.activate(first.result().invitationId(), first.secret(), memberCaller());

        UUID otherEmployee = employees.create(UUID.randomUUID(), "EMP-T1", hrCaller).employeeId();
        IssuedInvitation other = enrollments.issue(otherEmployee, UUID.randomUUID(), "other@example.id",
            ISSUER, "other-1", null, hrCaller);
        enrollments.activate(other.result().invitationId(), other.secret(),
            JwtAuthFixture.token(ISSUER, "other-1"));
        assertThrows(BindingConflictException.class, () -> enrollments.rebind(
            otherEmployee, ISSUER, "emp-1", hrCaller));

        UserAccount dormant = accountRepository.save(
            new UserAccount(UUID.randomUUID(), ISSUER, "dormant-1", NOW));
        dormant.deactivate();
        accountRepository.save(dormant);
        assertThrows(BindingConflictException.class, () -> enrollments.rebind(
            otherEmployee, ISSUER, "dormant-1", hrCaller));
    }

    @Test
    void restoreValidationRejectsBadInputAndInactiveEmployment() {
        UUID fresh = employees.create(UUID.randomUUID(), "EMP-902", hrCaller).employeeId();
        assertThrows(BindingConflictException.class, () -> enrollments.rebind(
            fresh, ISSUER, "emp-1d", hrCaller));
        assertThrows(IllegalArgumentException.class, () -> enrollments.rebind(
            employeeId, " ", "emp-1e", hrCaller));
        assertThrows(ResourceNotFoundException.class, () -> enrollments.rebind(
            UUID.randomUUID(), ISSUER, "emp-1f", hrCaller));
    }
}
