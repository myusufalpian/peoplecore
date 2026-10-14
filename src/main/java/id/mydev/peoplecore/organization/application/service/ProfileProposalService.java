package id.mydev.peoplecore.organization.application.service;

import id.mydev.peoplecore.approval.application.command.ApprovalResults.RouteResolution;
import id.mydev.peoplecore.approval.application.service.ApprovalRuleService;
import id.mydev.peoplecore.approval.domain.validation.RouteBlockReasons;
import id.mydev.peoplecore.common.api.InvalidPaginationException;
import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.common.command.AdmissionGuard;
import id.mydev.peoplecore.identity.application.service.AccountAccessService;
import id.mydev.peoplecore.identity.domain.model.HrisRole;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.organization.application.command.ProfilePayloads.ChangePayload;
import id.mydev.peoplecore.organization.application.command.ProfileResults.ContactChange;
import id.mydev.peoplecore.organization.application.command.ProfileResults.AssignmentView;
import id.mydev.peoplecore.organization.application.command.ProfileResults.ChangeView;
import id.mydev.peoplecore.organization.application.command.ProfileResults.ContactCorrectionResult;
import id.mydev.peoplecore.organization.application.command.ProfileResults.ContactProfileResult;
import id.mydev.peoplecore.organization.application.command.ProfileResults.DecisionView;
import id.mydev.peoplecore.organization.application.command.ProfileResults.EvaluationView;
import id.mydev.peoplecore.organization.application.command.ProfileResults.ProposalDetailResult;
import id.mydev.peoplecore.organization.application.command.ProfileResults.ProposalHistoryResult;
import id.mydev.peoplecore.organization.application.command.ProfileResults.ProposalQueueResult;
import id.mydev.peoplecore.organization.application.command.ProfileResults.ProposalResult;
import id.mydev.peoplecore.organization.application.command.ProfileResults.ProposalSummary;
import id.mydev.peoplecore.organization.application.command.ProfileResults.RevisionView;
import id.mydev.peoplecore.organization.application.mapper.ProfileResultMapper;
import id.mydev.peoplecore.organization.domain.exception.ProfileAssignmentStaleException;
import id.mydev.peoplecore.organization.domain.exception.ProfileConflictException;
import id.mydev.peoplecore.organization.domain.exception.ProfileInvalidStateException;
import id.mydev.peoplecore.organization.domain.exception.ProfileNoChangeException;
import id.mydev.peoplecore.organization.domain.exception.ProfileOpenProposalException;
import id.mydev.peoplecore.organization.domain.exception.ProfileReviewerIneligibleException;
import id.mydev.peoplecore.organization.domain.exception.ProfileStaleVersionException;
import id.mydev.peoplecore.organization.domain.exception.ProfileValidationException;
import id.mydev.peoplecore.organization.domain.model.ContactProfile;
import id.mydev.peoplecore.organization.domain.model.Employee;
import id.mydev.peoplecore.organization.domain.model.ProfileAssignment;
import id.mydev.peoplecore.organization.domain.model.ProfileDecision;
import id.mydev.peoplecore.organization.domain.model.ProfileProposal;
import id.mydev.peoplecore.organization.domain.model.ProfileRevision;
import id.mydev.peoplecore.organization.domain.model.ProfileRevisionChange;
import id.mydev.peoplecore.organization.domain.model.ProfileRouteEvaluation;
import id.mydev.peoplecore.organization.domain.repository.ContactProfileRepository;
import id.mydev.peoplecore.organization.domain.repository.ProfileAssignmentRepository;
import id.mydev.peoplecore.organization.domain.repository.ProfileDecisionRepository;
import id.mydev.peoplecore.organization.domain.repository.ProfileProposalRepository;
import id.mydev.peoplecore.organization.domain.repository.ProfileRevisionChangeRepository;
import id.mydev.peoplecore.organization.domain.repository.ProfileRevisionRepository;
import id.mydev.peoplecore.organization.domain.repository.ProfileRouteEvaluationRepository;
import id.mydev.peoplecore.organization.domain.validation.ProfileContactValidator;
import jakarta.persistence.EntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class ProfileProposalService {

    private static final String OPEN_PROPOSAL_CONSTRAINT = "uq_profile_proposal_open";
    private static final int MAX_REASON_LENGTH = 1000;
    private static final int MAX_PAGE_SIZE = 100;

    private final ProfileProposalRepository proposals;
    private final ProfileRevisionRepository revisions;
    private final ProfileRevisionChangeRepository changes;
    private final ProfileAssignmentRepository assignments;
    private final ProfileDecisionRepository decisions;
    private final ProfileRouteEvaluationRepository evaluations;
    private final ContactProfileRepository contactProfiles;
    private final EmployeeService employees;
    private final AccountAccessService access;
    private final ApprovalRuleService routes;
    private final AdmissionGuard admission;
    private final EntityManager entities;
    private final Clock clock;

    public ProfileProposalService(
        ProfileProposalRepository proposals,
        ProfileRevisionRepository revisions,
        ProfileRevisionChangeRepository changes,
        ProfileAssignmentRepository assignments,
        ProfileDecisionRepository decisions,
        ProfileRouteEvaluationRepository evaluations,
        ContactProfileRepository contactProfiles,
        EmployeeService employees,
        AccountAccessService access,
        ApprovalRuleService routes,
        AdmissionGuard admission,
        EntityManager entities,
        Clock clock
    ) {
        this.proposals = proposals;
        this.revisions = revisions;
        this.changes = changes;
        this.assignments = assignments;
        this.decisions = decisions;
        this.evaluations = evaluations;
        this.contactProfiles = contactProfiles;
        this.employees = employees;
        this.access = access;
        this.routes = routes;
        this.admission = admission;
        this.entities = entities;
        this.clock = clock;
    }

    @Transactional
    public ProposalResult createProposal(UUID employeePublicId, UUID proposalPublicId,
                                         List<ChangePayload> submitted, Authentication caller) {
        Objects.requireNonNull(employeePublicId, "employeePublicId must not be null");
        Objects.requireNonNull(proposalPublicId, "proposalPublicId must not be null");
        Instant now = Instant.now(clock);
        Employee employee = employees.requireInternalByPublicId(employeePublicId);
        requireOwner(caller, employeePublicId, now);
        employees.lockEmployee(employee.getId()).orElseThrow(ResourceNotFoundException::new);
        recheckOwner(caller, employeePublicId);
        requireNoOpenProposal(employee.getId());
        ContactProfile contact = ensureContactProfile(employee.getId(), now);
        Map<String, String> validated = validatedGroup(submitted);
        requireActualChange(contact, validated);
        ProfileProposal proposal = saveProposal(proposalPublicId, employee.getId(), now);
        ProfileRevision revision = saveRevision(proposal, latestRevisionNumber(proposal.getId()) + 1,
            validated, contact, null, now);
        proposal.pointTo(revision.getId(), null, Instant.now(clock));
        proposal = proposals.saveAndFlush(proposal);
        return ProfileResultMapper.result(proposal, employeePublicId, revision, null, null);
    }

    @Transactional
    public ProposalResult editProposal(UUID proposalPublicId, long expectedVersion,
                                       List<ChangePayload> submitted, Authentication caller) {
        Instant now = Instant.now(clock);
        ProfileProposal preview = requireProposal(proposalPublicId);
        UUID employeePublicId = employeePublicId(preview.getEmployeeId());
        requireOwner(caller, employeePublicId, now);
        requireOneOfStates(preview, ProfileProposal.STATE_DRAFT, ProfileProposal.STATE_RETURNED);
        employees.lockEmployee(preview.getEmployeeId()).orElseThrow(ResourceNotFoundException::new);
        refreshEntities();
        ProfileProposal proposal = lockProposal(preview.getId());
        requireVersion(proposal, expectedVersion);
        requireOneOfStates(proposal, ProfileProposal.STATE_DRAFT, ProfileProposal.STATE_RETURNED);
        requireOwner(caller, employeePublicId, Instant.now(clock));
        ContactProfile contact = ensureContactProfile(proposal.getEmployeeId(), now);
        Map<String, String> validated = validatedGroup(submitted);
        requireActualChange(contact, validated);
        int nextNumber = latestRevisionNumber(proposal.getId()) + 1;
        ProfileRevision revision = saveRevision(proposal, nextNumber, validated, contact, null, now);
        proposal.pointTo(revision.getId(), null, Instant.now(clock));
        proposal = proposals.saveAndFlush(proposal);
        return ProfileResultMapper.result(proposal, employeePublicId, revision, null, proposal.getBlockedReason());
    }

    @Transactional
    public ProposalResult submitProposal(UUID proposalPublicId, long expectedVersion, Authentication caller) {
        Instant now = Instant.now(clock);
        ProfileProposal preview = requireProposal(proposalPublicId);
        UUID employeePublicId = employeePublicId(preview.getEmployeeId());
        requireOwner(caller, employeePublicId, now);
        requireState(preview, ProfileProposal.STATE_DRAFT);
        employees.lockEmployee(preview.getEmployeeId()).orElseThrow(ResourceNotFoundException::new);
        refreshEntities();
        ProfileProposal proposal = lockProposal(preview.getId());
        requireVersion(proposal, expectedVersion);
        requireOwner(caller, employeePublicId, Instant.now(clock));
        ContactProfile contact = ensureContactProfile(proposal.getEmployeeId(), now);
        ProfileRevision draft = currentRevision(proposal);
        Map<String, String> content = changeMap(changes.findByRevisionId(draft.getId()));
        requireActualChange(contact, content);
        return pinSubmission(proposal, employeePublicId, contact, content, now);
    }

    @Transactional
    public ProposalResult resubmitProposal(UUID proposalPublicId, long expectedVersion,
                                           List<ChangePayload> submitted, Authentication caller) {
        Instant now = Instant.now(clock);
        ProfileProposal preview = requireProposal(proposalPublicId);
        UUID employeePublicId = employeePublicId(preview.getEmployeeId());
        requireOwner(caller, employeePublicId, now);
        requireState(preview, ProfileProposal.STATE_RETURNED);
        employees.lockEmployee(preview.getEmployeeId()).orElseThrow(ResourceNotFoundException::new);
        refreshEntities();
        ProfileProposal proposal = lockProposal(preview.getId());
        requireVersion(proposal, expectedVersion);
        requireOwner(caller, employeePublicId, Instant.now(clock));
        ContactProfile contact = ensureContactProfile(proposal.getEmployeeId(), now);
        Map<String, String> validated = validatedGroup(submitted);
        requireActualChange(contact, validated);
        return pinSubmission(proposal, employeePublicId, contact, validated, now);
    }

    @Transactional
    public ProposalResult withdrawProposal(UUID proposalPublicId, long expectedVersion, Authentication caller) {
        Instant now = Instant.now(clock);
        ProfileProposal preview = requireProposal(proposalPublicId);
        UUID employeePublicId = employeePublicId(preview.getEmployeeId());
        requireOwner(caller, employeePublicId, now);
        requireOneOfStates(preview, ProfileProposal.STATE_DRAFT, ProfileProposal.STATE_PENDING,
            ProfileProposal.STATE_RETURNED);
        employees.lockEmployee(preview.getEmployeeId()).orElseThrow(ResourceNotFoundException::new);
        refreshEntities();
        ProfileProposal proposal = lockProposal(preview.getId());
        requireVersion(proposal, expectedVersion);
        requireOwner(caller, employeePublicId, Instant.now(clock));
        assignments.findActiveByProposalId(proposal.getId()).ifPresent(active -> {
            active.close(Instant.now(clock));
            assignments.save(active);
        });
        Instant closedAt = Instant.now(clock);
        proposal.transitionTo(ProfileProposal.STATE_CANCELLED, closedAt);
        proposal.clearBlocked(closedAt);
        proposal = proposals.saveAndFlush(proposal);
        return currentResult(proposal, employeePublicId);
    }

    @Transactional
    public ProposalResult decideProposal(UUID proposalPublicId, long expectedVersion, UUID revisionPublicId,
                                         UUID assignmentPublicId, String action, String reason,
                                         Authentication caller) {
        Objects.requireNonNull(action, "action must not be null");
        Instant now = Instant.now(clock);
        ProfileProposal preview = requireProposal(proposalPublicId);
        UUID employeePublicId = employeePublicId(preview.getEmployeeId());
        UserAccount previewCaller = access.requireActive(caller, now);
        requireReviewer(previewCaller,
            employees.currentOrgUnit(preview.getEmployeeId(), now).orElse(null), now);
        requireState(preview, ProfileProposal.STATE_PENDING);
        employees.lockEmployee(preview.getEmployeeId()).orElseThrow(ResourceNotFoundException::new);
        refreshEntities();
        ProfileProposal proposal = lockProposal(preview.getId());
        Instant fresh = Instant.now(clock);
        UserAccount current = access.requireActive(caller, fresh);
        String targetUnit = employees.currentOrgUnit(proposal.getEmployeeId(), fresh).orElse(null);
        requireReviewer(current, targetUnit, fresh);
        requireVersion(proposal, expectedVersion);
        requireState(proposal, ProfileProposal.STATE_PENDING);
        if (employeePublicId.equals(access.ownEmployeePublicId(current).orElse(null))) {
            throw new AccessDeniedException("Reviewer cannot decide their own proposal");
        }
        ProfileRevision revision = currentRevision(proposal);
        if (!revision.getPublicId().equals(revisionPublicId)) {
            throw new ProfileAssignmentStaleException("Profile revision is no longer current");
        }
        ProfileAssignment assignment = assignments.findActiveByProposalId(proposal.getId())
            .orElseThrow(() -> new ProfileAssignmentStaleException("Profile review assignment is not active"));
        if (!assignment.getPublicId().equals(assignmentPublicId)
            || !assignment.getRevisionId().equals(revision.getId())) {
            throw new ProfileAssignmentStaleException("Profile review assignment is no longer current");
        }
        if (!assignment.getAssigneeAccountPublicId().equals(current.getPublicId())) {
            throw new AccessDeniedException("Only the assigned reviewer may decide this proposal");
        }
        List<ProfileRevisionChange> content = changes.findByRevisionId(revision.getId());
        if (ProfileDecision.ACTION_APPROVE.equals(action)) {
            return approve(proposal, employeePublicId, revision, assignment, content, current, fresh);
        }
        if (ProfileDecision.ACTION_REJECT.equals(action)) {
            String decisionReason = requireReason(reason);
            return closeWithDecision(proposal, employeePublicId, revision, assignment, content,
                ProfileProposal.STATE_REJECTED, ProfileDecision.ACTION_REJECT, current, decisionReason, fresh);
        }
        if (ProfileDecision.ACTION_RETURN.equals(action)) {
            String decisionReason = requireReason(reason);
            return closeWithDecision(proposal, employeePublicId, revision, assignment, content,
                ProfileProposal.STATE_RETURNED, ProfileDecision.ACTION_RETURN, current, decisionReason, fresh);
        }
        throw new ProfileValidationException("Unknown decision action: " + action);
    }

    @Transactional
    public ProposalResult reassignProposal(UUID proposalPublicId, long expectedVersion, UUID assignmentPublicId,
                                           UUID replacementAccountPublicId, String reason, Authentication caller) {
        Objects.requireNonNull(replacementAccountPublicId, "replacementAccountPublicId must not be null");
        Instant now = Instant.now(clock);
        ProfileProposal preview = requireProposal(proposalPublicId);
        UUID employeePublicId = employeePublicId(preview.getEmployeeId());
        UserAccount previewOperator = access.requireHrOperation(caller, now);
        access.requireTargetScope(previewOperator.getId(),
            employees.currentOrgUnit(preview.getEmployeeId(), now).orElse(null), now);
        requireState(preview, ProfileProposal.STATE_PENDING);
        employees.lockEmployee(preview.getEmployeeId()).orElseThrow(ResourceNotFoundException::new);
        refreshEntities();
        ProfileProposal proposal = lockProposal(preview.getId());
        Instant fresh = Instant.now(clock);
        UserAccount currentOperator = access.requireHrOperation(caller, fresh);
        String targetUnit = employees.currentOrgUnit(proposal.getEmployeeId(), fresh).orElse(null);
        access.requireTargetScope(currentOperator.getId(), targetUnit, fresh);
        requireVersion(proposal, expectedVersion);
        requireState(proposal, ProfileProposal.STATE_PENDING);
        ProfileAssignment active = assignments.findActiveByProposalId(proposal.getId())
            .orElseThrow(() -> new ProfileAssignmentStaleException("Profile review assignment is not active"));
        if (!active.getPublicId().equals(assignmentPublicId)) {
            throw new ProfileAssignmentStaleException("Profile review assignment is no longer current");
        }
        UserAccount replacement = access.findAccountByPublicId(replacementAccountPublicId)
            .orElseThrow(() -> new ProfileReviewerIneligibleException("Replacement reviewer is unknown"));
        AccountAccessService.requireActive(replacement, fresh);
        if (!access.hasRole(replacement.getId(), HrisRole.HR_ADMIN, fresh)
            || !access.coversUnit(replacement.getId(), targetUnit, fresh)) {
            throw new ProfileReviewerIneligibleException("Replacement reviewer is not eligible");
        }
        if (employeePublicId.equals(access.ownEmployeePublicId(replacement).orElse(null))) {
            throw new ProfileReviewerIneligibleException("Employee cannot review their own proposal");
        }
        String reassignmentReason = requireReason(reason);
        Instant reassignedAt = Instant.now(clock);
        active.supersede(reassignmentReason, caller.getName(), reassignedAt);
        assignments.save(active);
        ProfileAssignment next = new ProfileAssignment(UUID.randomUUID(), proposal.getId(),
            active.getRevisionId(), replacementAccountPublicId, reassignedAt);
        assignments.save(next);
        proposal.pointTo(proposal.getCurrentRevisionId(), next.getId(), reassignedAt);
        proposal = proposals.saveAndFlush(proposal);
        return currentResult(proposal, employeePublicId);
    }

    @Transactional
    public ProposalResult retryRoute(UUID proposalPublicId, long expectedVersion, Authentication caller) {
        Instant now = Instant.now(clock);
        ProfileProposal preview = requireProposal(proposalPublicId);
        UUID employeePublicId = employeePublicId(preview.getEmployeeId());
        UserAccount previewOperator = access.requireHrOperation(caller, now);
        access.requireTargetScope(previewOperator.getId(),
            employees.currentOrgUnit(preview.getEmployeeId(), now).orElse(null), now);
        requireState(preview, ProfileProposal.STATE_PENDING);
        if (preview.getBlockedReason() == null) {
            throw new ProfileInvalidStateException("Proposal route is not blocked");
        }
        employees.lockEmployee(preview.getEmployeeId()).orElseThrow(ResourceNotFoundException::new);
        refreshEntities();
        ProfileProposal proposal = lockProposal(preview.getId());
        Instant fresh = Instant.now(clock);
        UserAccount currentOperator = access.requireHrOperation(caller, fresh);
        access.requireTargetScope(currentOperator.getId(),
            employees.currentOrgUnit(proposal.getEmployeeId(), fresh).orElse(null), fresh);
        requireVersion(proposal, expectedVersion);
        requireState(proposal, ProfileProposal.STATE_PENDING);
        ProfileRevision revision = currentRevision(proposal);
        RouteResolution resolution = routes.resolveForSubmission(revision.getEvaluatedOrgUnit(),
            revision.getEvaluatedJobLevel(), employeePublicId, fresh);
        Instant evaluatedAt = Instant.now(clock);
        evaluations.save(new ProfileRouteEvaluation(proposal.getId(), revision.getId(), evaluatedAt,
            resolution.ruleSetPublicId(), revision.getEvaluatedOrgUnit(), revision.getEvaluatedJobLevel(),
            resolution.matched() ? ProfileRouteEvaluation.RESULT_MATCHED : ProfileRouteEvaluation.RESULT_BLOCKED,
            resolution.ruleId(), resolution.reviewerAccountPublicId(), resolution.blockedReason()));
        if (!resolution.matched()) {
            proposal.markBlocked(resolution.blockedReason(), evaluatedAt);
            proposal = proposals.saveAndFlush(proposal);
            return currentResult(proposal, employeePublicId);
        }
        ProfileAssignment assignment = new ProfileAssignment(UUID.randomUUID(), proposal.getId(),
            revision.getId(), resolution.reviewerAccountPublicId(), evaluatedAt);
        assignments.save(assignment);
        proposal.pointTo(revision.getId(), assignment.getId(), evaluatedAt);
        proposal.clearBlocked(evaluatedAt);
        proposal = proposals.saveAndFlush(proposal);
        return currentResult(proposal, employeePublicId);
    }

    @Transactional
    public ContactCorrectionResult correctContactProfile(UUID employeePublicId, long expectedPhoneVersion,
                                                      long expectedEmailVersion, long expectedAddressVersion,
                                                      long expectedEmergencyVersion, List<ChangePayload> submitted,
                                                      String reason, AtomicReference<ContactChange> changeRef,
                                                      Authentication caller) {
        Instant now = Instant.now(clock);
        UserAccount hr = access.requireHrOperation(caller, now);
        Employee employee = employees.requireInternalByPublicId(employeePublicId);
        access.requireTargetScope(hr.getId(), employees.currentOrgUnit(employee.getId(), now).orElse(null), now);
        String correctionReason = requireReason(reason);
        Assert.notNull(correctionReason, "correction reason is required");
        employees.lockEmployee(employee.getId()).orElseThrow(ResourceNotFoundException::new);
        refreshEntities();
        Instant fresh = Instant.now(clock);
        UserAccount currentHr = access.requireHrOperation(caller, fresh);
        access.requireTargetScope(currentHr.getId(),
            employees.currentOrgUnit(employee.getId(), fresh).orElse(null), fresh);
        ContactProfile contact = ensureContactProfile(employee.getId(), fresh);
        ContactProfile locked = contactProfiles.lockByEmployeeId(employee.getId())
            .orElseThrow(ResourceNotFoundException::new);
        Map<String, String> validated = validatedGroup(submitted);
        requireActualChange(locked, validated);
        requireTouchedVersions(locked, validated, expectedPhoneVersion, expectedEmailVersion,
            expectedAddressVersion, expectedEmergencyVersion);
        Map<String, String> before = new LinkedHashMap<>();
        for (String field : validated.keySet()) {
            before.put(field, locked.valueOf(field));
        }
        Instant appliedAt = Instant.now(clock);
        locked.applyChanges(validated, appliedAt);
        contactProfiles.save(locked);
        changeRef.set(new ContactChange(unmodifiableCopy(before), unmodifiableCopy(validated)));
        return new ContactCorrectionResult(employeePublicId, locked.getPhoneVersion(),
            locked.getEmailVersion(), locked.getAddressVersion(), locked.getEmergencyVersion());
    }

    private static Map<String, String> unmodifiableCopy(Map<String, String> values) {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    @Transactional
    public ProposalDetailResult getProposal(UUID proposalPublicId, Authentication caller) {
        admission.acquire(AdmissionGuard.Mode.SHARED);
        Instant now = Instant.now(clock);
        ProfileProposal proposal = requireProposal(proposalPublicId);
        UUID employeePublicId = employeePublicId(proposal.getEmployeeId());
        requireProposalRead(proposal, employeePublicId, caller, now);
        ProfileRevision revision = currentRevision(proposal);
        List<ProfileRevisionChange> content = changes.findByRevisionId(revision.getId());
        ProfileAssignment assignment = proposal.getCurrentAssignmentId() == null ? null
            : assignments.findById(proposal.getCurrentAssignmentId()).orElse(null);
        Instant fresh = Instant.now(clock);
        requireProposalRead(proposal, employeePublicId, caller, fresh);
        boolean eligible = assignment == null || !ProfileAssignment.STATUS_ACTIVE.equals(assignment.getStatus())
            || deriveAssigneeBlockage(assignment.getAssigneeAccountPublicId(),
                employees.currentOrgUnit(proposal.getEmployeeId(), fresh).orElse(null),
                employeePublicId, fresh) == null;
        return ProfileResultMapper.detail(proposal, employeePublicId, revision, content, assignment,
            eligible, availableActions(proposal, employeePublicId, assignment, eligible, caller, fresh));
    }

    @Transactional
    public ProposalHistoryResult getHistory(UUID proposalPublicId, Authentication caller, int page, int size) {
        return getHistory(proposalPublicId, caller, page, size, 0, 0, 0);
    }

    @Transactional
    public ProposalHistoryResult getHistory(UUID proposalPublicId, Authentication caller, int page, int size,
                                            int decisionPage, int evaluationPage, int assignmentPage) {
        admission.acquire(AdmissionGuard.Mode.SHARED);
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidPaginationException();
        }
        Instant now = Instant.now(clock);
        ProfileProposal proposal = requireProposal(proposalPublicId);
        UUID employeePublicId = employeePublicId(proposal.getEmployeeId());
        requireProposalRead(proposal, employeePublicId, caller, now);
        List<ProfileRevision> revisionPage = revisions.findByProposalIdOrderByRevisionNumberAsc(
            proposal.getId(), PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "revisionNumber")));
        long total = revisions.countByProposalId(proposal.getId());
        List<Long> revisionIds = new ArrayList<>();
        for (ProfileRevision revision : revisionPage) {
            revisionIds.add(revision.getId());
        }
        if (decisionPage < 0 || evaluationPage < 0 || assignmentPage < 0) {
            throw new InvalidPaginationException();
        }
        List<ProfileRevisionChange> allChanges = revisionIds.isEmpty() ? List.of()
            : changes.findByRevisionIdIn(revisionIds);
        List<ProfileDecision> decisionList = decisions.findByProposalIdOrderByCreatedAtDescIdDesc(
            proposal.getId(), PageRequest.of(decisionPage, size));
        List<ProfileRouteEvaluation> evaluationList = evaluations.findByProposalIdOrderByEvaluatedAtDescIdDesc(
            proposal.getId(), PageRequest.of(evaluationPage, size));
        List<ProfileAssignment> assignmentList = assignments.findByProposalIdOrderByIdAsc(
            proposal.getId(), PageRequest.of(assignmentPage, size));
        Map<Long, UUID> revisionReferences = new LinkedHashMap<>();
        Map<Long, UUID> assignmentReferences = new LinkedHashMap<>();
        for (ProfileDecision decision : decisionList) {
            revisionReferences.put(decision.getRevisionId(), revisions.findById(decision.getRevisionId())
                .orElseThrow(ResourceNotFoundException::new).getPublicId());
            if (decision.getAssignmentId() != null) {
                assignmentReferences.put(decision.getAssignmentId(), assignments.findById(decision.getAssignmentId())
                    .orElseThrow(ResourceNotFoundException::new).getPublicId());
            }
        }
        for (ProfileRouteEvaluation evaluation : evaluationList) {
            revisionReferences.put(evaluation.getRevisionId(), revisions.findById(evaluation.getRevisionId())
                .orElseThrow(ResourceNotFoundException::new).getPublicId());
        }
        for (ProfileAssignment assignment : assignmentList) {
            revisionReferences.put(assignment.getRevisionId(), revisions.findById(assignment.getRevisionId())
                .orElseThrow(ResourceNotFoundException::new).getPublicId());
        }
        requireProposalRead(proposal, employeePublicId, caller, Instant.now(clock));
        return ProfileResultMapper.history(proposal, employeePublicId, revisionPage, allChanges,
            decisionList, evaluationList, total, decisions.countByProposalId(proposal.getId()),
            evaluations.countByProposalId(proposal.getId()), assignmentList,
            assignments.countByProposalId(proposal.getId()), revisionReferences, assignmentReferences);
    }

    @Transactional
    public ContactProfileResult getContactProfile(UUID employeePublicId, Authentication caller) {
        admission.acquire(AdmissionGuard.Mode.SHARED);
        Instant now = Instant.now(clock);
        Employee employee = employees.requireInternalByPublicId(employeePublicId);
        requireProposalReadFor(employee, caller, now);
        ContactProfile contact = contactProfiles.findByEmployeeId(employee.getId()).orElse(null);
        requireProposalReadFor(employee, caller, Instant.now(clock));
        return ProfileResultMapper.contactResult(employeePublicId, contact);
    }

    @Transactional
    public ProposalQueueResult queueProposals(Authentication caller, int page, int size) {
        admission.acquire(AdmissionGuard.Mode.SHARED);
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidPaginationException();
        }
        Instant now = Instant.now(clock);
        UserAccount hr = access.requireHrOperation(caller, now);
        AccountAccessService.HrAuthority authority = access.hrAuthority(hr.getId(), now);
        List<String> units = new ArrayList<>(authority.units());
        List<ProfileProposal> pending;
        long total;
        if (authority.organizationWide()) {
            pending = proposals.findPendingQueueAll(size, page * size);
            total = proposals.countPendingQueueAll();
        } else {
            if (units.isEmpty()) {
                return new ProposalQueueResult(List.of(), page, size, 0);
            }
            pending = proposals.findPendingQueueScoped(units, size, page * size);
            total = proposals.countPendingQueueScoped(units);
        }
        List<ProposalSummary> visible = new ArrayList<>();
        for (ProfileProposal proposal : pending) {
            Instant fresh = Instant.now(clock);
            String unit = employees.currentOrgUnit(proposal.getEmployeeId(), fresh).orElse(null);
            if (!authority.organizationWide()
                && (unit == null || !authority.units().contains(unit))) {
                continue;
            }
            UUID employeePublicId = employeePublicId(proposal.getEmployeeId());
            Optional<ProfileAssignment> active =
                assignments.findActiveByProposalId(proposal.getId());
            UUID assignmentPublicId = active.map(ProfileAssignment::getPublicId).orElse(null);
            UUID assignee = active.map(ProfileAssignment::getAssigneeAccountPublicId).orElse(null);
            String derived = assignee == null ? proposal.getBlockedReason()
                : deriveAssigneeBlockage(assignee, unit, employeePublicId, fresh);
            visible.add(new ProposalSummary(proposal.getPublicId(), employeePublicId,
                proposal.getState(), proposal.getBlockedReason(), assignmentPublicId, assignee,
                derived, proposal.getCreatedAt()));
        }
        return new ProposalQueueResult(List.copyOf(visible), page, size, total);
    }

    private String deriveAssigneeBlockage(UUID assigneeAccountPublicId, String unit,
                                          UUID employeePublicId, Instant now) {
        Optional<UserAccount> reviewer = access.findAccountByPublicId(assigneeAccountPublicId);
        if (reviewer.isEmpty() || !reviewer.get().isActiveAt(now)) {
            return RouteBlockReasons.NO_DESIGNATED_REVIEWER;
        }
        UserAccount account = reviewer.get();
        if (!access.hasRole(account.getId(), HrisRole.HR_ADMIN, now)
            || !access.coversUnit(account.getId(), unit, now)) {
            return RouteBlockReasons.REVIEWER_INELIGIBLE;
        }
        if (employeePublicId.equals(access.ownEmployeePublicId(account).orElse(null))) {
            return RouteBlockReasons.SELF_REVIEW_PROHIBITED;
        }
        return null;
    }

    @Transactional(readOnly = true)
    public UUID ownEmployeePublicId(Authentication caller) {
        Instant now = Instant.now(clock);
        UserAccount account = access.requireActive(caller, now);
        return access.ownEmployeePublicId(account)
            .orElseThrow(() -> new AccessDeniedException("Caller has no bound employee record"));
    }

    @Transactional(readOnly = true)
    public UUID requireOwnershipForReplay(UUID proposalPublicId, Authentication caller) {
        Instant now = Instant.now(clock);
        ProfileProposal proposal = requireProposal(proposalPublicId);
        UUID employeePublicId = employeePublicId(proposal.getEmployeeId());
        requireOwner(caller, employeePublicId, now);
        return employeePublicId;
    }

    @Transactional(readOnly = true)
    public void requireHrScopeForReplay(UUID proposalPublicId, Authentication caller) {
        Instant now = Instant.now(clock);
        ProfileProposal proposal = requireProposal(proposalPublicId);
        UserAccount hr = access.requireHrOperation(caller, now);
        access.requireTargetScope(hr.getId(),
            employees.currentOrgUnit(proposal.getEmployeeId(), now).orElse(null), now);
    }

    public record ReplaySnapshot(
        UUID employeePublicId,
        String state,
        UUID assignmentPublicId,
        UUID assigneeAccountPublicId
    ) { }

    @Transactional
    public ReplaySnapshot lockForReplay(UUID proposalPublicId) {
        ProfileProposal preview = requireProposal(proposalPublicId);
        employees.lockEmployee(preview.getEmployeeId()).orElseThrow(ResourceNotFoundException::new);
        refreshEntities();
        ProfileProposal proposal = lockProposal(preview.getId());
        UUID employeePublicId = employeePublicId(proposal.getEmployeeId());
        Optional<ProfileAssignment> active = assignments.findActiveByProposalId(proposal.getId());
        return new ReplaySnapshot(employeePublicId, proposal.getState(),
            active.map(ProfileAssignment::getPublicId).orElse(null),
            active.map(ProfileAssignment::getAssigneeAccountPublicId).orElse(null));
    }

    @Transactional
    public void lockContactForReplay(UUID employeePublicId) {
        Employee employee = employees.requireInternalByPublicId(employeePublicId);
        employees.lockEmployee(employee.getId()).orElseThrow(ResourceNotFoundException::new);
        refreshEntities();
        contactProfiles.lockByEmployeeId(employee.getId())
            .orElseThrow(ResourceNotFoundException::new);
    }

    @Transactional
    public boolean isTerminalAssignment(UUID proposalPublicId, UUID assignmentPublicId) {
        Objects.requireNonNull(assignmentPublicId, "assignmentPublicId must not be null");
        ProfileProposal proposal = requireProposal(proposalPublicId);
        Optional<ProfileAssignment> latest =
            assignments.findFirstByProposalIdOrderByIdDesc(proposal.getId());
        return latest.isPresent()
            && latest.get().getPublicId().equals(assignmentPublicId)
            && ProfileAssignment.STATUS_CLOSED.equals(latest.get().getStatus());
    }

    private ProposalResult pinSubmission(ProfileProposal proposal, UUID employeePublicId,
                                         ContactProfile contact, Map<String, String> content, Instant now) {
        EmployeeService.OrganizationContext context = employees.organizationContext(proposal.getEmployeeId(), now)
            .orElse(null);
        String unit = context == null ? null : context.orgUnit();
        String level = context == null ? null : context.jobLevel();
        RouteResolution resolution = routes.resolveForSubmission(unit, level, employeePublicId, now);
        int nextNumber = latestRevisionNumber(proposal.getId()) + 1;
        ProfileRevision revision = saveRevision(proposal, nextNumber, content, contact,
            new EvaluationSnapshot(resolution.ruleSetPublicId(), now, unit, level,
                resolution.reviewerAccountPublicId(), resolution.blockedReason(),
                context == null ? null : context.assignmentPublicId(),
                context == null ? null : context.predecessorPublicId()), now);
        Instant evaluatedAt = Instant.now(clock);
        evaluations.save(new ProfileRouteEvaluation(proposal.getId(), revision.getId(), evaluatedAt,
            resolution.ruleSetPublicId(), unit, level,
            resolution.matched() ? ProfileRouteEvaluation.RESULT_MATCHED : ProfileRouteEvaluation.RESULT_BLOCKED,
            resolution.ruleId(), resolution.reviewerAccountPublicId(), resolution.blockedReason()));
        Long assignmentId = null;
        if (resolution.matched()) {
            ProfileAssignment assignment = new ProfileAssignment(UUID.randomUUID(), proposal.getId(),
                revision.getId(), resolution.reviewerAccountPublicId(), evaluatedAt);
            assignments.save(assignment);
            assignmentId = assignment.getId();
            proposal.clearBlocked(evaluatedAt);
        } else {
            proposal.markBlocked(resolution.blockedReason(), evaluatedAt);
        }
        proposal.transitionTo(ProfileProposal.STATE_PENDING, evaluatedAt);
        proposal.pointTo(revision.getId(), assignmentId, evaluatedAt);
        proposal = proposals.saveAndFlush(proposal);
        return currentResult(proposal, employeePublicId);
    }

    private record EvaluationSnapshot(UUID ruleSetPublicId, Instant evaluatedAt, String orgUnit,
                                      String jobLevel, UUID reviewerAccountPublicId, String blockedReason,
                                      UUID orgAssignmentPublicId, UUID predecessorPublicId) { }

    private ProposalResult approve(ProfileProposal proposal, UUID employeePublicId, ProfileRevision revision,
                                   ProfileAssignment assignment, List<ProfileRevisionChange> content,
                                   UserAccount reviewer, Instant now) {
        ContactProfile contact = contactProfiles.lockByEmployeeId(proposal.getEmployeeId())
            .orElseThrow(ResourceNotFoundException::new);
        requireNoConflict(contact, content);
        Map<String, String> applied = new LinkedHashMap<>();
        for (ProfileRevisionChange change : content) {
            applied.put(change.getFieldName(), change.getProposedValue());
        }
        contact.applyChanges(applied, now);
        contactProfiles.save(contact);
        assignment.close(now);
        assignments.save(assignment);
        decisions.save(new ProfileDecision(proposal.getId(), revision.getId(), assignment.getId(),
            ProfileDecision.ACTION_APPROVE, reviewer.getPublicId(), null, now));
        proposal.transitionTo(ProfileProposal.STATE_APPROVED, now);
        proposal.clearBlocked(now);
        proposal = proposals.saveAndFlush(proposal);
        return currentResult(proposal, employeePublicId);
    }

    private ProposalResult closeWithDecision(ProfileProposal proposal, UUID employeePublicId,
                                             ProfileRevision revision, ProfileAssignment assignment,
                                             List<ProfileRevisionChange> content, String state,
                                             String action, UserAccount reviewer, String reason, Instant now) {
        assignment.close(now);
        assignments.save(assignment);
        decisions.save(new ProfileDecision(proposal.getId(), revision.getId(), assignment.getId(),
            action, reviewer.getPublicId(), reason, now));
        proposal.transitionTo(state, now);
        proposal.clearBlocked(now);
        proposal = proposals.saveAndFlush(proposal);
        return currentResult(proposal, employeePublicId);
    }

    private void requireNoConflict(ContactProfile contact, List<ProfileRevisionChange> content) {
        for (ProfileRevisionChange change : content) {
            String group = ProfileRevisionChange.groupOf(change.getFieldName());
            if (contact.versionOf(group) != change.getBaselineVersion()
                || !Objects.equals(contact.valueOf(change.getFieldName()), change.getBaselineValue())) {
                throw new ProfileConflictException(
                    "Active profile values changed during review; return for revision");
            }
        }
    }

    private void requireTouchedVersions(ContactProfile contact, Map<String, String> validated,
                                        long expectedPhone, long expectedEmail,
                                        long expectedAddress, long expectedEmergency) {
        for (String field : validated.keySet()) {
            String group = ProfileRevisionChange.groupOf(field);
            long expected = switch (group) {
                case ContactProfile.GROUP_PHONE -> expectedPhone;
                case ContactProfile.GROUP_EMAIL -> expectedEmail;
                case ContactProfile.GROUP_ADDRESS -> expectedAddress;
                case ContactProfile.GROUP_EMERGENCY -> expectedEmergency;
                default -> throw new ProfileValidationException("Unknown profile group: " + group);
            };
            if (contact.versionOf(group) != expected) {
                throw new ProfileConflictException("Contact profile changed; reload before correcting");
            }
        }
    }

    private Map<String, String> validatedGroup(List<ChangePayload> submitted) {
        Objects.requireNonNull(submitted, "changes must not be null");
        if (submitted.isEmpty()) {
            throw new ProfileValidationException("At least one profile change is required");
        }
        Map<String, String> raw = new LinkedHashMap<>();
        for (ChangePayload change : submitted) {
            Objects.requireNonNull(change, "change must not be null");
            String field;
            try {
                field = ProfileRevisionChange.requireKnownField(change.fieldName());
            } catch (IllegalArgumentException ex) {
                throw new ProfileValidationException("Unknown profile field: " + change.fieldName());
            }
            if (raw.containsKey(field)) {
                throw new ProfileValidationException("Duplicate profile field: " + field);
            }
            raw.put(field, change.proposedValue());
        }
        requireCompleteEmergencyGroup(raw);
        return ProfileContactValidator.validatedChanges(raw);
    }

    private void requireCompleteEmergencyGroup(Map<String, String> raw) {
        boolean touchesEmergency = raw.keySet().stream().anyMatch(ProfileRevisionChange::isEmergencyField);
        if (!touchesEmergency) {
            return;
        }
        List<String> required = List.of(ProfileRevisionChange.FIELD_EMERGENCY_NAME,
            ProfileRevisionChange.FIELD_EMERGENCY_RELATIONSHIP,
            ProfileRevisionChange.FIELD_EMERGENCY_PHONE);
        for (String field : required) {
            if (!raw.containsKey(field) || raw.get(field) == null) {
                throw new ProfileValidationException(
                    "Emergency contact changes require a complete contact group");
            }
        }
    }

    private void requireActualChange(ContactProfile contact, Map<String, String> validated) {
        if (validated.isEmpty()) {
            throw new ProfileValidationException("At least one profile change is required");
        }
        for (Map.Entry<String, String> change : validated.entrySet()) {
            if (!Objects.equals(contact.valueOf(change.getKey()), change.getValue())) {
                return;
            }
        }
        throw new ProfileNoChangeException("Submitted values do not change active profile data");
    }

    private ProfileProposal saveProposal(UUID proposalPublicId, Long employeeId, Instant now) {
        ProfileProposal proposal = new ProfileProposal(proposalPublicId, employeeId, now);
        try {
            proposal = proposals.saveAndFlush(proposal);
        } catch (DataIntegrityViolationException ex) {
            throw translateOpenProposalConflict(ex);
        }
        return proposal;
    }

    private void requireNoOpenProposal(Long employeeId) {
        if (proposals.findOpenByEmployeeId(employeeId).isPresent()) {
            throw new ProfileOpenProposalException("Employee already has an open profile proposal");
        }
    }

    private ProfileRevision saveRevision(ProfileProposal proposal, int number, Map<String, String> content,
                                          ContactProfile contact, EvaluationSnapshot snapshot, Instant now) {
        UUID revisionPublicId = UUID.randomUUID();
        ProfileRevision revision = new ProfileRevision(revisionPublicId, proposal.getId(), number,
            snapshot == null ? null : snapshot.ruleSetPublicId(),
            snapshot == null ? null : snapshot.evaluatedAt(),
            snapshot == null ? null : snapshot.orgUnit(),
            snapshot == null ? null : snapshot.jobLevel(),
            snapshot == null ? null : snapshot.reviewerAccountPublicId(),
            snapshot == null ? null : snapshot.blockedReason(), now);
        if (snapshot != null) {
            revision.pinOrganizationContext(snapshot.orgAssignmentPublicId(), snapshot.predecessorPublicId());
        }
        revisions.save(revision);
        entities.flush();
        for (Map.Entry<String, String> change : content.entrySet()) {
            String group = ProfileRevisionChange.groupOf(change.getKey());
            changes.save(new ProfileRevisionChange(revision.getId(), change.getKey(),
                contact.valueOf(change.getKey()), change.getValue(), contact.versionOf(group)));
        }
        ProfileRevision sealed = revisions.lockById(revision.getId())
            .orElseThrow(() -> new IllegalStateException("Proposal revision is missing"));
        sealed.seal();
        revisions.save(sealed);
        return sealed;
    }

    private Map<String, String> changeMap(List<ProfileRevisionChange> persisted) {
        Map<String, String> content = new LinkedHashMap<>();
        for (ProfileRevisionChange change : persisted) {
            content.put(change.getFieldName(), change.getProposedValue());
        }
        return content;
    }

    private ContactProfile ensureContactProfile(Long employeeId, Instant now) {
        return contactProfiles.findByEmployeeId(employeeId).orElseGet(() -> {
            ContactProfile created = new ContactProfile(employeeId, now);
            contactProfiles.save(created);
            return created;
        });
    }

    private void requireOwner(Authentication caller, UUID employeePublicId, Instant now) {
        UserAccount account = access.requireActive(caller, now);
        if (!access.hasRole(account.getId(), HrisRole.EMPLOYEE, now)) {
            throw new AccessDeniedException("Employee actions require an effective EMPLOYEE grant");
        }
        UUID own = access.ownEmployeePublicId(account).orElse(null);
        if (!employeePublicId.equals(own)) {
            throw new AccessDeniedException("Only the employee concerned may change their proposal");
        }
    }

    private List<String> availableActions(ProfileProposal proposal, UUID employeePublicId,
                                          ProfileAssignment assignment, boolean eligible,
                                          Authentication caller, Instant at) {
        UserAccount account = access.requireActive(caller, at);
        UUID ownEmployeeId = access.ownEmployeePublicId(account).orElse(null);
        boolean owner = employeePublicId.equals(ownEmployeeId)
            && access.hasRole(account.getId(), HrisRole.EMPLOYEE, at);
        List<String> actions = new ArrayList<>();
        String state = proposal.getState();
        if (owner && (ProfileProposal.STATE_DRAFT.equals(state) || ProfileProposal.STATE_RETURNED.equals(state))) {
            actions.add("EDIT");
            actions.add(ProfileProposal.STATE_DRAFT.equals(state) ? "SUBMIT" : "RESUBMIT");
        }
        if (owner && (ProfileProposal.STATE_DRAFT.equals(state) || ProfileProposal.STATE_RETURNED.equals(state)
            || ProfileProposal.STATE_PENDING.equals(state))) {
            actions.add("WITHDRAW");
        }
        String unit = employees.currentOrgUnit(proposal.getEmployeeId(), at).orElse(null);
        boolean hr = access.hasRole(account.getId(), HrisRole.HR_ADMIN, at)
            && access.coversUnit(account.getId(), unit, at);
        if (hr && ProfileProposal.STATE_PENDING.equals(state)) {
            if (assignment != null && assignment.isActive()) {
                actions.add("REASSIGN");
                if (eligible && account.getPublicId().equals(assignment.getAssigneeAccountPublicId())
                    && !employeePublicId.equals(ownEmployeeId)) {
                    actions.addAll(List.of("APPROVE", "REJECT", "RETURN"));
                }
            } else if (proposal.getBlockedReason() != null) {
                actions.add("RETRY_ROUTING");
            }
        }
        return List.copyOf(actions);
    }

    private void recheckOwner(Authentication caller, UUID employeePublicId) {
        refreshEntities();
        requireOwner(caller, employeePublicId, Instant.now(clock));
    }

    private void requireReviewer(UserAccount reviewer, String targetUnit, Instant now) {
        AccountAccessService.requireActive(reviewer, now);
        if (!access.hasRole(reviewer.getId(), HrisRole.HR_ADMIN, now)) {
            throw new AccessDeniedException("Profile review requires the HR role");
        }
        access.requireTargetScope(reviewer.getId(), targetUnit, now);
    }

    private void requireProposalRead(ProfileProposal proposal, UUID employeePublicId,
                                     Authentication caller, Instant now) {
        UserAccount account = access.requireActive(caller, now);
        String unit = employees.currentOrgUnit(proposal.getEmployeeId(), now).orElse(null);
        if (access.coversUnit(account.getId(), unit, now)) {
            return;
        }
        if (access.activeRoles(account.getId(), now).isEmpty()) {
            throw new ResourceNotFoundException();
        }
        if (!employeePublicId.equals(access.ownEmployeePublicId(account).orElse(null))) {
            throw new ResourceNotFoundException();
        }
    }

    private void requireProposalReadFor(Employee employee, Authentication caller, Instant now) {
        UserAccount account = access.requireActive(caller, now);
        String unit = employees.currentOrgUnit(employee.getId(), now).orElse(null);
        if (access.coversUnit(account.getId(), unit, now)) {
            return;
        }
        if (access.activeRoles(account.getId(), now).isEmpty()) {
            throw new ResourceNotFoundException();
        }
        if (!employee.getPublicId().equals(access.ownEmployeePublicId(account).orElse(null))) {
            throw new ResourceNotFoundException();
        }
    }

    private UUID employeePublicId(Long employeeId) {
        return employees.findInternalById(employeeId)
            .orElseThrow(ResourceNotFoundException::new).getPublicId();
    }

    private ProfileProposal requireProposal(UUID proposalPublicId) {
        Objects.requireNonNull(proposalPublicId, "proposalPublicId must not be null");
        return proposals.findByPublicId(proposalPublicId).orElseThrow(ResourceNotFoundException::new);
    }

    private ProfileProposal lockProposal(Long id) {
        return proposals.lockById(id).orElseThrow(ResourceNotFoundException::new);
    }

    private ProfileRevision currentRevision(ProfileProposal proposal) {
        if (proposal.getCurrentRevisionId() == null) {
            throw new IllegalStateException("Proposal has no current revision");
        }
        return revisions.findById(proposal.getCurrentRevisionId())
            .orElseThrow(() -> new IllegalStateException("Current proposal revision is missing"));
    }

    private int latestRevisionNumber(Long proposalId) {
        return revisions.findMaxRevisionNumberByProposalId(proposalId);
    }

    private ProposalResult currentResult(ProfileProposal proposal, UUID employeePublicId) {
        ProfileRevision revision = currentRevision(proposal);
        ProfileAssignment assignment = proposal.getCurrentAssignmentId() == null ? null
            : assignments.findById(proposal.getCurrentAssignmentId()).orElse(null);
        UUID assignmentPublicId = assignment == null ? null : assignment.getPublicId();
        return ProfileResultMapper.result(proposal, employeePublicId, revision,
            assignmentPublicId, proposal.getBlockedReason());
    }

    private void requireVersion(ProfileProposal proposal, long expectedVersion) {
        if (proposal.getVersion() != expectedVersion) {
            throw new ProfileStaleVersionException("Profile proposal changed; reload before retrying");
        }
    }

    private void requireState(ProfileProposal proposal, String state) {
        if (!state.equals(proposal.getState())) {
            throw new ProfileInvalidStateException(
                "Proposal action requires state " + state + " but proposal is " + proposal.getState());
        }
    }

    private void requireOneOfStates(ProfileProposal proposal, String... states) {
        for (String state : states) {
            if (state.equals(proposal.getState())) {
                return;
            }
        }
        throw new ProfileInvalidStateException(
            "Proposal action is not allowed in state " + proposal.getState());
    }

    private String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ProfileValidationException("A review reason is required");
        }
        String trimmed = reason.trim();
        if (trimmed.length() > MAX_REASON_LENGTH) {
            throw new ProfileValidationException("Review reason exceeds maximum length 1000");
        }
        return trimmed;
    }

    private ProfileOpenProposalException translateOpenProposalConflict(DataIntegrityViolationException ex) {
        Throwable cause = ex.getMostSpecificCause();
        if (cause != null && String.valueOf(cause.getMessage()).contains(OPEN_PROPOSAL_CONSTRAINT)) {
            return new ProfileOpenProposalException("Employee already has an open profile proposal");
        }
        throw ex;
    }

    private void refreshEntities() {
        entities.flush();
        entities.clear();
    }
}
