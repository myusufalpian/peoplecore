package id.mydev.peoplecore.organization.application.service;

import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.identity.application.service.AuthGenerationService;
import id.mydev.peoplecore.identity.domain.model.HrisRole;
import id.mydev.peoplecore.identity.domain.model.RoleAssignment;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.identity.domain.repository.AccountBindingRepository;
import id.mydev.peoplecore.identity.domain.repository.RoleAssignmentRepository;
import id.mydev.peoplecore.identity.domain.repository.UserAccountRepository;
import id.mydev.peoplecore.organization.application.command.EmployeeResults.AssignmentResult;
import id.mydev.peoplecore.organization.application.command.EmployeeResults.EmployeeResult;
import id.mydev.peoplecore.organization.application.policy.OrgUnitPolicy;
import id.mydev.peoplecore.organization.domain.exception.AssignmentOverlapException;
import id.mydev.peoplecore.organization.domain.exception.AssignmentValidationException;
import id.mydev.peoplecore.organization.domain.exception.EmployeeNumberConflictException;
import id.mydev.peoplecore.organization.domain.exception.UnknownOrgUnitException;
import id.mydev.peoplecore.organization.domain.model.Employee;
import id.mydev.peoplecore.organization.domain.repository.EmployeeAssignmentRepository;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "peoplecore.organization.allowed-units=ENG,FIN,OPS"
})
@Transactional
class EmployeeServiceJpaTest {

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

    @Autowired EmployeeService employees;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired EmployeeAssignmentRepository assignmentRepository;
    @Autowired UserAccountRepository accountRepository;
    @Autowired RoleAssignmentRepository roleRepository;
    @Autowired AccountBindingRepository bindingRepository;
    @Autowired AuthGenerationService generations;

    private Authentication hrCaller;
    private Authentication scopedCaller;
    private Authentication employeeCaller;
    private UUID ownEmployeeId;

    @BeforeEach
    void seedAccess() {
        UserAccount hr = accountRepository.save(new UserAccount(UUID.randomUUID(), ISSUER, "hr-1", NOW));
        roleRepository.save(new RoleAssignment(hr.getId(), HrisRole.HR_ADMIN, null, NOW.minusSeconds(60), NOW));
        hrCaller = JwtAuthFixture.token(ISSUER, "hr-1");

        UserAccount scoped = accountRepository.save(new UserAccount(UUID.randomUUID(), ISSUER, "hr-eng", NOW));
        roleRepository.save(new RoleAssignment(scoped.getId(), HrisRole.HR_ADMIN, "ENG", NOW.minusSeconds(60), NOW));
        scopedCaller = JwtAuthFixture.token(ISSUER, "hr-eng");

        UserAccount member = accountRepository.save(new UserAccount(UUID.randomUUID(), ISSUER, "emp-1", NOW));
        roleRepository.save(new RoleAssignment(member.getId(), HrisRole.EMPLOYEE, null, NOW.minusSeconds(60), NOW));
        employeeCaller = JwtAuthFixture.token(ISSUER, "emp-1");

        EmployeeResult own = employees.create(UUID.randomUUID(), "EMP-OWN", hrCaller);
        ownEmployeeId = own.employeeId();
        Employee entity = employeeRepository.findByPublicId(ownEmployeeId).orElseThrow();
        entity.linkAccount(member.getId());
        employeeRepository.save(entity);
        bindingRepository.save(new id.mydev.peoplecore.identity.domain.model.AccountBinding(
            entity.getId(), ownEmployeeId, member.getId(), ISSUER, "emp-1", NOW));
    }

    @AfterEach
    void clearCaller() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createPersistsAndBumpsGeneration() {
        EmployeeResult created = employees.create(UUID.randomUUID(), "EMP-001", hrCaller);
        assertEquals("ACTIVE", created.status());
        assertEquals(2L, generations.current());
        assertTrue(employeeRepository.findByEmployeeNumber("EMP-001").isPresent());
        assertThrows(EmployeeNumberConflictException.class,
            () -> employees.create(UUID.randomUUID(), "EMP-001", hrCaller));
        assertThrows(IllegalArgumentException.class,
            () -> employees.create(UUID.randomUUID(), "  ", hrCaller));
        assertThrows(AccessDeniedException.class,
            () -> employees.create(UUID.randomUUID(), "EMP-002", employeeCaller));
        assertThrows(AccessDeniedException.class,
            () -> employees.create(UUID.randomUUID(), "EMP-003", scopedCaller));
    }

    @Test
    void updateNumberKeepsUniqueness() {
        EmployeeResult created = employees.create(UUID.randomUUID(), "EMP-100", hrCaller);
        EmployeeResult updated = employees.updateNumber(created.employeeId(), "EMP-101", hrCaller);
        assertEquals("EMP-101", updated.employeeNumber());
        assertThrows(EmployeeNumberConflictException.class,
            () -> employees.updateNumber(created.employeeId(), "EMP-OWN", hrCaller));
        assertThrows(IllegalArgumentException.class,
            () -> employees.updateNumber(created.employeeId(), " ", hrCaller));
        assertThrows(ResourceNotFoundException.class,
            () -> employees.updateNumber(UUID.randomUUID(), "EMP-102", hrCaller));
        assertThrows(AccessDeniedException.class,
            () -> employees.updateNumber(created.employeeId(), "EMP-103", employeeCaller));
    }

    @Test
    void getEnforcesOwnershipAndScopeForNonHr() {
        EmployeeResult created = employees.create(UUID.randomUUID(), "EMP-110", hrCaller);
        employees.addAssignment(created.employeeId(), UUID.randomUUID(), "FIN", null, "L3",
            Instant.parse("2026-01-01T00:00:00Z"), null, hrCaller);
        assertEquals("EMP-110", employees.get(created.employeeId(), hrCaller).getEmployeeNumber());
        assertEquals("EMP-OWN", employees.get(ownEmployeeId, employeeCaller).getEmployeeNumber());
        assertThrows(ResourceNotFoundException.class,
            () -> employees.get(created.employeeId(), employeeCaller));
        assertThrows(ResourceNotFoundException.class,
            () -> employees.get(UUID.randomUUID(), hrCaller));
        assertThrows(AuthenticationCredentialsNotFoundException.class,
            () -> employees.get(created.employeeId(), null));
    }

    @Test
    void assignmentsRejectOverlapUnknownUnitsAndBadIntervals() {
        EmployeeResult created = employees.create(UUID.randomUUID(), "EMP-200", hrCaller);
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant mid = Instant.parse("2026-06-01T00:00:00Z");
        Instant to = Instant.parse("2026-12-01T00:00:00Z");
        AssignmentResult first = employees.addAssignment(created.employeeId(), UUID.randomUUID(),
            "ENG", null, "L3", from, mid, hrCaller);
        assertEquals("ENG", first.orgUnit());
        assertThrows(AssignmentOverlapException.class, () -> employees.addAssignment(
            created.employeeId(), UUID.randomUUID(), "FIN", null, "L4",
            Instant.parse("2026-03-01T00:00:00Z"), to, hrCaller));
        AssignmentResult adjacent = employees.addAssignment(created.employeeId(), UUID.randomUUID(),
            "FIN", null, "L4", mid, to, hrCaller);
        assertEquals("L4", adjacent.jobLevel());
        assertEquals(2, employees.listAssignments(created.employeeId(), hrCaller).size());
        assertThrows(AccessDeniedException.class, () -> employees.addAssignment(
            created.employeeId(), UUID.randomUUID(), "OPS", null, "L2", to, null, employeeCaller));
        assertThrows(IllegalArgumentException.class, () -> employees.addAssignment(
            created.employeeId(), UUID.randomUUID(), " ", null, "L2", to, null, hrCaller));
        assertThrows(ResourceNotFoundException.class, () -> employees.addAssignment(
            UUID.randomUUID(), UUID.randomUUID(), "OPS", null, "L2", to, null, hrCaller));
        assertThrows(ResourceNotFoundException.class, () -> employees.addAssignment(
            created.employeeId(), UUID.randomUUID(), "OPS", UUID.randomUUID(), "L2", to, null, hrCaller));
        assertThrows(UnknownOrgUnitException.class, () -> employees.addAssignment(
            created.employeeId(), UUID.randomUUID(), "NOPE", null, "L2",
            Instant.parse("2027-01-01T00:00:00Z"), null, hrCaller));
        assertThrows(AssignmentValidationException.class, () -> employees.addAssignment(
            created.employeeId(), UUID.randomUUID(), "OPS", null, "L2", to, to, hrCaller));
        assertThrows(AssignmentValidationException.class, () -> employees.addAssignment(
            created.employeeId(), UUID.randomUUID(), "OPS", null, "L2", null, null, hrCaller));
    }

    @Test
    void currentScopeFollowsEffectiveAssignmentOnly() {
        EmployeeResult created = employees.create(UUID.randomUUID(), "EMP-EFF", hrCaller);
        Long internalId = employeeRepository.findByPublicId(created.employeeId()).orElseThrow().getId();
        assertTrue(employees.currentOrgUnit(internalId, NOW).isEmpty());
        employees.addAssignment(created.employeeId(), UUID.randomUUID(), "ENG", null, "L3",
            Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-06-01T00:00:00Z"), hrCaller);
        employees.addAssignment(created.employeeId(), UUID.randomUUID(), "FIN", null, "L4",
            Instant.parse("2026-06-01T00:00:00Z"), null, hrCaller);
        assertEquals("ENG", employees.currentOrgUnit(internalId, Instant.parse("2026-03-01T00:00:00Z")).orElse(null));
        assertEquals("FIN", employees.currentOrgUnit(internalId, Instant.parse("2026-09-01T00:00:00Z")).orElse(null));
        assertTrue(employees.currentOrgUnit(internalId, Instant.parse("2025-01-01T00:00:00Z")).isEmpty());
    }

    @Test
    void expiredFinalAssignmentLeavesNoCurrentScope() {
        EmployeeResult created = employees.create(UUID.randomUUID(), "EMP-EXP", hrCaller);
        Long internalId = employeeRepository.findByPublicId(created.employeeId()).orElseThrow().getId();
        employees.addAssignment(created.employeeId(), UUID.randomUUID(), "ENG", null, "L3",
            Instant.parse("2025-01-01T00:00:00Z"), Instant.parse("2025-06-01T00:00:00Z"), hrCaller);
        assertTrue(employees.currentOrgUnit(internalId, NOW).isEmpty());
        assertThrows(ResourceNotFoundException.class, () -> employees.get(created.employeeId(), scopedCaller));
    }

    @Test
    void selfReadRequiresEffectiveRole() {
        UserAccount bare = accountRepository.save(new UserAccount(UUID.randomUUID(), ISSUER, "bare-1", NOW));
        Authentication bareCaller = JwtAuthFixture.token(ISSUER, "bare-1");
        Employee own = employeeRepository.findByPublicId(ownEmployeeId).orElseThrow();
        own.linkAccount(bare.getId());
        employeeRepository.save(own);
        bindingRepository.save(new id.mydev.peoplecore.identity.domain.model.AccountBinding(
            own.getId(), ownEmployeeId, bare.getId(), ISSUER, "bare-1", NOW));
        assertThrows(ResourceNotFoundException.class, () -> employees.get(ownEmployeeId, bareCaller));
        assertThrows(ResourceNotFoundException.class,
            () -> employees.listAssignments(ownEmployeeId, bareCaller));
        roleRepository.save(new RoleAssignment(bare.getId(), HrisRole.EMPLOYEE, null,
            NOW.minusSeconds(60), NOW));
        assertEquals("EMP-OWN", employees.get(ownEmployeeId, bareCaller).getEmployeeNumber());
    }

    @Test
    void scopedHrManagesOnlyItsUnit() {
        EmployeeResult eng = employees.create(UUID.randomUUID(), "EMP-ENG", hrCaller);
        employees.addAssignment(eng.employeeId(), UUID.randomUUID(), "ENG", null, "L3",
            Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-06-01T00:00:00Z"), hrCaller);
        AssignmentResult added = employees.addAssignment(eng.employeeId(), UUID.randomUUID(), "ENG", null, "L4",
            Instant.parse("2026-06-01T00:00:00Z"), null, scopedCaller);
        assertEquals("L4", added.jobLevel());
        assertEquals("EMP-ENG", employees.get(eng.employeeId(), scopedCaller).getEmployeeNumber());
        assertThrows(ResourceNotFoundException.class, () -> employees.addAssignment(
            eng.employeeId(), UUID.randomUUID(), "FIN", null, "L4",
            Instant.parse("2027-01-01T00:00:00Z"), null, scopedCaller));
    }

    @Test
    void scopedHrCannotTouchEmployeeOutsideItsScope() {
        EmployeeResult fin = employees.create(UUID.randomUUID(), "EMP-FIN", hrCaller);
        employees.addAssignment(fin.employeeId(), UUID.randomUUID(), "FIN", null, "L3",
            Instant.parse("2026-01-01T00:00:00Z"), null, hrCaller);
        assertThrows(ResourceNotFoundException.class, () -> employees.get(fin.employeeId(), scopedCaller));
        assertThrows(ResourceNotFoundException.class, () -> employees.addAssignment(
            fin.employeeId(), UUID.randomUUID(), "ENG", null, "L3",
            Instant.parse("2027-01-01T00:00:00Z"), null, scopedCaller));
        assertThrows(ResourceNotFoundException.class, () -> employees.updateNumber(
            fin.employeeId(), "EMP-FIN-X", scopedCaller));
    }

    @Test
    void managerMustExistAndDifferFromEmployee() {
        EmployeeResult lead = employees.create(UUID.randomUUID(), "EMP-300", hrCaller);
        EmployeeResult member = employees.create(UUID.randomUUID(), "EMP-301", hrCaller);
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        AssignmentResult assigned = employees.addAssignment(member.employeeId(), UUID.randomUUID(), "ENG",
            lead.employeeId(), "L2", from, null, hrCaller);
        assertEquals("L2", assigned.jobLevel());
        assertThrows(AssignmentValidationException.class, () -> employees.addAssignment(
            member.employeeId(), UUID.randomUUID(), "ENG", member.employeeId(), "L2",
            Instant.parse("2027-01-01T00:00:00Z"), null, hrCaller));
    }

    @Test
    void orgUnitPolicyDeniesUnknownAndUnconfigured() {
        OrgUnitPolicy policy = new OrgUnitPolicy("ENG, FIN");
        policy.requireAllowed("ENG");
        policy.requireAllowed("FIN");
        assertThrows(UnknownOrgUnitException.class, () -> policy.requireAllowed("NOPE"));
        assertThrows(IllegalStateException.class, () -> new OrgUnitPolicy("").requireAllowed("ENG"));
        assertThrows(IllegalStateException.class, () -> new OrgUnitPolicy(" , ").requireAllowed("ENG"));
    }
}
