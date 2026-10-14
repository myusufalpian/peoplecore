package id.mydev.peoplecore.identity.application.policy;

import id.mydev.peoplecore.approval.application.service.ApprovalRuleService;
import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.common.command.CommandExecutionService.CommandContext;
import id.mydev.peoplecore.identity.application.service.AccountAccessService;
import id.mydev.peoplecore.identity.application.service.AuthGenerationService;
import id.mydev.peoplecore.identity.application.service.EnrollmentService;
import id.mydev.peoplecore.identity.domain.model.HrisRole;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.organization.application.service.EmployeeService;
import id.mydev.peoplecore.organization.application.service.ProfileProposalService;
import id.mydev.peoplecore.organization.application.service.ProfileProposalService.ReplaySnapshot;
import id.mydev.peoplecore.organization.domain.model.Employee;
import id.mydev.peoplecore.support.JwtAuthFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ProfileAccessPolicyTest {

    private static final String ISSUER = "https://idp.example";
    private static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final UUID PROPOSAL_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID EMPLOYEE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID HR_ACCOUNT_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID MEMBER_ACCOUNT_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");

    static class FakeAccess extends AccountAccessService {
        final Map<String, UserAccount> bySubject = new HashMap<>();
        final Map<UUID, UserAccount> byPublicId = new HashMap<>();
        final Map<Long, Set<String>> roles = new HashMap<>();
        final Map<Long, Set<String>> units = new HashMap<>();
        final Map<Long, UUID> employeeByAccount = new HashMap<>();
        boolean orgWide = true;

        FakeAccess() {
            super(null, null, null, null, CLOCK);
        }

        void addAccount(String subject, long internalId, UUID publicId, boolean hr) {
            UserAccount account = new UserAccount(publicId, ISSUER, subject, NOW);
            ReflectionTestUtils.setField(account, "id", internalId);
            bySubject.put(subject, account);
            byPublicId.put(publicId, account);
            roles.put(internalId, hr ? Set.of(HrisRole.HR_ADMIN) : Set.of(HrisRole.EMPLOYEE));
            units.put(internalId, hr ? Set.of("ENG") : Set.of());
        }

        @Override
        public Optional<UserAccount> resolveAccount(Authentication caller) {
            return Optional.ofNullable(bySubject.get(caller.getName()));
        }

        @Override
        public UserAccount requireActive(Authentication caller, Instant now) {
            return resolveAccount(caller)
                .orElseThrow(() -> new AccessDeniedException("Account is not linked"));
        }

        @Override
        public UserAccount requireHrOperation(Authentication caller, Instant now) {
            UserAccount account = requireActive(caller, now);
            if (!hasRole(account.getId(), HrisRole.HR_ADMIN, now)) {
                throw new AccessDeniedException("HR operation requires the HR role");
            }
            return account;
        }

        @Override
        public Optional<UUID> ownEmployeePublicId(UserAccount account) {
            return Optional.ofNullable(employeeByAccount.get(account.getId()));
        }

        @Override
        public Optional<UserAccount> findAccountByPublicId(UUID publicId) {
            return Optional.ofNullable(byPublicId.get(publicId));
        }

        @Override
        public boolean hasRole(Long accountId, String role, Instant now) {
            return roles.getOrDefault(accountId, Set.of()).contains(role);
        }

        @Override
        public boolean coversUnit(Long accountId, String targetOrgUnit, Instant now) {
            if (!hasRole(accountId, HrisRole.HR_ADMIN, now)) {
                return false;
            }
            Set<String> covered = units.getOrDefault(accountId, Set.of());
            return targetOrgUnit != null && covered.contains(targetOrgUnit);
        }

        @Override
        public void requireTargetScope(Long accountId, String targetOrgUnit, Instant now) {
            if (!coversUnit(accountId, targetOrgUnit, now)) {
                throw new ResourceNotFoundException();
            }
        }

        @Override
        public List<String> activeRoles(Long accountId, Instant now) {
            return List.copyOf(roles.getOrDefault(accountId, Set.of()));
        }

        @Override
        public HrAuthority hrAuthority(Long accountId, Instant now) {
            return new HrAuthority(orgWide, Set.of("ENG"));
        }

        @Override
        public void requireIdentityAuthority(Long accountId, Instant now) {
        }
    }

    static class FakeProposals extends ProfileProposalService {
        final Map<UUID, String> ownerByProposal = new HashMap<>();
        UUID lockedAssignee;
        UUID terminalAssignment;
        boolean hrScopeOk = true;

        FakeProposals() {
            super(null, null, null, null, null, null, null, null, null, null, null, null, null);
        }

        @Override
        public UUID requireOwnershipForReplay(UUID proposalPublicId, Authentication caller) {
            if (!caller.getName().equals(ownerByProposal.get(proposalPublicId))) {
                throw new AccessDeniedException("Ownership changed");
            }
            return EMPLOYEE_ID;
        }

        @Override
        public void requireHrScopeForReplay(UUID proposalPublicId, Authentication caller) {
            if (!hrScopeOk) {
                throw new AccessDeniedException("Scope changed");
            }
        }

        @Override
        public ReplaySnapshot lockForReplay(UUID proposalPublicId) {
            return new ReplaySnapshot(EMPLOYEE_ID, "PENDING", PROPOSAL_ID, lockedAssignee);
        }

        @Override
        public boolean isTerminalAssignment(UUID proposalPublicId, UUID assignmentPublicId) {
            return assignmentPublicId.equals(terminalAssignment);
        }

        @Override
        public void lockContactForReplay(UUID employeePublicId) {
        }
    }

    static class FakeEmployees extends EmployeeService {
        FakeEmployees() {
            super(null, null, null, null, null, null, null);
        }

        @Override
        public Optional<Employee> findByPublicId(UUID publicId) {
            if (!EMPLOYEE_ID.equals(publicId)) {
                return Optional.empty();
            }
            Employee employee = new Employee(EMPLOYEE_ID, "EMP-001", NOW);
            ReflectionTestUtils.setField(employee, "id", 7L);
            return Optional.of(employee);
        }

        @Override
        public Optional<String> currentOrgUnit(Long employeeId, Instant now) {
            return Optional.of("ENG");
        }
    }

    static class FakeEnrollments extends EnrollmentService {
        FakeEnrollments() {
            super(null, null, null, null, null, null, null, null, null, null);
        }
    }

    static class FakeApprovalRules extends ApprovalRuleService {
        boolean publishReplayScopeOk = true;
        boolean activateReplayScopeOk = true;

        FakeApprovalRules() {
            super(null, null, null, null, null, null, null, null, null);
        }

        @Override
        public void verifyPublishReplayScope(UUID ruleSetPublicId, Authentication caller) {
            if (!publishReplayScopeOk) {
                throw new AccessDeniedException("Configuration scope changed");
            }
        }

        @Override
        public void verifyActivateReplayScope(UUID ruleSetPublicId, Authentication caller) {
            if (!activateReplayScopeOk) {
                throw new AccessDeniedException("Configuration scope changed");
            }
        }
    }

    private final FakeAccess access = new FakeAccess();
    private final FakeEmployees employees = new FakeEmployees();
    private final FakeEnrollments enrollments = new FakeEnrollments();
    private final FakeProposals proposals = new FakeProposals();
    private final FakeApprovalRules approvalRules = new FakeApprovalRules();
    private final HrisAccessPolicy policy =
        new HrisAccessPolicy(access, employees, enrollments, proposals, approvalRules,
            new ObjectMapper(), CLOCK);

    private void seed() {
        access.addAccount("hr-1", 100L, HR_ACCOUNT_ID, true);
        access.addAccount("hr-fin", 300L, UUID.fromString("66666666-6666-6666-6666-666666666666"), true);
        access.units.put(300L, Set.of("FIN"));
        access.addAccount("emp-1", 200L, MEMBER_ACCOUNT_ID, false);
        access.employeeByAccount.put(200L, EMPLOYEE_ID);
        proposals.ownerByProposal.put(PROPOSAL_ID, "emp-1");
        proposals.lockedAssignee = null;
    }

    @AfterEach
    void clearCaller() {
        SecurityContextHolder.clearContext();
    }

    private static Authentication caller(String subject) {
        return JwtAuthFixture.token(ISSUER, subject);
    }

    private static CommandContext command(String actor, String type) {
        return new CommandContext(actor, type, "key", "{}", null);
    }

    private static final UUID ASSIGNMENT_A =
        UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID ASSIGNMENT_B =
        UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private static String proposalReceipt() {
        return "{\"proposalPublicId\":\"" + PROPOSAL_ID + "\"}";
    }

    private static String decisionReceipt(UUID assignmentPublicId) {
        return "{\"proposalPublicId\":\"" + PROPOSAL_ID
            + "\",\"assignmentPublicId\":\"" + assignmentPublicId + "\"}";
    }

    private static String ruleReceipt() {
        return "{\"ruleSetPublicId\":\"" + UUID.randomUUID() + "\"}";
    }

    @Test
    void ownerCommandsRequireBoundEmployee() {
        seed();
        for (String type : List.of("PROFILE_CREATE", "PROFILE_EDIT", "PROFILE_SUBMIT",
            "PROFILE_RESUBMIT", "PROFILE_WITHDRAW")) {
            policy.checkCommand(caller("emp-1"), command("emp-1", type));
            assertThrows(AccessDeniedException.class,
                () -> policy.checkCommand(caller("hr-1"), command("hr-1", type)));
        }
        access.employeeByAccount.put(100L, EMPLOYEE_ID);
        assertThrows(AccessDeniedException.class,
            () -> policy.checkCommand(caller("hr-1"), command("hr-1", "PROFILE_CREATE")));
        assertThrows(AccessDeniedException.class,
            () -> policy.checkCommand(caller("emp-1"), command("emp-1", "PROFILE_UNKNOWN")));
    }

    @Test
    void decisionCommandsRequireHrRole() {
        seed();
        for (String type : List.of("PROFILE_APPROVE", "PROFILE_REJECT", "PROFILE_RETURN")) {
            policy.checkCommand(caller("hr-1"), command("hr-1", type));
            assertThrows(AccessDeniedException.class,
                () -> policy.checkCommand(caller("emp-1"), command("emp-1", type)));
        }
    }

    @Test
    void operatorAndRuleCommandsRequireHrOperation() {
        seed();
        for (String type : List.of("PROFILE_REASSIGN", "PROFILE_ROUTE_RETRY",
            "PROFILE_MASTER_CORRECT", "PROFILE_RULE_PUBLISH", "PROFILE_RULE_ACTIVATE")) {
            policy.checkCommand(caller("hr-1"), command("hr-1", type));
            assertThrows(AccessDeniedException.class,
                () -> policy.checkCommand(caller("emp-1"), command("emp-1", type)));
        }
    }

    @Test
    void ownerReplayFollowsCurrentOwnership() {
        seed();
        for (String type : List.of("PROFILE_CREATE", "PROFILE_SUBMIT", "PROFILE_WITHDRAW")) {
            policy.checkReplay(caller("emp-1"), command("emp-1", type), proposalReceipt());
        }
        proposals.ownerByProposal.put(PROPOSAL_ID, "emp-9");
        assertThrows(AccessDeniedException.class, () -> policy.checkReplay(
            caller("emp-1"), command("emp-1", "PROFILE_SUBMIT"), proposalReceipt()));
        assertThrows(AccessDeniedException.class, () -> policy.checkReplay(
            caller("emp-1"), command("emp-1", "PROFILE_SUBMIT"), "not-json"));
    }

    @Test
    void decisionReplayDeniesSupersededAssignee() {
        seed();
        proposals.lockedAssignee = HR_ACCOUNT_ID;
        policy.checkReplay(caller("hr-1"), command("hr-1", "PROFILE_APPROVE"),
            decisionReceipt(ASSIGNMENT_A));
        proposals.lockedAssignee = UUID.randomUUID();
        assertThrows(AccessDeniedException.class, () -> policy.checkReplay(
            caller("hr-1"), command("hr-1", "PROFILE_APPROVE"), decisionReceipt(ASSIGNMENT_A)));
        proposals.hrScopeOk = false;
        proposals.lockedAssignee = null;
        proposals.terminalAssignment = ASSIGNMENT_A;
        assertThrows(AccessDeniedException.class, () -> policy.checkReplay(
            caller("hr-1"), command("hr-1", "PROFILE_REJECT"), decisionReceipt(ASSIGNMENT_A)));
    }

    @Test
    void decisionReplayAfterClosureUsesRecordedLineage() {
        seed();
        proposals.lockedAssignee = null;
        proposals.terminalAssignment = ASSIGNMENT_B;
        policy.checkReplay(caller("hr-1"), command("hr-1", "PROFILE_APPROVE"),
            decisionReceipt(ASSIGNMENT_B));
        assertThrows(AccessDeniedException.class, () -> policy.checkReplay(
            caller("hr-1"), command("hr-1", "PROFILE_APPROVE"),
            decisionReceipt(ASSIGNMENT_A)));
        assertThrows(AccessDeniedException.class, () -> policy.checkReplay(
            caller("hr-1"), command("hr-1", "PROFILE_APPROVE"), proposalReceipt()));
    }

    @Test
    void masterCorrectReplayAllowsCurrentScope() {
        seed();
        policy.checkReplay(caller("hr-1"), command("hr-1", "PROFILE_MASTER_CORRECT"),
            "{\"employeePublicId\":\"" + EMPLOYEE_ID + "\"}");
    }

    @Test
    void replayRejectsMalformedRecordedResults() {
        seed();
        assertThrows(AccessDeniedException.class, () -> policy.checkReplay(
            caller("emp-1"), command("emp-1", "PROFILE_SUBMIT"), "{\"proposalPublicId\":123}"));
        assertThrows(AccessDeniedException.class, () -> policy.checkReplay(
            caller("emp-1"), command("emp-1", "PROFILE_SUBMIT"), "{}"));
        assertThrows(AccessDeniedException.class, () -> policy.checkReplay(
            caller("emp-1"), command("emp-1", "PROFILE_SUBMIT"),
            "{\"proposalPublicId\":\"not-a-uuid\"}"));
        assertThrows(ResourceNotFoundException.class, () -> policy.checkReplay(
            caller("hr-fin"), command("hr-fin", "PROFILE_MASTER_CORRECT"),
            "{\"employeePublicId\":\"" + UUID.randomUUID() + "\"}"));
    }

    @Test
    void assignmentReplayFollowsCurrentScope() {
        seed();
        String allowed = "{\"employeeId\":\"" + EMPLOYEE_ID + "\",\"orgUnit\":\"ENG\"}";
        policy.checkReplay(caller("hr-1"), command("hr-1", "ASSIGNMENT_ADD"), allowed);
        String allowedHistory =
            "{\"employeeId\":\"" + EMPLOYEE_ID + "\",\"orgUnit\":\"ENG\",\"before\":{\"orgUnit\":\"ENG\"}}";
        policy.checkReplay(caller("hr-1"), command("hr-1", "ASSIGNMENT_REVISE"), allowedHistory);
        String deniedUnit = "{\"employeeId\":\"" + EMPLOYEE_ID + "\",\"orgUnit\":\"FIN\"}";
        assertThrows(AccessDeniedException.class, () -> policy.checkReplay(
            caller("hr-1"), command("hr-1", "ASSIGNMENT_ADD"), deniedUnit));
        String deniedHistory = "{\"employeeId\":\"" + EMPLOYEE_ID
            + "\",\"orgUnit\":\"ENG\",\"before\":{\"orgUnit\":\"FIN\"}}";
        assertThrows(AccessDeniedException.class, () -> policy.checkReplay(
            caller("hr-1"), command("hr-1", "ASSIGNMENT_REVISE"), deniedHistory));
    }

    @Test
    void restoreReplayVerifiesRecordedGrants() {
        seed();
        String memberRestore = "{\"employeeId\":\"" + EMPLOYEE_ID
            + "\",\"roles\":[\"EMPLOYEE\"],\"scope\":\"ENG\"}";
        policy.checkReplay(caller("hr-1"), command("hr-1", "ACCOUNT_RESTORE"), memberRestore);
        String managerRestore = "{\"employeeId\":\"" + EMPLOYEE_ID
            + "\",\"roles\":[\"MANAGER\"],\"scope\":\"ENG\"}";
        policy.checkReplay(caller("hr-1"), command("hr-1", "ACCOUNT_RESTORE"), managerRestore);
        String privilegedRestore = "{\"employeeId\":\"" + EMPLOYEE_ID
            + "\",\"roles\":[\"HR_ADMIN\"],\"scope\":\"ENG\"}";
        policy.checkReplay(caller("hr-1"), command("hr-1", "ACCOUNT_RESTORE"), privilegedRestore);
        String missingRoles = "{\"employeeId\":\"" + EMPLOYEE_ID + "\",\"scope\":\"ENG\"}";
        assertThrows(AccessDeniedException.class, () -> policy.checkReplay(
            caller("hr-1"), command("hr-1", "ACCOUNT_RESTORE"), missingRoles));
        String emptyRoles = "{\"employeeId\":\"" + EMPLOYEE_ID + "\",\"roles\":[],\"scope\":\"ENG\"}";
        assertThrows(AccessDeniedException.class, () -> policy.checkReplay(
            caller("hr-1"), command("hr-1", "ACCOUNT_RESTORE"), emptyRoles));
        String nonArrayRoles = "{\"employeeId\":\"" + EMPLOYEE_ID
            + "\",\"roles\":\"EMPLOYEE\",\"scope\":\"ENG\"}";
        assertThrows(AccessDeniedException.class, () -> policy.checkReplay(
            caller("hr-1"), command("hr-1", "ACCOUNT_RESTORE"), nonArrayRoles));
        String malformedRole = "{\"employeeId\":\"" + EMPLOYEE_ID
            + "\",\"roles\":[42],\"scope\":\"ENG\"}";
        assertThrows(AccessDeniedException.class, () -> policy.checkReplay(
            caller("hr-1"), command("hr-1", "ACCOUNT_RESTORE"), malformedRole));
        access.orgWide = false;
        String privileged = "{\"employeeId\":\"" + EMPLOYEE_ID
            + "\",\"roles\":[\"HR_ADMIN\"],\"scope\":\"ENG\"}";
        assertThrows(AccessDeniedException.class, () -> policy.checkReplay(
            caller("hr-1"), command("hr-1", "ACCOUNT_RESTORE"), privileged));
        String wrongScope = "{\"employeeId\":\"" + EMPLOYEE_ID
            + "\",\"roles\":[\"EMPLOYEE\"],\"scope\":\"FIN\"}";
        assertThrows(AccessDeniedException.class, () -> policy.checkReplay(
            caller("hr-1"), command("hr-1", "ACCOUNT_RESTORE"), wrongScope));
        String nullScope = "{\"employeeId\":\"" + EMPLOYEE_ID
            + "\",\"roles\":[\"EMPLOYEE\"],\"scope\":null}";
        assertThrows(AccessDeniedException.class, () -> policy.checkReplay(
            caller("hr-1"), command("hr-1", "ACCOUNT_RESTORE"), nullScope));
    }

    @Test
    void ruleReplayRechecksConfigurationScope() {
        seed();
        policy.checkReplay(caller("hr-1"), command("hr-1", "PROFILE_RULE_PUBLISH"), ruleReceipt());
        policy.checkReplay(caller("hr-1"), command("hr-1", "PROFILE_RULE_ACTIVATE"), ruleReceipt());
        approvalRules.publishReplayScopeOk = false;
        assertThrows(AccessDeniedException.class, () -> policy.checkReplay(
            caller("hr-1"), command("hr-1", "PROFILE_RULE_PUBLISH"), ruleReceipt()));
        policy.checkReplay(caller("hr-1"), command("hr-1", "PROFILE_RULE_ACTIVATE"), ruleReceipt());
        approvalRules.publishReplayScopeOk = true;
        approvalRules.activateReplayScopeOk = false;
        assertThrows(AccessDeniedException.class, () -> policy.checkReplay(
            caller("hr-1"), command("hr-1", "PROFILE_RULE_ACTIVATE"), ruleReceipt()));
        policy.checkReplay(caller("hr-1"), command("hr-1", "PROFILE_RULE_PUBLISH"), ruleReceipt());
    }

    @Test
    void operatorReplayRechecksScope() {
        seed();
        policy.checkReplay(caller("hr-1"), command("hr-1", "PROFILE_REASSIGN"), proposalReceipt());
        policy.checkReplay(caller("hr-1"), command("hr-1", "PROFILE_ROUTE_RETRY"), proposalReceipt());
        policy.checkReplay(caller("hr-1"), command("hr-1", "PROFILE_RULE_PUBLISH"), ruleReceipt());
        proposals.hrScopeOk = false;
        assertThrows(AccessDeniedException.class, () -> policy.checkReplay(
            caller("hr-1"), command("hr-1", "PROFILE_REASSIGN"), proposalReceipt()));
        assertThrows(ResourceNotFoundException.class, () -> policy.checkReplay(
            caller("hr-fin"), command("hr-fin", "PROFILE_MASTER_CORRECT"),
            "{\"employeePublicId\":\"" + EMPLOYEE_ID + "\"}"));
    }
}
