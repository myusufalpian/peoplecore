package id.mydev.peoplecore.organization.application.command;

import java.util.List;
import java.util.UUID;

public final class ProfilePayloads {
    private ProfilePayloads() { }

    public record ChangePayload(
        String fieldName,
        String proposedValue
    ) { }

    public record CreateProposalPayload(
        UUID proposalPublicId,
        UUID employeePublicId,
        List<ChangePayload> changes
    ) { }

    public record EditProposalPayload(
        UUID proposalPublicId,
        long expectedVersion,
        List<ChangePayload> changes
    ) { }

    public record SubmitProposalPayload(
        UUID proposalPublicId,
        long expectedVersion
    ) { }

    public record ResubmitProposalPayload(
        UUID proposalPublicId,
        long expectedVersion,
        List<ChangePayload> changes
    ) { }

    public record WithdrawProposalPayload(
        UUID proposalPublicId,
        long expectedVersion
    ) { }

    public record DecideProposalPayload(
        UUID proposalPublicId,
        long expectedVersion,
        UUID revisionPublicId,
        UUID assignmentPublicId,
        String action,
        String reason
    ) { }

    public record ReassignProposalPayload(
        UUID proposalPublicId,
        long expectedVersion,
        UUID assignmentPublicId,
        UUID replacementAccountPublicId,
        String reason
    ) { }

    public record RetryRoutePayload(
        UUID proposalPublicId,
        long expectedVersion
    ) { }

    public record MasterCorrectPayload(
        UUID employeePublicId,
        long expectedPhoneVersion,
        long expectedEmailVersion,
        long expectedAddressVersion,
        long expectedEmergencyVersion,
        List<ChangePayload> changes,
        String reason
    ) { }
}
