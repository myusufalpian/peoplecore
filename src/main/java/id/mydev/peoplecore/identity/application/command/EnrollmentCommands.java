package id.mydev.peoplecore.identity.application.command;

import id.mydev.peoplecore.common.command.CommandExecutionService;
import id.mydev.peoplecore.common.command.CommandExecutionService.CommandContext;
import id.mydev.peoplecore.common.command.CommandExecutionService.AuditDescriptor;
import id.mydev.peoplecore.common.command.CommandExecutionService.OutboxDescriptor;
import id.mydev.peoplecore.common.command.CommandIds;
import id.mydev.peoplecore.common.command.CommandRetry;
import id.mydev.peoplecore.identity.application.command.EnrollmentPayloads.ActivatePayload;
import id.mydev.peoplecore.identity.application.command.EnrollmentPayloads.IssueInvitationPayload;
import id.mydev.peoplecore.identity.application.command.EnrollmentPayloads.OffboardPayload;
import id.mydev.peoplecore.identity.application.command.EnrollmentPayloads.RebindPayload;
import id.mydev.peoplecore.identity.application.command.EnrollmentPayloads.RehirePayload;
import id.mydev.peoplecore.identity.application.command.EnrollmentPayloads.RestoreAccessPayload;
import id.mydev.peoplecore.identity.application.command.EnrollmentResults.BindingResult;
import id.mydev.peoplecore.identity.application.command.EnrollmentResults.InvitationResult;
import id.mydev.peoplecore.identity.application.command.EnrollmentResults.IssuedInvitation;
import id.mydev.peoplecore.identity.application.command.EnrollmentResults.OffboardResult;
import id.mydev.peoplecore.identity.application.command.EnrollmentResults.RebindResult;
import id.mydev.peoplecore.identity.application.command.EnrollmentResults.RehireResult;
import id.mydev.peoplecore.identity.application.command.EnrollmentResults.RestoreResult;
import id.mydev.peoplecore.identity.application.mapper.JwtIdentityMapper;
import id.mydev.peoplecore.identity.application.service.AccountAccessService;
import id.mydev.peoplecore.identity.application.service.ActivationAttemptService;
import id.mydev.peoplecore.identity.application.service.EnrollmentService;
import id.mydev.peoplecore.identity.application.trail.IdentityTrailMapper;
import id.mydev.peoplecore.identity.domain.exception.BindingConflictException;
import id.mydev.peoplecore.identity.domain.exception.InvitationConflictException;
import id.mydev.peoplecore.identity.domain.exception.InvitationExpiredException;
import id.mydev.peoplecore.identity.application.trail.IdentityTrailMapper.AccessRestoredEvent;
import id.mydev.peoplecore.identity.application.trail.IdentityTrailMapper.BindingEvent;
import id.mydev.peoplecore.identity.application.trail.IdentityTrailMapper.InvitationEvent;
import id.mydev.peoplecore.identity.application.trail.IdentityTrailMapper.RehireEvent;
import id.mydev.peoplecore.identity.application.trail.IdentityTrailMapper.OffboardEvent;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

@Component
public class EnrollmentCommands {

    static final String COMMAND_ISSUE = "INVITATION_ISSUE";
    static final String COMMAND_REISSUE = "INVITATION_REISSUE";
    static final String COMMAND_ACTIVATE = "INVITATION_ACTIVATE";
    static final String COMMAND_OFFBOARD = "ACCOUNT_OFFBOARD";
    static final String COMMAND_REBIND = "ACCOUNT_REBIND";
    static final String COMMAND_REHIRE = "ACCOUNT_REHIRE";
    static final String COMMAND_RESTORE = "ACCOUNT_RESTORE";

    private static final String AGGREGATE_INVITATION = "ENROLLMENT_INVITATION";
    private static final String AGGREGATE_BINDING = "ACCOUNT_BINDING";
    private static final String AGGREGATE_EMPLOYEE = "EMPLOYEE";
    private static final String AGGREGATE_ACCOUNT = "USER_ACCOUNT";
    private static final Duration RECEIPT_TTL = Duration.ofDays(7);

    private final EnrollmentService enrollments;
    private final CommandExecutionService commands;
    private final IdentityTrailMapper trail;
    private final ActivationAttemptService attempts;

    public EnrollmentCommands(EnrollmentService enrollments, CommandExecutionService commands,
                              IdentityTrailMapper trail, ActivationAttemptService attempts) {
        this.enrollments = enrollments;
        this.commands = commands;
        this.trail = trail;
        this.attempts = attempts;
    }

    public IssuedInvitation issue(Authentication caller, String idempotencyKey, UUID employeeId,
                                  String intendedIdentityRef, String expectedIssuer, String expectedSubject) {
        UUID invitationId = CommandIds.derive(COMMAND_ISSUE, caller.getName(), idempotencyKey);
        var payload = new IssueInvitationPayload(employeeId, invitationId, intendedIdentityRef,
            expectedIssuer, expectedSubject, null);
        var context = commandContext(caller, COMMAND_ISSUE, idempotencyKey, payload);
        var secret = new java.util.concurrent.atomic.AtomicReference<String>();
        InvitationResult result = execute(context,
            () -> {
                IssuedInvitation issued = enrollments.issue(employeeId, invitationId, intendedIdentityRef,
                    expectedIssuer, expectedSubject, null, caller);
                secret.set(issued.secret());
                return issued.result();
            },
            InvitationResult.class,
            outcome -> trail.audit(COMMAND_ISSUE, AGGREGATE_INVITATION, outcome.invitationId(),
                "Identity verification: " + intendedIdentityRef),
            outcome -> trail.outbox(COMMAND_ISSUE, AGGREGATE_INVITATION, outcome.invitationId(),
                trail.invitationEvent(outcome)));
        return new IssuedInvitation(result, secret.get());
    }

    public IssuedInvitation reissue(Authentication caller, String idempotencyKey, UUID employeeId,
                                    String intendedIdentityRef, String expectedIssuer, String expectedSubject) {
        UUID invitationId = CommandIds.derive(COMMAND_REISSUE, caller.getName(), idempotencyKey);
        var payload = new IssueInvitationPayload(employeeId, invitationId, intendedIdentityRef,
            expectedIssuer, expectedSubject, null);
        var context = commandContext(caller, COMMAND_REISSUE, idempotencyKey, payload);
        var secret = new java.util.concurrent.atomic.AtomicReference<String>();
        InvitationResult result = execute(context,
            () -> {
                IssuedInvitation issued = enrollments.reissue(employeeId, invitationId, intendedIdentityRef,
                    expectedIssuer, expectedSubject, caller);
                secret.set(issued.secret());
                return issued.result();
            },
            InvitationResult.class,
            outcome -> trail.audit(COMMAND_REISSUE, AGGREGATE_INVITATION, outcome.invitationId(),
                "Identity verification: " + intendedIdentityRef),
            outcome -> trail.outbox(COMMAND_REISSUE, AGGREGATE_INVITATION, outcome.invitationId(),
                trail.invitationEvent(outcome)));
        return new IssuedInvitation(result, secret.get());
    }

    public BindingResult activate(Authentication caller, String idempotencyKey,
                                  UUID invitationId, String secret) {
        JwtIdentityMapper.OidcIdentity principal = AccountAccessService.identityOf(caller);
        attempts.reserveAdmission(invitationId, principal);
        var payload = new ActivatePayload(invitationId, secret);
        var context = commandContext(caller, COMMAND_ACTIVATE, idempotencyKey, payload);
        try {
            return execute(context,
                () -> enrollments.activate(invitationId, secret, caller),
                BindingResult.class,
                result -> trail.audit(COMMAND_ACTIVATE, AGGREGATE_BINDING, result.employeeId(),
                    "Account bound to employee"),
                result -> trail.outbox(COMMAND_ACTIVATE, AGGREGATE_BINDING, result.employeeId(),
                    trail.bindingEvent(result)));
        } catch (InvitationConflictException | InvitationExpiredException | BindingConflictException ex) {
            attempts.recordRejection(invitationId, principal, caller.getName());
            throw ex;
        }
    }

    public OffboardResult offboard(Authentication caller, String idempotencyKey, UUID employeeId,
                                   Instant accessEndsAt, LocalTime cutoffTime, String cutoffTimezone,
                                   String reason) {
        var payload = new OffboardPayload(employeeId, accessEndsAt, cutoffTime, cutoffTimezone, reason);
        var context = commandContext(caller, COMMAND_OFFBOARD, idempotencyKey, payload);
        return execute(context,
            () -> enrollments.offboard(employeeId, accessEndsAt, cutoffTime, cutoffTimezone, caller),
            OffboardResult.class,
            result -> trail.employmentAudit(COMMAND_OFFBOARD, result.employeeId(),
                reason == null ? "Access cut off" : reason, result.before(), result.after()),
            result -> trail.outbox(COMMAND_OFFBOARD, AGGREGATE_EMPLOYEE, result.employeeId(),
                trail.offboardEvent(result, accessEndsAt, reason)));
    }

    public RebindResult rebind(Authentication caller, String idempotencyKey, UUID employeeId,
                               String newIssuer, String newSubject, String reason) {
        Assert.hasText(reason, "Identity verification reference is required in reason");
        Assert.isTrue(reason.length() <= 500, "reason exceeds maximum length");
        var payload = new RebindPayload(employeeId, newIssuer, newSubject, reason);
        var context = commandContext(caller, COMMAND_REBIND, idempotencyKey, payload);
        return execute(context,
            () -> enrollments.rebind(employeeId, newIssuer, newSubject, caller),
            RebindResult.class,
            result -> trail.audit(COMMAND_REBIND, AGGREGATE_BINDING, result.employeeId(),
                reason == null ? "Binding reassociated" : reason),
            result -> trail.outbox(COMMAND_REBIND, AGGREGATE_BINDING, result.employeeId(),
                trail.rebindEvent(result, newIssuer, newSubject)));
    }

    public RehireResult rehire(Authentication caller, String idempotencyKey, UUID employeeId, String reason) {
        var payload = new RehirePayload(employeeId, reason);
        var context = commandContext(caller, COMMAND_REHIRE, idempotencyKey, payload);
        return execute(context,
            () -> enrollments.rehire(employeeId, caller),
            RehireResult.class,
            result -> trail.employmentAudit(COMMAND_REHIRE, result.employeeId(),
                reason == null ? "Employee rehired" : reason, result.before(), result.after()),
            result -> trail.outbox(COMMAND_REHIRE, AGGREGATE_EMPLOYEE, result.employeeId(),
                trail.rehireEvent(result, reason)));
    }

    public RestoreResult restoreAccess(Authentication caller, String idempotencyKey, UUID employeeId,
                                        List<String> roles, String scope, String reason) {
        var payload = new RestoreAccessPayload(employeeId, roles, scope, reason);
        var context = commandContext(caller, COMMAND_RESTORE, idempotencyKey, payload);
        return execute(context,
            () -> enrollments.restoreAccess(employeeId, roles, scope, caller),
            RestoreResult.class,
            result -> trail.audit(COMMAND_RESTORE, AGGREGATE_ACCOUNT, result.accountId(),
                reason == null ? "Access restored after review" : reason),
            result -> trail.outbox(COMMAND_RESTORE, AGGREGATE_ACCOUNT, result.accountId(),
                trail.accessRestoredEvent(result, reason)));
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
