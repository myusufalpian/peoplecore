package id.mydev.peoplecore.identity.application.policy;

import id.mydev.peoplecore.common.command.CommandExecutionService.CommandContext;
import id.mydev.peoplecore.common.security.CurrentAccessPolicy;
import id.mydev.peoplecore.identity.application.mapper.JwtIdentityMapper;
import id.mydev.peoplecore.identity.application.service.AccountAccessService;
import id.mydev.peoplecore.identity.application.service.EnrollmentService;
import id.mydev.peoplecore.identity.domain.model.HrisRole;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.organization.application.service.EmployeeService;
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

    private final AccountAccessService access;
    private final EmployeeService employees;
    private final EnrollmentService enrollments;
    private final ObjectMapper mapper;
    private final Clock clock;

    public HrisAccessPolicy(AccountAccessService access, EmployeeService employees,
                            EnrollmentService enrollments, ObjectMapper mapper, Clock clock) {
        this.access = access;
        this.employees = employees;
        this.enrollments = enrollments;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public void checkCommand(Authentication caller, CommandContext command) {
        if (COMMAND_ACTIVATE.equals(command.commandType())) {
            return;
        }
        Instant now = Instant.now(clock);
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

    @Override
    public void checkReplay(Authentication caller, CommandContext command, String recordedResult) {
        checkCommand(caller, command);
        if (COMMAND_ACTIVATE.equals(command.commandType())) {
            checkActivationReplay(caller, recordedResult);
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
