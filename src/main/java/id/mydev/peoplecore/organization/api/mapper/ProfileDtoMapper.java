package id.mydev.peoplecore.organization.api.mapper;

import id.mydev.peoplecore.organization.api.dto.ProfileDto;
import id.mydev.peoplecore.organization.application.command.ProfilePayloads.ChangePayload;
import id.mydev.peoplecore.organization.application.command.ProfileResults.AssignmentView;
import id.mydev.peoplecore.organization.application.command.ProfileResults.ChangeView;
import id.mydev.peoplecore.organization.application.command.ProfileResults.ContactProfileResult;
import id.mydev.peoplecore.organization.application.command.ProfileResults.DecisionView;
import id.mydev.peoplecore.organization.application.command.ProfileResults.EvaluationView;
import id.mydev.peoplecore.organization.application.command.ProfileResults.ProposalDetailResult;
import id.mydev.peoplecore.organization.application.command.ProfileResults.ProposalHistoryResult;
import id.mydev.peoplecore.organization.application.command.ProfileResults.ProposalQueueResult;
import id.mydev.peoplecore.organization.application.command.ProfileResults.ProposalResult;
import id.mydev.peoplecore.organization.application.command.ProfileResults.ProposalSummary;
import id.mydev.peoplecore.organization.application.command.ProfileResults.RevisionView;

import java.util.ArrayList;
import java.util.List;

public final class ProfileDtoMapper {
    private ProfileDtoMapper() { }

    public static ChangePayload payload(ProfileDto.ChangeRequest request) {
        return new ChangePayload(request.fieldName(), request.proposedValue());
    }

    public static List<ChangePayload> payloads(List<ProfileDto.ChangeRequest> requests) {
        List<ChangePayload> payloads = new ArrayList<>(requests.size());
        for (ProfileDto.ChangeRequest request : requests) {
            payloads.add(payload(request));
        }
        return payloads;
    }

    public static ProfileDto.ProposalResponse response(ProposalResult result) {
        return new ProfileDto.ProposalResponse(result.proposalPublicId(), result.employeePublicId(),
            result.state(), result.proposalVersion(), result.revisionPublicId(),
            result.revisionNumber(), result.assignmentPublicId(), result.blockedReason());
    }

    public static ProfileDto.ChangeResponse response(ChangeView change) {
        return new ProfileDto.ChangeResponse(change.fieldName(), change.baselineValue(),
            change.proposedValue());
    }

    public static ProfileDto.AssignmentResponse response(AssignmentView assignment) {
        if (assignment == null) {
            return null;
        }
        return new ProfileDto.AssignmentResponse(assignment.assignmentPublicId(),
            assignment.reviewerAccountPublicId(), assignment.status(), assignment.reason(),
            assignment.createdAt());
    }

    public static ProfileDto.ProposalDetailResponse response(ProposalDetailResult detail) {
        List<ProfileDto.ChangeResponse> changes = new ArrayList<>(detail.changes().size());
        for (ChangeView change : detail.changes()) {
            changes.add(response(change));
        }
        return new ProfileDto.ProposalDetailResponse(response(detail.proposal()),
            List.copyOf(changes), response(detail.assignment()), detail.availableActions());
    }

    public static ProfileDto.HistoryResponse response(ProposalHistoryResult history) {
        List<ProfileDto.RevisionResponse> revisions = new ArrayList<>(history.revisions().size());
        for (RevisionView revision : history.revisions()) {
            List<ProfileDto.ChangeResponse> changes = new ArrayList<>(revision.changes().size());
            for (ChangeView change : revision.changes()) {
                changes.add(response(change));
            }
            revisions.add(new ProfileDto.RevisionResponse(revision.orgAssignmentId(), revision.orgAssignmentPredecessorId(),
                revision.organizationContextStatus(), revision.revisionPublicId(),
                revision.revisionNumber(), revision.ruleSetPublicId(), revision.evaluatedAt(),
                revision.evaluatedOrgUnit(), revision.evaluatedJobLevel(),
                revision.routeReviewerAccountPublicId(), revision.blockedReason(), List.copyOf(changes)));
        }
        List<ProfileDto.DecisionResponse> decisions = new ArrayList<>(history.decisions().size());
        for (DecisionView decision : history.decisions()) {
            decisions.add(new ProfileDto.DecisionResponse(decision.revisionPublicId(), decision.assignmentPublicId(), decision.action(),
                decision.actorAccountPublicId(), decision.reason(), decision.createdAt()));
        }
        List<ProfileDto.EvaluationResponse> evaluations = new ArrayList<>(history.evaluations().size());
        for (EvaluationView evaluation : history.evaluations()) {
            evaluations.add(new ProfileDto.EvaluationResponse(evaluation.revisionPublicId(), evaluation.evaluatedAt(),
                evaluation.ruleSetPublicId(), evaluation.orgUnit(), evaluation.jobLevel(),
                evaluation.result(), evaluation.ruleId(), evaluation.reviewerAccountPublicId(),
                evaluation.blockedReason()));
        }
        return new ProfileDto.HistoryResponse(
            history.proposal() == null ? null : response(history.proposal()),
            List.copyOf(revisions), List.copyOf(decisions), List.copyOf(evaluations),
            history.assignments().stream().map(assignment -> new ProfileDto.AssignmentHistoryResponse(
                assignment.assignmentPublicId(), assignment.revisionPublicId(), assignment.reviewerAccountId(),
                assignment.status(), assignment.reason(), assignment.actor(),
                assignment.createdAt(), assignment.closedAt())).toList());
    }

    public static ProfileDto.QueueResponse response(ProposalQueueResult queue) {
        List<ProfileDto.ProposalSummaryResponse> summaries = new ArrayList<>(queue.proposals().size());
        for (ProposalSummary summary : queue.proposals()) {
            summaries.add(new ProfileDto.ProposalSummaryResponse(summary.proposalPublicId(),
                summary.employeePublicId(), summary.state(), summary.blockedReason(),
                summary.createdAt()));
        }
        return new ProfileDto.QueueResponse(List.copyOf(summaries));
    }

    public static ProfileDto.ContactProfileResponse response(ContactProfileResult result) {
        return new ProfileDto.ContactProfileResponse(result.employeePublicId(), result.personalPhone(),
            result.personalEmail(), result.residentialAddress(), result.emergencyContactName(),
            result.emergencyContactRelationship(), result.emergencyContactPhone(),
            result.phoneVersion(), result.emailVersion(), result.addressVersion(),
            result.emergencyVersion());
    }
}
