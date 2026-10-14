package id.mydev.peoplecore.organization.application.command;

import id.mydev.peoplecore.common.command.CommandExecutionService;
import id.mydev.peoplecore.common.command.CommandExecutionService.CommandContext;
import id.mydev.peoplecore.common.command.CommandExecutionService.AuditDescriptor;
import id.mydev.peoplecore.common.command.CommandExecutionService.OutboxDescriptor;
import id.mydev.peoplecore.common.command.CommandIds;
import id.mydev.peoplecore.common.command.CommandRetry;
import id.mydev.peoplecore.organization.application.command.ProfilePayloads.ChangePayload;
import id.mydev.peoplecore.organization.application.command.ProfilePayloads.CreateProposalPayload;
import id.mydev.peoplecore.organization.application.command.ProfilePayloads.DecideProposalPayload;
import id.mydev.peoplecore.organization.application.command.ProfilePayloads.EditProposalPayload;
import id.mydev.peoplecore.organization.application.command.ProfilePayloads.MasterCorrectPayload;
import id.mydev.peoplecore.organization.application.command.ProfilePayloads.ReassignProposalPayload;
import id.mydev.peoplecore.organization.application.command.ProfilePayloads.ResubmitProposalPayload;
import id.mydev.peoplecore.organization.application.command.ProfilePayloads.RetryRoutePayload;
import id.mydev.peoplecore.organization.application.command.ProfilePayloads.SubmitProposalPayload;
import id.mydev.peoplecore.organization.application.command.ProfilePayloads.WithdrawProposalPayload;
import id.mydev.peoplecore.organization.application.command.ProfileResults.ContactChange;
import id.mydev.peoplecore.organization.application.command.ProfileResults.ContactCorrectionResult;
import id.mydev.peoplecore.organization.application.command.ProfileResults.ProposalResult;
import id.mydev.peoplecore.organization.application.service.ProfileProposalService;
import id.mydev.peoplecore.organization.application.trail.ProfileTrailMapper;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

@Component
public class ProfileProposalCommands {

    private static final String COMMAND_CREATE = "PROFILE_CREATE";
    private static final String COMMAND_EDIT = "PROFILE_EDIT";
    private static final String COMMAND_SUBMIT = "PROFILE_SUBMIT";
    private static final String COMMAND_RESUBMIT = "PROFILE_RESUBMIT";
    private static final String COMMAND_WITHDRAW = "PROFILE_WITHDRAW";
    private static final String COMMAND_APPROVE = "PROFILE_APPROVE";
    private static final String COMMAND_REJECT = "PROFILE_REJECT";
    private static final String COMMAND_RETURN = "PROFILE_RETURN";
    private static final String COMMAND_REASSIGN = "PROFILE_REASSIGN";
    private static final String COMMAND_RETRY = "PROFILE_ROUTE_RETRY";
    private static final String COMMAND_CORRECT = "PROFILE_MASTER_CORRECT";
    private static final Duration RECEIPT_TTL = Duration.ofDays(7);

    private final ProfileProposalService proposals;
    private final CommandExecutionService commands;
    private final ProfileTrailMapper trail;

    public ProfileProposalCommands(ProfileProposalService proposals, CommandExecutionService commands,
                                   ProfileTrailMapper trail) {
        this.proposals = proposals;
        this.commands = commands;
        this.trail = trail;
    }

    public ProposalResult create(Authentication caller, String idempotencyKey, UUID employeePublicId,
                                 List<ChangePayload> changes) {
        UUID proposalPublicId = CommandIds.derive(COMMAND_CREATE, caller.getName(), idempotencyKey);
        var payload = new CreateProposalPayload(proposalPublicId, employeePublicId, List.copyOf(changes));
        var context = commandContext(caller, COMMAND_CREATE, idempotencyKey, payload);
        return execute(context,
            () -> proposals.createProposal(employeePublicId, proposalPublicId, payload.changes(), caller),
            ProposalResult.class,
            result -> trail.proposalAudit(COMMAND_CREATE, result, "Profile proposal created"),
            result -> trail.outbox(COMMAND_CREATE, result.proposalPublicId(), trail.proposalEvent(result)));
    }

    public ProposalResult edit(Authentication caller, String idempotencyKey, UUID proposalPublicId,
                               long expectedVersion, List<ChangePayload> changes) {
        var payload = new EditProposalPayload(proposalPublicId, expectedVersion, List.copyOf(changes));
        var context = commandContext(caller, COMMAND_EDIT, idempotencyKey, payload);
        return execute(context,
            () -> proposals.editProposal(proposalPublicId, expectedVersion, payload.changes(), caller),
            ProposalResult.class,
            result -> trail.proposalAudit(COMMAND_EDIT, result, "Profile proposal edited"),
            result -> trail.outbox(COMMAND_EDIT, result.proposalPublicId(), trail.proposalEvent(result)));
    }

    public ProposalResult submit(Authentication caller, String idempotencyKey, UUID proposalPublicId,
                                 long expectedVersion) {
        var payload = new SubmitProposalPayload(proposalPublicId, expectedVersion);
        var context = commandContext(caller, COMMAND_SUBMIT, idempotencyKey, payload);
        return execute(context,
            () -> proposals.submitProposal(proposalPublicId, expectedVersion, caller),
            ProposalResult.class,
            result -> trail.proposalAudit(COMMAND_SUBMIT, result, "Profile proposal submitted"),
            result -> trail.outbox(COMMAND_SUBMIT, result.proposalPublicId(), trail.proposalEvent(result)));
    }

    public ProposalResult resubmit(Authentication caller, String idempotencyKey, UUID proposalPublicId,
                                   long expectedVersion, List<ChangePayload> changes) {
        var payload = new ResubmitProposalPayload(proposalPublicId, expectedVersion, List.copyOf(changes));
        var context = commandContext(caller, COMMAND_RESUBMIT, idempotencyKey, payload);
        return execute(context,
            () -> proposals.resubmitProposal(proposalPublicId, expectedVersion, payload.changes(), caller),
            ProposalResult.class,
            result -> trail.proposalAudit(COMMAND_RESUBMIT, result, "Profile proposal resubmitted"),
            result -> trail.outbox(COMMAND_RESUBMIT, result.proposalPublicId(), trail.proposalEvent(result)));
    }

    public ProposalResult withdraw(Authentication caller, String idempotencyKey, UUID proposalPublicId,
                                   long expectedVersion) {
        var payload = new WithdrawProposalPayload(proposalPublicId, expectedVersion);
        var context = commandContext(caller, COMMAND_WITHDRAW, idempotencyKey, payload);
        return execute(context,
            () -> proposals.withdrawProposal(proposalPublicId, expectedVersion, caller),
            ProposalResult.class,
            result -> trail.proposalAudit(COMMAND_WITHDRAW, result, "Profile proposal withdrawn"),
            result -> trail.outbox(COMMAND_WITHDRAW, result.proposalPublicId(), trail.proposalEvent(result)));
    }

    public ProposalResult decide(Authentication caller, String idempotencyKey, UUID proposalPublicId,
                                 long expectedVersion, UUID revisionPublicId, UUID assignmentPublicId,
                                 String commandType, String reason) {
        var payload = new DecideProposalPayload(proposalPublicId, expectedVersion, revisionPublicId,
            assignmentPublicId, commandType, reason);
        var context = commandContext(caller, commandType, idempotencyKey, payload);
        return execute(context,
            () -> proposals.decideProposal(proposalPublicId, expectedVersion, revisionPublicId,
                assignmentPublicId, actionOf(commandType), reason, caller),
            ProposalResult.class,
            result -> trail.proposalAudit(commandType, result, reason),
            result -> trail.outbox(commandType, result.proposalPublicId(), trail.proposalEvent(result)));
    }

    public ProposalResult reassign(Authentication caller, String idempotencyKey, UUID proposalPublicId,
                                   long expectedVersion, UUID assignmentPublicId,
                                   UUID replacementAccountPublicId, String reason) {
        var payload = new ReassignProposalPayload(proposalPublicId, expectedVersion, assignmentPublicId,
            replacementAccountPublicId, reason);
        var context = commandContext(caller, COMMAND_REASSIGN, idempotencyKey, payload);
        return execute(context,
            () -> proposals.reassignProposal(proposalPublicId, expectedVersion, assignmentPublicId,
                replacementAccountPublicId, reason, caller),
            ProposalResult.class,
            result -> trail.proposalAudit(COMMAND_REASSIGN, result, reason),
            result -> trail.outbox(COMMAND_REASSIGN, result.proposalPublicId(), trail.proposalEvent(result)));
    }

    public ProposalResult retryRoute(Authentication caller, String idempotencyKey, UUID proposalPublicId,
                                     long expectedVersion) {
        var payload = new RetryRoutePayload(proposalPublicId, expectedVersion);
        var context = commandContext(caller, COMMAND_RETRY, idempotencyKey, payload);
        return execute(context,
            () -> proposals.retryRoute(proposalPublicId, expectedVersion, caller),
            ProposalResult.class,
            result -> trail.proposalAudit(COMMAND_RETRY, result, "Profile route retried"),
            result -> trail.outbox(COMMAND_RETRY, result.proposalPublicId(), trail.proposalEvent(result)));
    }

    public ContactCorrectionResult masterCorrect(Authentication caller, String idempotencyKey,
                                              UUID employeePublicId, long expectedPhoneVersion,
                                              long expectedEmailVersion, long expectedAddressVersion,
                                              long expectedEmergencyVersion, List<ChangePayload> changes,
                                              String reason) {
        var payload = new MasterCorrectPayload(employeePublicId, expectedPhoneVersion, expectedEmailVersion,
            expectedAddressVersion, expectedEmergencyVersion, List.copyOf(changes), reason);
        var context = commandContext(caller, COMMAND_CORRECT, idempotencyKey, payload);
        var changeRef = new AtomicReference<ContactChange>();
        return execute(context,
            () -> proposals.correctContactProfile(employeePublicId, expectedPhoneVersion, expectedEmailVersion,
                expectedAddressVersion, expectedEmergencyVersion, payload.changes(), reason, changeRef, caller),
            ContactCorrectionResult.class,
            result -> trail.contactAudit(COMMAND_CORRECT, result, reason, changeRef.get()),
            result -> trail.outbox(COMMAND_CORRECT, result.employeePublicId(), trail.contactEvent(result)));
    }

    private static String actionOf(String commandType) {
        return switch (commandType) {
            case COMMAND_APPROVE -> "APPROVE";
            case COMMAND_REJECT -> "REJECT";
            case COMMAND_RETURN -> "RETURN";
            default -> throw new IllegalArgumentException("Unknown decision command: " + commandType);
        };
    }

    private <T> T execute(CommandContext context, Supplier<T> action, Class<T> resultType,
                          Function<T, AuditDescriptor> audit, Function<T, OutboxDescriptor> outbox) {
        return CommandRetry.withRetry(() -> commands.executeCommand(context, action, resultType, audit, outbox));
    }

    private CommandContext commandContext(Authentication caller, String commandType,
                                          String idempotencyKey, Object payload) {
        return new CommandContext(caller.getName(), commandType, idempotencyKey,
            trail.toPayload(payload), RECEIPT_TTL);
    }
}
