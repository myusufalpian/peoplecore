package id.mydev.peoplecore.identity.application.policy;

import id.mydev.peoplecore.common.command.CommandExecutionService.CommandContext;
import id.mydev.peoplecore.common.security.CurrentAccessPolicy;
import id.mydev.peoplecore.identity.application.mapper.JwtIdentityMapper;
import id.mydev.peoplecore.identity.application.service.AccountAccessService;
import id.mydev.peoplecore.identity.application.service.EnrollmentService;
import id.mydev.peoplecore.identity.domain.model.HrisRole;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.approval.application.service.ApprovalRuleService;
import id.mydev.peoplecore.organization.application.service.EmployeeService;
import id.mydev.peoplecore.organization.application.service.ProfileProposalService;
import id.mydev.peoplecore.organization.application.service.ProfileProposalService.ReplaySnapshot;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Component
public class HrisAccessPolicy implements CurrentAccessPolicy {

    public static final String COMMAND_ACTIVATE = "INVITATION_ACTIVATE";

    private static final Set<String> HR_COMMANDS = Set.of(
        "EMPLOYEE_CREATE",
        "EMPLOYEE_UPDATE",
        "ASSIGNMENT_ADD",
        "ASSIGNMENT_REVISE",
        "EMPLOYMENT_UPDATE",
        "INVITATION_ISSUE",
        "INVITATION_REISSUE",
        "ACCOUNT_OFFBOARD",
        "ACCOUNT_REBIND",
        "ACCOUNT_REHIRE",
        "ACCOUNT_RESTORE"
    );
    private static final Set<String> IDENTITY_COMMANDS = Set.of(
        "INVITATION_ISSUE", "INVITATION_REISSUE", "ACCOUNT_REBIND");
    private static final Set<String> PROFILE_OWNER_COMMANDS = Set.of(
        "PROFILE_CREATE",
        "PROFILE_EDIT",
        "PROFILE_SUBMIT",
        "PROFILE_RESUBMIT",
        "PROFILE_WITHDRAW");
    private static final Set<String> PROFILE_DECISION_COMMANDS = Set.of(
        "PROFILE_APPROVE",
        "PROFILE_REJECT",
        "PROFILE_RETURN");
    private static final Set<String> PROFILE_OPERATOR_COMMANDS = Set.of(
        "PROFILE_REASSIGN",
        "PROFILE_ROUTE_RETRY",
        "PROFILE_MASTER_CORRECT");
    private static final Set<String> RULE_COMMANDS = Set.of(
        "PROFILE_RULE_PUBLISH",
        "PROFILE_RULE_ACTIVATE");
    private static final Set<String> CONTEXT_PUBLISH_COMMANDS = Set.of(
        "SCHEDULE_PUBLISH",
        "CALENDAR_PUBLISH");
    private static final Set<String> CONTEXT_ASSIGN_COMMANDS = Set.of(
        "SCHEDULE_ASSIGN",
        "CALENDAR_ASSIGN");

    private static final Set<String> ATTENDANCE_COMMANDS = Set.of(
        "ATTENDANCE_CLOCK",
        "ATTENDANCE_CORRECTION_CREATE",
        "ATTENDANCE_CORRECTION_EDIT",
        "ATTENDANCE_CORRECTION_DISCARD",
        "ATTENDANCE_CORRECTION_SUBMIT",
        "ATTENDANCE_CORRECTION_RESUBMIT",
        "ATTENDANCE_CORRECTION_WITHDRAW",
        "ATTENDANCE_CORRECTION_APPROVE",
        "ATTENDANCE_CORRECTION_REJECT",
        "ATTENDANCE_CORRECTION_RETURN",
        "ATTENDANCE_CORRECTION_REASSIGN",
        "ATTENDANCE_CORRECTION_ROUTE_RETRY",
        "ATTENDANCE_RULE_PUBLISH",
        "ATTENDANCE_RULE_ACTIVATE");
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<id.mydev.peoplecore.attendance.application.command.AttendanceCommands> attendanceCommands;

    private static final Set<String> LEAVE_WORKFLOW_COMMANDS = Set.of(
        "LEAVE_REQUEST_CREATE",
        "LEAVE_REQUEST_EDIT",
        "LEAVE_REQUEST_DISCARD",
        "LEAVE_REQUEST_SUBMIT",
        "LEAVE_REQUEST_RESUBMIT",
        "LEAVE_REQUEST_WITHDRAW",
        "LEAVE_REQUEST_APPROVE",
        "LEAVE_REQUEST_REJECT",
        "LEAVE_REQUEST_RETURN",
        "LEAVE_REQUEST_REASSIGN",
        "LEAVE_REQUEST_RETRY_ROUTE",
        "LEAVE_CANCELLATION_CREATE",
        "LEAVE_CANCELLATION_WITHDRAW",
        "LEAVE_CANCELLATION_APPROVE",
        "LEAVE_CANCELLATION_REJECT",
        "LEAVE_CANCELLATION_REASSIGN",
        "LEAVE_CANCELLATION_RETRY_ROUTE",
        "LEAVE_RULE_PUBLISH",
        "LEAVE_RULE_ACTIVATE");
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<id.mydev.peoplecore.leave.application.service.LeaveWorkflowService> leaveWorkflows;

    private final AccountAccessService access;
    private final EmployeeService employees;
    private final EnrollmentService enrollments;
    private final ProfileProposalService proposals;
    private final ApprovalRuleService approvalRules;
    private final ObjectMapper mapper;
    private final Clock clock;

    public HrisAccessPolicy(AccountAccessService access, EmployeeService employees,
                            EnrollmentService enrollments, ProfileProposalService proposals,
                            ApprovalRuleService approvalRules, ObjectMapper mapper, Clock clock) {
        this.access = access;
        this.employees = employees;
        this.enrollments = enrollments;
        this.proposals = proposals;
        this.approvalRules = approvalRules;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public void checkCommand(Authentication caller, CommandContext command) {
        if (COMMAND_ACTIVATE.equals(command.commandType())) {
            return;
        }
        Instant now = Instant.now(clock);
        if (ATTENDANCE_COMMANDS.contains(command.commandType())) {
            attendanceCommands.getObject().verifyCommand(command.commandType(), mapper.readTree(command.requestPayload()), caller);
            return;
        }
        if (LEAVE_WORKFLOW_COMMANDS.contains(command.commandType())) {
            leaveWorkflows.getObject().verifyCommand(command.commandType(), mapper.readTree(command.requestPayload()), caller);
            return;
        }
        if ("LEAVE_OPENING".equals(command.commandType()) || "LEAVE_ADJUSTMENT".equals(command.commandType())) {
            requireEntitlementScope(caller, mapper.readTree(command.requestPayload()), now);
            return;
        }
        if (PROFILE_OWNER_COMMANDS.contains(command.commandType())) {
            requireBoundEmployee(caller, now);
            return;
        }
        if (PROFILE_DECISION_COMMANDS.contains(command.commandType())) {
            UserAccount account = access.requireActive(caller, now);
            if (!access.hasRole(account.getId(), HrisRole.HR_ADMIN, now)) {
                throw new AccessDeniedException("Role " + HrisRole.HR_ADMIN + " is required");
            }
            return;
        }
        if (PROFILE_OPERATOR_COMMANDS.contains(command.commandType())
            || RULE_COMMANDS.contains(command.commandType())) {
            access.requireHrOperation(caller, now);
            return;
        }
        if (CONTEXT_PUBLISH_COMMANDS.contains(command.commandType())) {
            requireOrganizationWideHr(caller, now);
            return;
        }
        if (CONTEXT_ASSIGN_COMMANDS.contains(command.commandType())) {
            UserAccount account = access.requireActive(caller, now);
            if (!access.hasRole(account.getId(), HrisRole.HR_ADMIN, now)) {
                throw new AccessDeniedException("Role " + HrisRole.HR_ADMIN + " is required");
            }
            return;
        }
        if (!HR_COMMANDS.contains(command.commandType())) {
            throw new AccessDeniedException("Command is not permitted for this caller");
        }
        UserAccount account = access.requireActive(caller, now);
        if (!access.hasRole(account.getId(), HrisRole.HR_ADMIN, now)) {
            throw new AccessDeniedException("Role " + HrisRole.HR_ADMIN + " is required");
        }
        if (IDENTITY_COMMANDS.contains(command.commandType())) {
            access.requireIdentityAuthority(account.getId(), now);
        }
    }

    private void requireEntitlementScope(Authentication caller, JsonNode payload, Instant now) {
        UUID employeeId = requiredId(payload, "employeePublicId");
        var employee = employees.requireInternalByPublicId(employeeId);
        access.requireHrScope(caller, employees.currentOrgUnit(employee.getId(), now).orElse(null), now);
    }

    private void requireBoundEmployee(Authentication caller, Instant now) {
        UserAccount account = access.requireActive(caller, now);
        if (!access.hasRole(account.getId(), HrisRole.EMPLOYEE, now)) {
            throw new AccessDeniedException("Employee actions require an effective EMPLOYEE grant");
        }
        if (access.ownEmployeePublicId(account).isEmpty()) {
            throw new AccessDeniedException("Caller has no bound employee record");
        }
    }

    @Override
    public void checkReplay(Authentication caller, CommandContext command, String recordedResult) {
        checkCommand(caller, command);
        if (ATTENDANCE_COMMANDS.contains(command.commandType())) {
            attendanceCommands.getObject().verifyReplay(command.commandType(), mapper.readTree(command.requestPayload()), recordedResult, caller);
            return;
        }
        if (LEAVE_WORKFLOW_COMMANDS.contains(command.commandType())) {
            leaveWorkflows.getObject().verifyReplay(command.commandType(), mapper.readTree(command.requestPayload()),
                recordedResult(recordedResult), caller);
            return;
        }
        if ("LEAVE_OPENING".equals(command.commandType()) || "LEAVE_ADJUSTMENT".equals(command.commandType())) {
            requireEntitlementScope(caller, mapper.readTree(command.requestPayload()), Instant.now(clock));
            return;
        }
        if (COMMAND_ACTIVATE.equals(command.commandType())) {
            checkActivationReplay(caller, recordedResult);
            return;
        }
        if (PROFILE_OWNER_COMMANDS.contains(command.commandType())) {
            UUID ownerProposal = recordedProposal(recordedResult);
            proposals.lockForReplay(ownerProposal);
            proposals.requireOwnershipForReplay(ownerProposal, caller);
            return;
        }
        if (PROFILE_DECISION_COMMANDS.contains(command.commandType())) {
            UUID proposalPublicId = recordedProposal(recordedResult);
            ReplaySnapshot snapshot = proposals.lockForReplay(proposalPublicId);
            proposals.requireHrScopeForReplay(proposalPublicId, caller);
            if (snapshot.assigneeAccountPublicId() != null) {
                requireSnapshotAssignee(snapshot, caller);
                return;
            }
            UUID recordedAssignmentId = recordedAssignment(recordedResult);
            if (!proposals.isTerminalAssignment(proposalPublicId, recordedAssignmentId)) {
                throw new AccessDeniedException("Recorded review assignment is no longer current");
            }
            return;
        }
        if (PROFILE_OPERATOR_COMMANDS.contains(command.commandType())) {
            if ("PROFILE_MASTER_CORRECT".equals(command.commandType())) {
                requireMasterCorrectReplay(caller, recordedResult);
                return;
            }
            UUID operatorProposal = recordedProposal(recordedResult);
            proposals.lockForReplay(operatorProposal);
            proposals.requireHrScopeForReplay(operatorProposal, caller);
            return;
        }
        if (RULE_COMMANDS.contains(command.commandType())) {
            if ("PROFILE_RULE_ACTIVATE".equals(command.commandType())) {
                approvalRules.verifyActivateReplayScope(recordedRuleSet(recordedResult), caller);
            } else {
                approvalRules.verifyPublishReplayScope(recordedRuleSet(recordedResult), caller);
            }
            return;
        }
        if (CONTEXT_PUBLISH_COMMANDS.contains(command.commandType())) {
            requireOrganizationWideHr(caller, Instant.now(clock));
            return;
        }
        if (CONTEXT_ASSIGN_COMMANDS.contains(command.commandType())) {
            JsonNode assigned = recordedResult(recordedResult);
            UUID assignedEmployee = requiredId(assigned, "employeePublicId");
            Instant assignedNow = Instant.now(clock);
            UserAccount assignedCaller = access.requireActive(caller, assignedNow);
            String assignedUnit = employees.findByPublicId(assignedEmployee)
                .flatMap(employee -> employees.currentOrgUnit(employee.getId(), assignedNow))
                .orElse(null);
            if (!access.coversUnit(assignedCaller.getId(), assignedUnit, assignedNow)) {
                throw new AccessDeniedException("Recorded scope is no longer authorized");
            }
            if (!access.hasRole(assignedCaller.getId(), HrisRole.HR_ADMIN, assignedNow)) {
                throw new AccessDeniedException("Role " + HrisRole.HR_ADMIN + " is required");
            }
            return;
        }
        Instant now = Instant.now(clock);
        UUID employeeId = recordedEmployee(recordedResult);
        UserAccount account = access.requireActive(caller, now);
        if ("ASSIGNMENT_ADD".equals(command.commandType()) || "ASSIGNMENT_REVISE".equals(command.commandType())) {
            JsonNode result = recordedResult(recordedResult);
            if (!access.coversUnit(account.getId(), requiredText(result, "orgUnit"), now)) {
                throw new AccessDeniedException("Recorded assignment scope is no longer authorized");
            }
            JsonNode before = result.get("before");
            if (before != null && !before.isNull()
                && !access.coversUnit(account.getId(), requiredText(before, "orgUnit"), now)) {
                throw new AccessDeniedException("Recorded assignment history is no longer authorized");
            }
        }
        if ("ACCOUNT_RESTORE".equals(command.commandType())) {
            JsonNode result = recordedResult(recordedResult);
            AccountAccessService.HrAuthority authority = access.hrAuthority(account.getId(), now);
            JsonNode roles = result.get("roles");
            if (roles == null || !roles.isArray() || roles.isEmpty()) {
                throw new AccessDeniedException("Recorded grants cannot be verified");
            }
            for (JsonNode role : roles) {
                if (!role.isString()) {
                    throw new AccessDeniedException("Recorded grants cannot be verified");
                }
                if (!HrisRole.EMPLOYEE.equals(role.stringValue()) && !HrisRole.MANAGER.equals(role.stringValue())) {
                    if (!authority.organizationWide()) {
                        throw new AccessDeniedException("Recorded privileged grant requires organization-wide HR authority");
                    }
                    access.requireIdentityAuthority(account.getId(), now);
                }
            }
            JsonNode scope = result.get("scope");
            if (!authority.organizationWide() && (scope == null || !scope.isString()
                || !authority.units().contains(scope.stringValue()))) {
                throw new AccessDeniedException("Recorded grant scope is no longer authorized");
            }
        }
        String unit = employees.findByPublicId(employeeId)
            .flatMap(employee -> employees.currentOrgUnit(employee.getId(), now))
            .orElse(null);
        if (!access.coversUnit(account.getId(), unit, now)) {
            throw new AccessDeniedException("Recorded scope is no longer authorized");
        }
        if (!access.hasRole(account.getId(), HrisRole.HR_ADMIN, now)) {
            throw new AccessDeniedException("Role " + HrisRole.HR_ADMIN + " is required");
        }
    }

    private void requireOrganizationWideHr(Authentication caller, Instant now) {
        UserAccount account = access.requireHrOperation(caller, now);
        if (!access.hrAuthority(account.getId(), now).organizationWide()) {
            throw new AccessDeniedException("Organization-wide HR authority is required");
        }
    }

    private void requireSnapshotAssignee(ReplaySnapshot snapshot, Authentication caller) {
        if (snapshot.assigneeAccountPublicId() == null) {
            return;
        }
        UserAccount account = access.requireActive(caller, Instant.now(clock));
        if (!snapshot.assigneeAccountPublicId().equals(account.getPublicId())) {
            throw new AccessDeniedException("Recorded review assignment is no longer current");
        }
    }

    private void requireMasterCorrectReplay(Authentication caller, String recordedResult) {
        JsonNode result = recordedResult(recordedResult);
        UUID employeePublicId = requiredId(result, "employeePublicId");
        proposals.lockContactForReplay(employeePublicId);
        Instant now = Instant.now(clock);
        UserAccount account = access.requireHrOperation(caller, now);
        String unit = employees.findByPublicId(employeePublicId)
            .flatMap(employee -> employees.currentOrgUnit(employee.getId(), now))
            .orElse(null);
        access.requireTargetScope(account.getId(), unit, now);
    }

    private UUID recordedProposal(String recordedResult) {
        return requiredId(recordedResult(recordedResult), "proposalPublicId");
    }

    private UUID recordedAssignment(String recordedResult) {
        return requiredId(recordedResult(recordedResult), "assignmentPublicId");
    }

    private UUID recordedRuleSet(String recordedResult) {
        return requiredId(recordedResult(recordedResult), "ruleSetPublicId");
    }

    private void checkActivationReplay(Authentication caller, String recordedResult) {
        JsonNode result = recordedResult(recordedResult);
        UUID invitationId = requiredId(result, "invitationId");
        UUID employeeId = requiredId(result, "employeeId");
        String issuer = requiredText(result, "issuer");
        String subject = requiredText(result, "subject");
        JwtIdentityMapper.OidcIdentity principal;
        try {
            principal = AccountAccessService.identityOf(caller);
        } catch (AuthenticationException | AccessDeniedException | IllegalArgumentException ex) {
            throw new AccessDeniedException("Recorded activation is no longer valid");
        }
        if (!issuer.equals(principal.issuer()) || !subject.equals(principal.subject())) {
            throw new AccessDeniedException("Recorded activation is no longer valid");
        }
        if (!enrollments.activationReplayAllowed(invitationId, issuer, subject, employeeId)) {
            throw new AccessDeniedException("Recorded activation is no longer valid");
        }
    }

    private UUID recordedEmployee(String recordedResult) {
        JsonNode result = recordedResult(recordedResult);
        return requiredId(result, "employeeId");
    }

    private JsonNode recordedResult(String recordedResult) {
        try {
            JsonNode result = mapper.readTree(recordedResult);
            if (result == null || !result.isObject()) {
                throw new AccessDeniedException("Recorded result cannot be verified");
            }
            return result;
        } catch (AccessDeniedException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AccessDeniedException("Recorded result cannot be verified");
        }
    }

    private static UUID requiredId(JsonNode result, String field) {
        String value = requiredText(result, field);
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw new AccessDeniedException("Recorded result cannot be verified");
        }
    }

    private static String requiredText(JsonNode result, String field) {
        JsonNode node = result.get(field);
        if (node == null || !node.isString() || node.stringValue().isBlank()) {
            throw new AccessDeniedException("Recorded result cannot be verified");
        }
        return node.stringValue();
    }

    @Override
    public Set<String> auditResources(Authentication caller) {
        Optional<UserAccount> account;
        try {
            account = access.resolveAccount(caller);
        } catch (AccessDeniedException | IllegalArgumentException ex) {
            return Set.of();
        }
        if (account.isEmpty() || !account.get().isActiveAt(Instant.now(clock))) {
            return Set.of();
        }
        Optional<UUID> employeeId = access.ownEmployeePublicId(account.get());
      return employeeId.map(uuid -> Set.of("EMPLOYEE:" + uuid)).orElseGet(Set::of);
    }
}
