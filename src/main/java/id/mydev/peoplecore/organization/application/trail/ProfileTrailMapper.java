package id.mydev.peoplecore.organization.application.trail;

import id.mydev.peoplecore.common.command.CommandExecutionService.AuditDescriptor;
import id.mydev.peoplecore.common.command.CommandExecutionService.OutboxDescriptor;
import id.mydev.peoplecore.organization.application.command.ProfileResults.ContactChange;
import id.mydev.peoplecore.organization.application.command.ProfileResults.ContactCorrectionResult;
import id.mydev.peoplecore.organization.application.command.ProfileResults.ProposalResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

@Component
public class ProfileTrailMapper {

    private static final int SCHEMA_VERSION = 1;
    private static final String AGGREGATE_PROPOSAL = "PROFILE_PROPOSAL";

    public record ProposalEvent(UUID proposalPublicId, UUID employeePublicId, String state,
                                UUID revisionPublicId, int revisionNumber, UUID assignmentPublicId,
                                String blockedReason) { }

    public record ContactEvent(UUID employeePublicId) { }

    private final ObjectMapper mapper;

    public ProfileTrailMapper(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public AuditDescriptor proposalAudit(String action, ProposalResult result, String reason) {
        return new AuditDescriptor(action, AGGREGATE_PROPOSAL, null, result.proposalPublicId(),
            null, reason, toPayload(new ProposalEvent(result.proposalPublicId(), result.employeePublicId(),
                result.state(), result.revisionPublicId(), result.revisionNumber(),
                result.assignmentPublicId(), result.blockedReason())));
    }

    public AuditDescriptor contactAudit(String action, ContactCorrectionResult result, String reason,
                                        ContactChange change) {
        return new AuditDescriptor(action, AGGREGATE_PROPOSAL, null, result.employeePublicId(),
            null, reason, toPayload(change));
    }

    public OutboxDescriptor outbox(String eventType, UUID aggregatePublicId, Object payload) {
        return new OutboxDescriptor(eventType, SCHEMA_VERSION, AGGREGATE_PROPOSAL, null,
            aggregatePublicId, toPayload(payload));
    }

    public ProposalEvent proposalEvent(ProposalResult result) {
        return new ProposalEvent(result.proposalPublicId(), result.employeePublicId(), result.state(),
            result.revisionPublicId(), result.revisionNumber(), result.assignmentPublicId(),
            result.blockedReason());
    }

    public ContactEvent contactEvent(ContactCorrectionResult result) {
        return new ContactEvent(result.employeePublicId());
    }

    public String toPayload(Object payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (Exception ex) {
            throw new IllegalStateException("Trail payload serialization failed", ex);
        }
    }
}
