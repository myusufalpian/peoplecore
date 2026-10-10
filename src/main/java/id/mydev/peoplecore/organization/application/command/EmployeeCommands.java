package id.mydev.peoplecore.organization.application.command;

import id.mydev.peoplecore.common.command.CommandExecutionService;
import id.mydev.peoplecore.common.command.CommandExecutionService.CommandContext;
import id.mydev.peoplecore.common.command.CommandExecutionService.AuditDescriptor;
import id.mydev.peoplecore.common.command.CommandExecutionService.OutboxDescriptor;
import id.mydev.peoplecore.common.command.CommandIds;
import id.mydev.peoplecore.common.command.CommandRetry;
import id.mydev.peoplecore.organization.application.command.EmployeePayloads.AddAssignmentPayload;
import id.mydev.peoplecore.organization.application.command.EmployeePayloads.CreateEmployeePayload;
import id.mydev.peoplecore.organization.application.command.EmployeePayloads.UpdateEmployeePayload;
import id.mydev.peoplecore.organization.application.command.EmployeePayloads.EmploymentDatesPayload;
import id.mydev.peoplecore.organization.application.command.EmployeePayloads.ReviseAssignmentPayload;
import id.mydev.peoplecore.organization.application.command.EmployeeResults.AssignmentResult;
import id.mydev.peoplecore.organization.application.command.EmployeeResults.EmployeeResult;
import id.mydev.peoplecore.organization.application.service.EmployeeService;
import id.mydev.peoplecore.organization.application.trail.OrganizationTrailMapper;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Component
public class EmployeeCommands {

    private static final String COMMAND_CREATE = "EMPLOYEE_CREATE";
    private static final String COMMAND_UPDATE = "EMPLOYEE_UPDATE";
    private static final String COMMAND_ASSIGN = "ASSIGNMENT_ADD";
    private static final String COMMAND_EMPLOYMENT = "EMPLOYMENT_UPDATE";
    private static final String COMMAND_REVISE = "ASSIGNMENT_REVISE";
    private static final String AGGREGATE_EMPLOYEE = "EMPLOYEE";
    private static final Duration RECEIPT_TTL = Duration.ofDays(7);

    private final EmployeeService employees;
    private final CommandExecutionService commands;
    private final OrganizationTrailMapper trail;

    public EmployeeCommands(EmployeeService employees, CommandExecutionService commands,
                            OrganizationTrailMapper trail) {
        this.employees = employees;
        this.commands = commands;
        this.trail = trail;
    }

    public EmployeeResult create(Authentication caller, String idempotencyKey, String employeeNumber) {
        UUID employeeId = CommandIds.derive(COMMAND_CREATE, caller.getName(), idempotencyKey);
        var payload = new CreateEmployeePayload(employeeId, employeeNumber);
        var context = commandContext(caller, COMMAND_CREATE, idempotencyKey, payload);
        return execute(context,
            () -> employees.create(employeeId, employeeNumber, caller),
            EmployeeResult.class,
            result -> trail.employeeAudit(COMMAND_CREATE, result,
                "Employee record created"),
            result -> trail.outbox(COMMAND_CREATE, AGGREGATE_EMPLOYEE, result.employeeId(),
                trail.employeeEvent(result)));
    }

    public EmployeeResult updateNumber(Authentication caller, String idempotencyKey,
                                       UUID employeeId, String employeeNumber) {
        var payload = new UpdateEmployeePayload(employeeId, employeeNumber);
        var context = commandContext(caller, COMMAND_UPDATE, idempotencyKey, payload);
        return execute(context,
            () -> employees.updateNumber(employeeId, employeeNumber, caller),
            EmployeeResult.class,
            result -> trail.employeeAudit(COMMAND_UPDATE, result,
                "Employee record updated"),
            result -> trail.outbox(COMMAND_UPDATE, AGGREGATE_EMPLOYEE, result.employeeId(),
                trail.employeeEvent(result)));
    }

    public AssignmentResult addAssignment(Authentication caller, String idempotencyKey, UUID employeeId,
                                          String orgUnit, UUID managerId, String jobLevel,
                                          Instant validFrom, Instant validTo) {
        UUID assignmentId = CommandIds.derive(COMMAND_ASSIGN, caller.getName(), idempotencyKey);
        var payload = new AddAssignmentPayload(employeeId, assignmentId, orgUnit, managerId,
            jobLevel, validFrom, validTo);
        var context = commandContext(caller, COMMAND_ASSIGN, idempotencyKey, payload);
        return execute(context,
            () -> employees.addAssignment(employeeId, assignmentId, orgUnit, managerId,
                jobLevel, validFrom, validTo, caller),
            AssignmentResult.class,
            result -> trail.assignmentAudit(COMMAND_ASSIGN, result,
                "Assignment added for " + result.orgUnit()),
            result -> trail.outbox(COMMAND_ASSIGN, AGGREGATE_EMPLOYEE, result.employeeId(),
                trail.assignmentEvent(result)));
    }

    public EmployeeResult updateEmploymentDates(Authentication caller, String key, UUID employeeId,
                                                 LocalDate startDate, LocalDate endDate, String reason) {
        requireReason(reason);
        var payload = new EmploymentDatesPayload(employeeId, startDate, endDate, reason);
        return execute(commandContext(caller, COMMAND_EMPLOYMENT, key, payload),
            () -> employees.updateEmploymentDates(employeeId, startDate, endDate, caller), EmployeeResult.class,
            result -> trail.employeeAudit(COMMAND_EMPLOYMENT, result, reason),
            result -> trail.outbox(COMMAND_EMPLOYMENT, AGGREGATE_EMPLOYEE, employeeId, trail.employeeEvent(result)));
    }

    public AssignmentResult reviseAssignment(Authentication caller, String key, UUID employeeId,
                                               UUID assignmentId, String orgUnit, UUID managerId, String jobLevel,
                                               Instant validFrom, Instant validTo, String reason) {
        requireReason(reason);
        UUID replacementId = CommandIds.derive(COMMAND_REVISE, caller.getName(), key);
        var payload = new ReviseAssignmentPayload(employeeId, assignmentId, replacementId, orgUnit, managerId,
            jobLevel, validFrom, validTo, reason);
        return execute(commandContext(caller, COMMAND_REVISE, key, payload),
            () -> employees.reviseAssignment(employeeId, assignmentId, replacementId, orgUnit, managerId,
                jobLevel, validFrom, validTo, caller), AssignmentResult.class,
            result -> trail.assignmentAudit(COMMAND_REVISE, result, reason),
            result -> trail.outbox(COMMAND_REVISE, AGGREGATE_EMPLOYEE, employeeId, trail.assignmentEvent(result)));
    }

    private static void requireReason(String reason) {
        Assert.hasText(reason, "Change reason is required");
        Assert.isTrue(reason.length() <= 500, "Change reason exceeds maximum length");
    }

    private <T> T execute(CommandContext context, java.util.function.Supplier<T> action,
                          Class<T> resultType,
                          java.util.function.Function<T, AuditDescriptor> audit,
                          java.util.function.Function<T, OutboxDescriptor> outbox) {
        return CommandRetry.withRetry(() -> commands.executeCommand(context, action, resultType, audit, outbox));
    }

    private CommandContext commandContext(Authentication caller, String commandType,
                                           String idempotencyKey, Object payload) {
        // Receipts are namespaced by the authenticated principal name within the single-IdP deployment.
        return new CommandContext(caller.getName(), commandType, idempotencyKey,
            trail.toPayload(payload), RECEIPT_TTL);
    }
}
