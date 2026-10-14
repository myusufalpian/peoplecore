package id.mydev.peoplecore.organization.application.mapper;

import id.mydev.peoplecore.organization.application.command.ProfileResults.AssignmentView;
import id.mydev.peoplecore.organization.application.command.ProfileResults.AssignmentHistoryView;
import id.mydev.peoplecore.organization.application.command.ProfileResults.ChangeView;
import id.mydev.peoplecore.organization.application.command.ProfileResults.ContactProfileResult;
import id.mydev.peoplecore.organization.application.command.ProfileResults.DecisionView;
import id.mydev.peoplecore.organization.application.command.ProfileResults.EvaluationView;
import id.mydev.peoplecore.organization.application.command.ProfileResults.ProposalDetailResult;
import id.mydev.peoplecore.organization.application.command.ProfileResults.ProposalHistoryResult;
import id.mydev.peoplecore.organization.application.command.ProfileResults.ProposalResult;
import id.mydev.peoplecore.organization.application.command.ProfileResults.RevisionView;
import id.mydev.peoplecore.organization.domain.model.ContactProfile;
import id.mydev.peoplecore.organization.domain.model.ProfileAssignment;
import id.mydev.peoplecore.organization.domain.model.ProfileDecision;
import id.mydev.peoplecore.organization.domain.model.ProfileProposal;
import id.mydev.peoplecore.organization.domain.model.ProfileRevision;
import id.mydev.peoplecore.organization.domain.model.ProfileRevisionChange;
import id.mydev.peoplecore.organization.domain.model.ProfileRouteEvaluation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public final class ProfileResultMapper {
    private ProfileResultMapper() { }

    public static ProposalResult result(ProfileProposal proposal, UUID employeePublicId,
                                        ProfileRevision revision, UUID assignmentPublicId,
                                        String blockedReason) {
        return new ProposalResult(proposal.getPublicId(), employeePublicId, proposal.getState(),
            proposal.getVersion(), revision.getPublicId(), revision.getRevisionNumber(),
            assignmentPublicId, blockedReason);
    }

    public static ProposalDetailResult detail(ProfileProposal proposal, UUID employeePublicId,
                                              ProfileRevision revision, List<ProfileRevisionChange> changes,
                                              ProfileAssignment assignment, boolean assignmentEligible,
                                              List<String> availableActions) {
        List<ChangeView> views = new ArrayList<>(changes.size());
        for (ProfileRevisionChange change : changes) {
            views.add(new ChangeView(change.getFieldName(), change.getBaselineValue(),
                change.getProposedValue()));
        }
        AssignmentView assignmentView = assignment == null ? null
            : new AssignmentView(assignment.getPublicId(), assignment.getAssigneeAccountPublicId(),
                assignment.getStatus(), assignment.getReason(), assignment.getCreatedAt(),
                assignmentEligible);
        ProposalResult summary = new ProposalResult(proposal.getPublicId(), employeePublicId,
            proposal.getState(), proposal.getVersion(), revision.getPublicId(),
            revision.getRevisionNumber(), assignmentView == null ? null : assignmentView.assignmentPublicId(),
            proposal.getBlockedReason());
        return new ProposalDetailResult(summary, List.copyOf(views), assignmentView, availableActions);
    }

    public static ProposalHistoryResult history(ProfileProposal proposal, UUID employeePublicId,
                                                 List<ProfileRevision> revisions,
                                                 List<ProfileRevisionChange> allChanges,
                                                 List<ProfileDecision> decisions,
                                                 List<ProfileRouteEvaluation> evaluations,
                                                 long totalRevisions, long totalDecisions,
                                                 long totalEvaluations, List<ProfileAssignment> assignments,
                                                 long totalAssignments, Map<Long, UUID> revisionReferences,
                                                 Map<Long, UUID> assignmentReferences) {
        Map<Long, List<ProfileRevisionChange>> changesByRevision = allChanges.stream()
            .collect(Collectors.groupingBy(ProfileRevisionChange::getRevisionId));
        List<RevisionView> revisionViews = new ArrayList<>(revisions.size());
        for (ProfileRevision revision : revisions) {
            List<ChangeView> changeViews = new ArrayList<>();
            List<ProfileRevisionChange> revisionChanges = new ArrayList<>(
                changesByRevision.getOrDefault(revision.getId(), List.of()));
            revisionChanges.sort(Comparator.comparing(ProfileRevisionChange::getFieldName));
            for (ProfileRevisionChange change : revisionChanges) {
                changeViews.add(new ChangeView(change.getFieldName(), change.getBaselineValue(),
                    change.getProposedValue()));
            }
            revisionViews.add(new RevisionView(revision.getOrgAssignmentPublicId(), revision.getOrgAssignmentPredecessorPublicId(),
                revision.getEvaluatedAt() == null ? "NOT_EVALUATED"
                    : revision.getOrgAssignmentPublicId() == null ? "UNKNOWN" : "RESOLVED", revision.getPublicId(), revision.getRevisionNumber(),
                revision.getRuleSetPublicId(), revision.getEvaluatedAt(), revision.getEvaluatedOrgUnit(),
                revision.getEvaluatedJobLevel(), revision.getRouteReviewerAccountPublicId(),
                revision.getBlockedReason(), List.copyOf(changeViews)));
        }
        List<DecisionView> decisionViews = new ArrayList<>(decisions.size());
        for (ProfileDecision decision : decisions) {
            decisionViews.add(new DecisionView(revisionReferences.get(decision.getRevisionId()),
                assignmentReferences.get(decision.getAssignmentId()), decision.getAction(), decision.getActorAccountPublicId(),
                decision.getReason(), decision.getCreatedAt()));
        }
        List<EvaluationView> evaluationViews = new ArrayList<>(evaluations.size());
        for (ProfileRouteEvaluation evaluation : evaluations) {
            evaluationViews.add(new EvaluationView(revisionReferences.get(evaluation.getRevisionId()), evaluation.getEvaluatedAt(),
                evaluation.getRuleSetPublicId(), evaluation.getOrgUnit(), evaluation.getJobLevel(),
                evaluation.getResult(), evaluation.getRuleId(), evaluation.getReviewerAccountPublicId(),
                evaluation.getBlockedReason()));
        }
        ProfileRevision latest = revisions.isEmpty() ? null : revisions.get(revisions.size() - 1);
        ProposalResult summary = latest == null ? null : new ProposalResult(proposal.getPublicId(),
            employeePublicId, proposal.getState(), proposal.getVersion(), latest.getPublicId(),
            latest.getRevisionNumber(), null, proposal.getBlockedReason());
        List<AssignmentHistoryView> assignmentViews = assignments.stream()
            .map(assignment -> new AssignmentHistoryView(assignment.getPublicId(),
                revisionReferences.get(assignment.getRevisionId()), assignment.getAssigneeAccountPublicId(),
                assignment.getStatus(), assignment.getReason(), assignment.getActor(),
                assignment.getCreatedAt(), assignment.getClosedAt())).toList();
        return new ProposalHistoryResult(summary, List.copyOf(revisionViews),
            List.copyOf(decisionViews), List.copyOf(evaluationViews), assignmentViews, totalRevisions, totalDecisions,
            totalEvaluations, totalAssignments);
    }

    public static ContactProfileResult contactResult(UUID employeePublicId, ContactProfile contact) {
        if (contact == null) {
            return new ContactProfileResult(employeePublicId, null, null, null, null, null, null,
                0, 0, 0, 0);
        }
        return new ContactProfileResult(employeePublicId, contact.getPersonalPhone(),
            contact.getPersonalEmail(), contact.getResidentialAddress(),
            contact.getEmergencyContactName(), contact.getEmergencyContactRelationship(),
            contact.getEmergencyContactPhone(), contact.getPhoneVersion(), contact.getEmailVersion(),
            contact.getAddressVersion(), contact.getEmergencyVersion());
    }
}
