package id.mydev.peoplecore.identity.application.policy;

import id.mydev.peoplecore.common.command.CommandExecutionService;
import id.mydev.peoplecore.identity.application.service.AccountAccessService;
import id.mydev.peoplecore.identity.application.service.AuthGenerationService;
import id.mydev.peoplecore.identity.application.service.EnrollmentService;
import id.mydev.peoplecore.identity.domain.model.AccountBinding;
import id.mydev.peoplecore.identity.domain.model.HrisRole;
import id.mydev.peoplecore.identity.domain.model.RoleAssignment;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.identity.domain.repository.AccountBindingRepository;
import id.mydev.peoplecore.identity.domain.repository.RoleAssignmentRepository;
import id.mydev.peoplecore.identity.domain.repository.UserAccountRepository;
import id.mydev.peoplecore.organization.application.service.EmployeeService;
import id.mydev.peoplecore.organization.domain.model.Employee;
import id.mydev.peoplecore.support.JwtAuthFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HrisAccessPolicyTest {

    private static final String ISSUER = "https://idp.example";
    private static final Instant NOW = Instant.parse("2026-10-08T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final UUID EMPLOYEE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID INVITATION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final UserAccountRepository accounts = mock(UserAccountRepository.class);
    private final RoleAssignmentRepository roles = mock(RoleAssignmentRepository.class);
    private final AccountBindingRepository bindings = mock(AccountBindingRepository.class);
    private final AuthGenerationService generations = mock(AuthGenerationService.class);
    private final EmployeeService employees = mock(EmployeeService.class);
    private final EnrollmentService enrollments = mock(EnrollmentService.class);
    private final AccountAccessService access =
        new AccountAccessService(accounts, roles, bindings, generations, CLOCK);
    private final HrisAccessPolicy policy =
        new HrisAccessPolicy(access, employees, enrollments, new ObjectMapper(), CLOCK);

    @AfterEach
    void clearCaller() {
        SecurityContextHolder.clearContext();
    }

    private static Authentication caller(String subject) {
        return JwtAuthFixture.token(ISSUER, subject);
    }

    private static CommandExecutionService.CommandContext command(String actor, String type) {
        return new CommandExecutionService.CommandContext(actor, type, "key", "{}", null);
    }

    private UserAccount activeHrAccount() {
        UserAccount account = new UserAccount(UUID.randomUUID(), ISSUER, "hr-1", NOW);
        account.reactivate();
        org.springframework.test.util.ReflectionTestUtils.setField(account, "id", 100L);
        return account;
    }

    private static UserAccount namedAccount(String subject, long id) {
        UserAccount account = new UserAccount(UUID.randomUUID(), ISSUER, subject, NOW);
        org.springframework.test.util.ReflectionTestUtils.setField(account, "id", id);
        return account;
    }

    private static Employee employee() {
        Employee employee = new Employee(EMPLOYEE_ID, "EMP-001", NOW);
        org.springframework.test.util.ReflectionTestUtils.setField(employee, "id", 7L);
        return employee;
    }

    @Test
    void identityCommandsRequireCurrentUnscopedSystemAuthorityIncludingReplay() {
        UserAccount hr = activeHrAccount();
        when(accounts.findByOidcIssuerAndOidcSubject(ISSUER, "hr-1")).thenReturn(Optional.of(hr));
        RoleAssignment grant = new RoleAssignment(hr.getId(), HrisRole.HR_ADMIN, null, NOW.minusSeconds(10), NOW);
        RoleAssignment system = new RoleAssignment(hr.getId(), HrisRole.SYSTEM_ADMIN, null, NOW.minusSeconds(10), NOW);
        when(employees.findByPublicId(EMPLOYEE_ID)).thenReturn(Optional.of(employee()));
        when(employees.currentOrgUnit(7L, NOW)).thenReturn(Optional.of("ENG"));
        for (String type : List.of("INVITATION_ISSUE", "INVITATION_REISSUE", "ACCOUNT_REBIND")) {
            when(roles.findByAccountId(hr.getId())).thenReturn(List.of(grant));
            assertThrows(AccessDeniedException.class, () -> policy.checkCommand(caller("hr-1"), command("hr-1", type)));
            when(roles.findByAccountId(hr.getId())).thenReturn(List.of(grant, system));
            policy.checkCommand(caller("hr-1"), command("hr-1", type));
            policy.checkReplay(caller("hr-1"), command("hr-1", type), "{\"employeeId\":\"" + EMPLOYEE_ID + "\"}");
            system.endAt(NOW);
            assertThrows(AccessDeniedException.class, () -> policy.checkReplay(caller("hr-1"),
                command("hr-1", type), "{\"employeeId\":\"" + EMPLOYEE_ID + "\"}"));
            system = new RoleAssignment(hr.getId(), HrisRole.SYSTEM_ADMIN, null, NOW.minusSeconds(10), NOW);
        }
        when(roles.findByAccountId(hr.getId())).thenReturn(List.of(grant,
            new RoleAssignment(hr.getId(), HrisRole.SYSTEM_ADMIN, "ENG", NOW.minusSeconds(10), NOW)));
        assertThrows(AccessDeniedException.class, () -> access.requireIdentityAuthority(hr.getId(), NOW));
    }

    @Test
    void privilegedRestoreReplayRequiresCurrentAuthorityAndRecordedScope() {
        UserAccount hr = activeHrAccount();
        when(accounts.findByOidcIssuerAndOidcSubject(ISSUER, "hr-1")).thenReturn(Optional.of(hr));
        when(employees.findByPublicId(EMPLOYEE_ID)).thenReturn(Optional.of(employee()));
        when(employees.currentOrgUnit(7L, NOW)).thenReturn(Optional.of("ENG"));
        RoleAssignment grant = new RoleAssignment(hr.getId(), HrisRole.HR_ADMIN, null, NOW.minusSeconds(10), NOW);
        when(roles.findByAccountId(hr.getId())).thenReturn(List.of(grant));
        var context = command("hr-1", "ACCOUNT_RESTORE");
        for (String role : List.of(HrisRole.HR_ADMIN, HrisRole.FINANCE_PAYROLL, HrisRole.SYSTEM_ADMIN)) {
            String result = "{\"employeeId\":\"" + EMPLOYEE_ID + "\",\"roles\":[\"" + role + "\"],\"scope\":null}";
            assertThrows(AccessDeniedException.class, () -> policy.checkReplay(caller("hr-1"), context, result));
        }
        when(roles.findByAccountId(hr.getId())).thenReturn(List.of(
            new RoleAssignment(hr.getId(), HrisRole.HR_ADMIN, "ENG", NOW.minusSeconds(10), NOW)));
        assertThrows(AccessDeniedException.class, () -> policy.checkReplay(caller("hr-1"), context,
            "{\"employeeId\":\"" + EMPLOYEE_ID + "\",\"roles\":[\"EMPLOYEE\"],\"scope\":null}"));
        policy.checkReplay(caller("hr-1"), context,
            "{\"employeeId\":\"" + EMPLOYEE_ID + "\",\"roles\":[\"EMPLOYEE\"],\"scope\":\"ENG\"}");
    }

    @Test
    void activationCommandNeedsNoPriorAccount() {
        policy.checkCommand(caller("anyone"), command("anyone", HrisAccessPolicy.COMMAND_ACTIVATE));
        when(enrollments.activationReplayAllowed(INVITATION_ID, ISSUER, "emp-1", EMPLOYEE_ID)).thenReturn(true);
        policy.checkReplay(caller("emp-1"), command("emp-1", HrisAccessPolicy.COMMAND_ACTIVATE),
            "{\"invitationId\":\"" + INVITATION_ID + "\",\"employeeId\":\"" + EMPLOYEE_ID
                + "\",\"issuer\":\"" + ISSUER + "\",\"subject\":\"emp-1\"}");
        assertThrows(AccessDeniedException.class, () -> policy.checkReplay(caller("intruder"),
            command("intruder", HrisAccessPolicy.COMMAND_ACTIVATE),
            "{\"invitationId\":\"" + INVITATION_ID + "\",\"employeeId\":\"" + EMPLOYEE_ID
                + "\",\"issuer\":\"" + ISSUER + "\",\"subject\":\"emp-1\"}"));
        Authentication basic =
            UsernamePasswordAuthenticationToken.authenticated("emp-1", "", List.of());
        assertThrows(AccessDeniedException.class, () -> policy.checkReplay(basic,
            command("emp-1", HrisAccessPolicy.COMMAND_ACTIVATE),
            "{\"invitationId\":\"" + INVITATION_ID + "\",\"employeeId\":\"" + EMPLOYEE_ID
                + "\",\"issuer\":\"" + ISSUER + "\",\"subject\":\"emp-1\"}"));
    }

    @Test
    void activationReplayRequiresLiveBinding() {
        when(enrollments.activationReplayAllowed(INVITATION_ID, ISSUER, "emp-1", EMPLOYEE_ID)).thenReturn(false);
        assertThrows(AccessDeniedException.class, () -> policy.checkReplay(caller("anyone"),
            command("anyone", HrisAccessPolicy.COMMAND_ACTIVATE),
            "{\"invitationId\":\"" + INVITATION_ID + "\",\"employeeId\":\"" + EMPLOYEE_ID
                + "\",\"issuer\":\"" + ISSUER + "\",\"subject\":\"emp-1\"}"));
        assertThrows(AccessDeniedException.class, () -> policy.checkReplay(caller("anyone"),
            command("anyone", HrisAccessPolicy.COMMAND_ACTIVATE), "not-json"));
        assertThrows(AccessDeniedException.class, () -> policy.checkReplay(caller("anyone"),
            command("anyone", HrisAccessPolicy.COMMAND_ACTIVATE), "[1,2]"));
        assertThrows(AccessDeniedException.class, () -> policy.checkReplay(caller("anyone"),
            command("anyone", HrisAccessPolicy.COMMAND_ACTIVATE), "{\"employeeId\":\"nope\"}"));
        assertThrows(AccessDeniedException.class, () -> policy.checkReplay(caller("anyone"),
            command("anyone", HrisAccessPolicy.COMMAND_ACTIVATE),
            "{\"invitationId\":\"" + INVITATION_ID + "\",\"employeeId\":\"" + EMPLOYEE_ID
                + "\",\"issuer\":\" \",\"subject\":\"emp-1\"}"));
    }

    @Test
    void nonJwtCallersAreDenied() {
        Authentication basic =
            UsernamePasswordAuthenticationToken.authenticated("actor", "", List.of());
        assertThrows(AccessDeniedException.class,
            () -> policy.checkCommand(basic, command("actor", "EMPLOYEE_CREATE")));
        assertEquals(Set.of(), policy.auditResources(basic));
    }

    @Test
    void unknownCommandsAreDeniedByDefault() {
        UserAccount account = activeHrAccount();
        when(accounts.findByOidcIssuerAndOidcSubject(ISSUER, "hr-1"))
            .thenReturn(Optional.of(account));
        when(roles.findByAccountId(account.getId())).thenReturn(List.of(
            new RoleAssignment(account.getId(), HrisRole.HR_ADMIN, null, NOW.minusSeconds(10), NOW)));
        assertThrows(AccessDeniedException.class,
            () -> policy.checkCommand(caller("hr-1"), command("hr-1", "LEAVE_APPROVE")));
    }

    @Test
    void hrCommandsRequireLinkedActiveHrAccount() {
        when(accounts.findByOidcIssuerAndOidcSubject(ISSUER, "ghost"))
            .thenReturn(Optional.empty());
        assertThrows(AccessDeniedException.class,
            () -> policy.checkCommand(caller("ghost"), command("ghost", "EMPLOYEE_CREATE")));

        UserAccount inactive = namedAccount("off", 101L);
        inactive.deactivate();
        when(accounts.findByOidcIssuerAndOidcSubject(ISSUER, "off"))
            .thenReturn(Optional.of(inactive));
        assertThrows(AccessDeniedException.class,
            () -> policy.checkCommand(caller("off"), command("off", "EMPLOYEE_CREATE")));

        UserAccount plain = namedAccount("emp-1", 102L);
        when(accounts.findByOidcIssuerAndOidcSubject(ISSUER, "emp-1"))
            .thenReturn(Optional.of(plain));
        when(roles.findByAccountId(plain.getId())).thenReturn(List.of(
            new RoleAssignment(plain.getId(), HrisRole.EMPLOYEE, null, NOW.minusSeconds(10), NOW)));
        assertThrows(AccessDeniedException.class,
            () -> policy.checkCommand(caller("emp-1"), command("emp-1", "INVITATION_ISSUE")));

        UserAccount hr = activeHrAccount();
        when(accounts.findByOidcIssuerAndOidcSubject(ISSUER, "hr-1"))
            .thenReturn(Optional.of(hr));
        RoleAssignment grant = new RoleAssignment(hr.getId(), HrisRole.HR_ADMIN, null, NOW.minusSeconds(10), NOW);
        when(roles.findByAccountId(hr.getId())).thenReturn(List.of(grant));
        policy.checkCommand(caller("hr-1"), command("hr-1", "EMPLOYEE_CREATE"));
        when(employees.findByPublicId(EMPLOYEE_ID)).thenReturn(Optional.of(employee()));
        when(employees.currentOrgUnit(7L, NOW)).thenReturn(Optional.of("ENG"));
        RoleAssignment scoped = new RoleAssignment(hr.getId(), HrisRole.HR_ADMIN, "ENG", NOW.minusSeconds(10), NOW);
        when(roles.findByAccountId(hr.getId())).thenReturn(List.of(scoped));
        policy.checkReplay(caller("hr-1"), command("hr-1", "EMPLOYEE_CREATE"),
            "{\"employeeId\":\"" + EMPLOYEE_ID + "\"}");
        RoleAssignment expired = new RoleAssignment(hr.getId(), HrisRole.MANAGER, null, NOW.minusSeconds(100), NOW.minusSeconds(100));
        expired.endAt(NOW.minusSeconds(10));
        when(roles.findByAccountId(hr.getId())).thenReturn(List.of(grant, expired));
        assertEquals(List.of(HrisRole.HR_ADMIN), access.activeRoles(hr.getId(), NOW));
    }

    @Test
    void replayDeniesLostScope() {
        UserAccount hr = activeHrAccount();
        when(accounts.findByOidcIssuerAndOidcSubject(ISSUER, "hr-1"))
            .thenReturn(Optional.of(hr));
        when(employees.findByPublicId(EMPLOYEE_ID)).thenReturn(Optional.of(employee()));
        when(employees.currentOrgUnit(7L, NOW)).thenReturn(Optional.of("ENG"));
        assertThrows(AccessDeniedException.class, () -> policy.checkReplay(caller("hr-1"),
            command("hr-1", "EMPLOYEE_UPDATE"), "not-json"));
        assertThrows(AccessDeniedException.class, () -> policy.checkReplay(caller("hr-1"),
            command("hr-1", "EMPLOYEE_UPDATE"), "{\"other\":\"x\"}"));
        when(roles.findByAccountId(hr.getId())).thenReturn(List.of(
            new RoleAssignment(hr.getId(), HrisRole.HR_ADMIN, "FIN", NOW.minusSeconds(10), NOW)));
        assertThrows(AccessDeniedException.class, () -> policy.checkReplay(caller("hr-1"),
            command("hr-1", "EMPLOYEE_UPDATE"), "{\"employeeId\":\"" + EMPLOYEE_ID + "\"}"));
        when(employees.findByPublicId(EMPLOYEE_ID)).thenReturn(Optional.empty());
        assertThrows(AccessDeniedException.class, () -> policy.checkReplay(caller("hr-1"),
            command("hr-1", "EMPLOYEE_UPDATE"), "{\"employeeId\":\"" + EMPLOYEE_ID + "\"}"));
    }

    @Test
    void expiredHrGrantIsDenied() {
        UserAccount hr = activeHrAccount();
        when(accounts.findByOidcIssuerAndOidcSubject(ISSUER, "hr-1"))
            .thenReturn(Optional.of(hr));
        RoleAssignment expired = new RoleAssignment(hr.getId(), HrisRole.HR_ADMIN, null,
            NOW.minusSeconds(100), NOW.minusSeconds(100));
        expired.endAt(NOW.minusSeconds(10));
        when(roles.findByAccountId(hr.getId())).thenReturn(List.of(expired));
        assertThrows(AccessDeniedException.class,
            () -> policy.checkCommand(caller("hr-1"), command("hr-1", "EMPLOYEE_CREATE")));
        assertTrue(!access.coversUnit(hr.getId(), "ENG", NOW));
        when(roles.findByAccountId(hr.getId())).thenReturn(List.of(
            new RoleAssignment(hr.getId(), HrisRole.EMPLOYEE, null, NOW.minusSeconds(10), NOW)));
        assertTrue(!access.coversUnit(hr.getId(), "ENG", NOW));
    }

    @Test
    void scopedHrCoversOnlyItsUnit() {
        UserAccount hr = activeHrAccount();
        when(accounts.findByOidcIssuerAndOidcSubject(ISSUER, "hr-1"))
            .thenReturn(Optional.of(hr));
        RoleAssignment scoped = new RoleAssignment(hr.getId(), HrisRole.HR_ADMIN, "ENG", NOW.minusSeconds(10), NOW);
        when(roles.findByAccountId(hr.getId())).thenReturn(List.of(scoped));
        assertTrue(access.coversUnit(hr.getId(), "ENG", NOW));
        assertTrue(!access.coversUnit(hr.getId(), "FIN", NOW));
        access.requireHrScope(caller("hr-1"), "ENG", NOW);
        assertThrows(AccessDeniedException.class,
            () -> access.requireHrScope(caller("hr-1"), "FIN", NOW));
        assertThrows(AccessDeniedException.class,
            () -> access.requireHrScope(caller("hr-1"), null, NOW));
    }

    @Test
    void auditResourcesExposeOnlyOwnBoundEmployee() {
        UserAccount hr = activeHrAccount();
        when(accounts.findByOidcIssuerAndOidcSubject(ISSUER, "hr-1"))
            .thenReturn(Optional.of(hr));
        when(bindings.findByAccountIdAndRevokedAtIsNull(hr.getId())).thenReturn(Optional.empty());
        assertEquals(Set.of(), policy.auditResources(caller("hr-1")));

        UUID employeeId = UUID.randomUUID();
        AccountBinding binding = new AccountBinding(5L, employeeId, hr.getId(), ISSUER, "hr-1", NOW);
        when(bindings.findByAccountIdAndRevokedAtIsNull(hr.getId())).thenReturn(Optional.of(binding));
        assertEquals(Set.of("EMPLOYEE:" + employeeId), policy.auditResources(caller("hr-1")));

        UserAccount inactive = namedAccount("off", 101L);
        inactive.deactivate();
        when(accounts.findByOidcIssuerAndOidcSubject(ISSUER, "off"))
            .thenReturn(Optional.of(inactive));
        assertEquals(Set.of(), policy.auditResources(caller("off")));
        assertTrue(hr.isActiveAt(NOW));
    }
}
