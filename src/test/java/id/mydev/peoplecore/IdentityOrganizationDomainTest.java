package id.mydev.peoplecore;

import id.mydev.peoplecore.common.api.ApiData;
import id.mydev.peoplecore.common.command.CommandIds;
import id.mydev.peoplecore.identity.application.command.EnrollmentResults.BindingResult;
import id.mydev.peoplecore.identity.application.command.EnrollmentResults.InvitationResult;
import id.mydev.peoplecore.identity.application.command.EnrollmentResults.IssuedInvitation;
import id.mydev.peoplecore.identity.infrastructure.config.SecurityJwtConfig;
import id.mydev.peoplecore.identity.domain.model.AccountBinding;
import id.mydev.peoplecore.identity.application.service.AccountAccessService;
import id.mydev.peoplecore.identity.domain.model.AuthGeneration;
import id.mydev.peoplecore.identity.domain.model.EnrollmentInvitation;
import id.mydev.peoplecore.identity.application.service.EnrollmentService;
import id.mydev.peoplecore.identity.application.mapper.JwtIdentityMapper;
import id.mydev.peoplecore.identity.domain.model.RoleAssignment;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.identity.api.mapper.EnrollmentDtoMapper;
import id.mydev.peoplecore.organization.domain.model.Employee;
import id.mydev.peoplecore.organization.domain.model.EmployeeAssignment;
import id.mydev.peoplecore.organization.api.mapper.EmployeeDtoMapper;
import id.mydev.peoplecore.support.JwtAuthFixture;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdentityOrganizationDomainTest {

    private static final Instant NOW = Instant.parse("2026-10-08T10:00:00Z");

    @Test
    void accountCutoffBoundaryIsExclusive() {
        UserAccount account = new UserAccount(UUID.randomUUID(), "iss", "sub", NOW);
        assertTrue(account.isActiveAt(NOW));
        account.applyCutoff(NOW.plusSeconds(60), LocalTime.of(17, 0), "Asia/Jakarta", NOW);
        assertTrue(account.isActiveAt(NOW));
        assertEquals(LocalTime.of(17, 0), account.getCutoffTime());
        assertEquals("Asia/Jakarta", account.getCutoffTimezone());
        assertFalse(account.isActiveAt(NOW.plusSeconds(60)));
        assertFalse(account.isActiveAt(NOW.plusSeconds(3600)));
        account.deactivate();
        assertFalse(account.isActiveAt(NOW));
        account.reactivate();
        assertTrue(account.isActiveAt(NOW));
        assertEquals(null, account.getAccessEndsAt());
        account.recordLogin(NOW);
        assertEquals(NOW, account.getLastLoginAt());
        assertEquals("iss", account.getOidcIssuer());
        assertEquals("sub", account.getOidcSubject());
        assertEquals(NOW, account.getCreatedAt());
    }

    @Test
    void roleEffectivenessHonoursBoundaries() {
        RoleAssignment assignment = new RoleAssignment(1L, "HR_ADMIN", null, NOW, NOW);
        assertTrue(assignment.isEffectiveAt(NOW));
        assertFalse(assignment.isEffectiveAt(NOW.minusSeconds(1)));
        assignment.endAt(NOW.plusSeconds(60));
        assertTrue(assignment.isEffectiveAt(NOW));
        assertFalse(assignment.isEffectiveAt(NOW.plusSeconds(60)));
        assertEquals(1L, assignment.getAccountId());
        assertEquals("HR_ADMIN", assignment.getRole());
        assertEquals(null, assignment.getScope());
        assertEquals(NOW, assignment.getEffectiveFrom());
        assertEquals(NOW.plusSeconds(60), assignment.getEffectiveTo());
        assertEquals(NOW, assignment.getCreatedAt());
    }

    @Test
    void identityComesOnlyFromVerifiedJwtPrincipal() {
        var fromJwt = AccountAccessService.identityOf(JwtAuthFixture.token("https://idp.example", "user-1"));
        assertEquals("https://idp.example", fromJwt.issuer());
        assertEquals("user-1", fromJwt.subject());

        var basic = UsernamePasswordAuthenticationToken.authenticated("actor", "", List.of());
        assertThrows(AccessDeniedException.class, () -> AccountAccessService.identityOf(basic));
        assertThrows(AuthenticationCredentialsNotFoundException.class,
            () -> AccountAccessService.identityOf(null));
        assertThrows(IllegalArgumentException.class,
            () -> new JwtIdentityMapper.OidcIdentity(" ", "sub"));
        assertThrows(IllegalArgumentException.class,
            () -> new JwtIdentityMapper.OidcIdentity(null, "sub"));
        assertThrows(IllegalArgumentException.class,
            () -> new JwtIdentityMapper.OidcIdentity("iss", " "));
        Jwt noIssuer = Jwt.withTokenValue("token").header("alg", "none")
            .subject("user-1").issuedAt(NOW).expiresAt(NOW.plusSeconds(300)).build();
        assertThrows(IllegalArgumentException.class,
            () -> JwtIdentityMapper.toIdentity(noIssuer));
        assertThrows(NullPointerException.class, () -> JwtIdentityMapper.toIdentity(null));
    }

    @Test
    void invitationLifecycleStates() {
        EnrollmentInvitation invitation = new EnrollmentInvitation(UUID.randomUUID(), 7L,
            "hrd@example.id", "https://idp.example", "user-1", "hash", NOW.plusSeconds(60), NOW);
        assertEquals(EnrollmentInvitation.STATUS_ISSUED, invitation.getStatus());
        assertEquals(EnrollmentInvitation.DELIVERY_PENDING, invitation.getDeliveryStatus());
        assertEquals("https://idp.example", invitation.getExpectedIssuer());
        assertEquals("user-1", invitation.getExpectedSubject());
        assertTrue(invitation.isUsableAt(NOW));
        assertFalse(invitation.isUsableAt(NOW.plusSeconds(60)));
        invitation.markDeliverySent();
        assertEquals(EnrollmentInvitation.DELIVERY_SENT, invitation.getDeliveryStatus());
        invitation.consume(NOW);
        assertEquals(EnrollmentInvitation.STATUS_CONSUMED, invitation.getStatus());
        assertFalse(invitation.isUsableAt(NOW));
        assertEquals(NOW, invitation.getIssuedAt());
        assertEquals(NOW, invitation.getConsumedAt());
        invitation.revoke(NOW);
        assertEquals(EnrollmentInvitation.STATUS_CONSUMED, invitation.getStatus());

        EnrollmentInvitation fresh = new EnrollmentInvitation(UUID.randomUUID(), 7L,
            "hrd@example.id", "https://idp.example", "user-1", "hash", NOW.plusSeconds(60), NOW);
        fresh.revoke(NOW);
        assertEquals(NOW, fresh.getRevokedAt());
        fresh.revoke(NOW.plusSeconds(60));
        assertEquals(NOW, fresh.getRevokedAt());
        assertNull(invitation.getRevokedAt());
        assertEquals(EnrollmentInvitation.STATUS_REVOKED, fresh.getStatus());
        assertThrows(IllegalArgumentException.class, () -> new EnrollmentInvitation(UUID.randomUUID(), 7L,
            "hrd@example.id", " ", "user-1", "hash", NOW.plusSeconds(60), NOW));
        assertThrows(IllegalArgumentException.class, () -> new EnrollmentInvitation(UUID.randomUUID(), 7L,
            "hrd@example.id", "https://idp.example", null, "hash", NOW.plusSeconds(60), NOW));
    }

    @Test
    void invitationSecretsMatchConstantTime() {
        String hash = EnrollmentService.sha256Hex("correct");
        assertTrue(EnrollmentService.matchesSecret("correct", hash));
        assertFalse(EnrollmentService.matchesSecret("wrong", hash));
        assertEquals(hash, EnrollmentService.sha256Hex("correct"));
    }

    @Test
    void assignmentOverlapMatrix() {
        UUID assignmentId = UUID.fromString("66666666-6666-6666-6666-666666666666");
        EmployeeAssignment current = new EmployeeAssignment(assignmentId, 1L, "ENG", null, "L3",
            Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-06-01T00:00:00Z"), NOW);
        assertEquals(assignmentId, current.getPublicId());
        assertTrue(current.overlaps(Instant.parse("2026-03-01T00:00:00Z"), Instant.parse("2026-09-01T00:00:00Z")));
        assertTrue(current.overlaps(Instant.parse("2025-01-01T00:00:00Z"), null));
        assertFalse(current.overlaps(Instant.parse("2026-06-01T00:00:00Z"), Instant.parse("2026-09-01T00:00:00Z")));
        assertFalse(current.overlaps(Instant.parse("2025-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z")));
        assertThrows(IllegalArgumentException.class, () -> new EmployeeAssignment(UUID.randomUUID(), 1L, "ENG", null, "L3",
            Instant.parse("2026-06-01T00:00:00Z"), Instant.parse("2026-06-01T00:00:00Z"), NOW));

        EmployeeAssignment open = new EmployeeAssignment(UUID.randomUUID(), 1L, "ENG", null, "L3",
            Instant.parse("2026-01-01T00:00:00Z"), null, NOW);
        assertTrue(open.overlaps(Instant.parse("2027-01-01T00:00:00Z"), null));
        assertFalse(open.overlaps(Instant.parse("2025-01-01T00:00:00Z"), Instant.parse("2025-06-01T00:00:00Z")));
    }

    @Test
    void employeeAndBindingStateTransitions() {
        Employee employee = new Employee(UUID.randomUUID(), "EMP-001", NOW);
        assertTrue(employee.isActive());
        employee.linkAccount(42L);
        assertEquals(42L, employee.getUserAccountId());
        employee.changeEmployeeNumber("EMP-002");
        assertEquals("EMP-002", employee.getEmployeeNumber());
        assertThrows(IllegalArgumentException.class, () -> employee.changeEmployeeNumber("  "));
        employee.deactivate();
        assertFalse(employee.isActive());
        employee.reactivate();
        assertTrue(employee.isActive());

        AccountBinding binding = new AccountBinding(1L, UUID.randomUUID(), 2L, "iss", "sub", NOW);
        assertTrue(binding.isActive());
        assertEquals(1L, binding.getEmployeeId());
        assertEquals(2L, binding.getAccountId());
        assertEquals("iss", binding.getOidcIssuer());
        assertEquals("sub", binding.getOidcSubject());
        binding.revoke(NOW);
        assertFalse(binding.isActive());
        assertEquals(NOW, binding.getRevokedAt());

        AuthGeneration generation = new AuthGeneration(AuthGeneration.SINGLETON_ID, 0L);
        assertEquals(1, generation.getId());
        assertEquals(1L, generation.next());
        assertEquals(2L, generation.next());
    }

    @Test
    void envelopeAndMappersCarryOnlyContractFields() {
        UUID employeeId = UUID.randomUUID();
        Employee employee = new Employee(employeeId, "EMP-001", NOW);
        var employeeResponse = EmployeeDtoMapper.response(employee);
        assertEquals(employeeId, employeeResponse.id());
        assertEquals("EMP-001", employeeResponse.employeeNumber());

        EmployeeAssignment assignment = new EmployeeAssignment(
            UUID.fromString("77777777-7777-7777-7777-777777777777"), 1L, "ENG", 9L, "L3",
            Instant.parse("2026-01-01T00:00:00Z"), null, NOW);
        var assignmentResult = id.mydev.peoplecore.organization.application.mapper.EmployeeResultMapper
            .result(assignment, employee.getPublicId(), null, null, null);
        var assignmentResponse = EmployeeDtoMapper.response(assignmentResult);
        assertEquals(UUID.fromString("77777777-7777-7777-7777-777777777777"), assignmentResponse.id());
        assertEquals("ENG", assignmentResponse.orgUnit());
        assertEquals("L3", assignmentResponse.jobLevel());
        assertEquals(1L, assignment.getEmployeeId());
        assertEquals(NOW, assignment.getCreatedAt());
        var detail = EmployeeDtoMapper.detail(employee, List.of(assignmentResult));
        assertEquals(1, detail.assignments().size());

        var invitationResponse = EnrollmentDtoMapper.response(
            new IssuedInvitation(new InvitationResult(UUID.randomUUID(), employeeId, "hrd@example.id",
                EnrollmentInvitation.STATUS_ISSUED, EnrollmentInvitation.DELIVERY_PENDING,
                NOW.plusSeconds(60)), "raw-secret"));
        assertEquals("raw-secret", invitationResponse.secret());
        assertEquals(EnrollmentInvitation.DELIVERY_PENDING, invitationResponse.deliveryStatus());

        var bindingResponse = EnrollmentDtoMapper.response(
            new BindingResult(employeeId, UUID.randomUUID(), "iss", "sub", NOW));
        assertEquals(employeeId, bindingResponse.employeeId());

        var envelope = ApiData.of("value");
        assertEquals("value", envelope.data());
        assertEquals(Map.of(), envelope.metadata());
        assertNotEquals(null, envelope);
    }

    @Test
    void commandIdsAreDeterministicPerActorAndKey() {
        UUID first = CommandIds.derive("EMPLOYEE_CREATE", "hr-1", "key-1");
        assertEquals(first, CommandIds.derive("EMPLOYEE_CREATE", "hr-1", "key-1"));
        assertNotEquals(first, CommandIds.derive("EMPLOYEE_CREATE", "hr-1", "key-2"));
        assertNotEquals(first, CommandIds.derive("ASSIGNMENT_ADD", "hr-1", "key-1"));
        assertNotEquals(first, CommandIds.derive("EMPLOYEE_CREATE", "hr-2", "key-1"));
    }

    @Test
    void jwtValidatorsAlwaysRequireAudience() {
        assertThrows(IllegalArgumentException.class,
            () -> SecurityJwtConfig.validators("https://idp.example", null));
        assertThrows(IllegalArgumentException.class,
            () -> SecurityJwtConfig.validators("https://idp.example", "  "));
        var validators = SecurityJwtConfig.validators("https://idp.example", "hris-api");
        org.springframework.security.oauth2.jwt.Jwt missingAud = org.springframework.security.oauth2.jwt.Jwt
            .withTokenValue("token").header("alg", "none").issuer("https://idp.example").subject("user-1")
            .issuedAt(NOW).expiresAt(NOW.plusSeconds(300)).build();
        assertTrue(validators.validate(missingAud).hasErrors());
        org.springframework.security.oauth2.jwt.Jwt missingExp = org.springframework.security.oauth2.jwt.Jwt
            .withTokenValue("token").header("alg", "none").issuer("https://idp.example").subject("user-1")
            .issuedAt(NOW).claim("aud", java.util.List.of("hris-api")).build();
        assertTrue(validators.validate(missingExp).hasErrors());
    }
}
