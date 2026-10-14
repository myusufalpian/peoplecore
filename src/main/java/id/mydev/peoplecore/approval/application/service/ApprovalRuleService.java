package id.mydev.peoplecore.approval.application.service;

import id.mydev.peoplecore.approval.application.command.ApprovalPayloads.RulePayload;
import id.mydev.peoplecore.approval.application.command.ApprovalResults.ActivationResult;
import id.mydev.peoplecore.approval.application.command.ApprovalResults.RoutePreview;
import id.mydev.peoplecore.approval.application.command.ApprovalResults.RouteResolution;
import id.mydev.peoplecore.approval.application.command.ApprovalResults.RuleSetResult;
import id.mydev.peoplecore.approval.domain.exception.RuleActivationException;
import id.mydev.peoplecore.approval.domain.exception.RulePublishException;
import id.mydev.peoplecore.approval.domain.model.ProfileRule;
import id.mydev.peoplecore.approval.domain.model.ProfileRuleActivation;
import id.mydev.peoplecore.approval.domain.model.ProfileRuleCurrentActivation;
import id.mydev.peoplecore.approval.domain.model.ProfileRuleSet;
import id.mydev.peoplecore.approval.domain.repository.ProfileRuleActivationRepository;
import id.mydev.peoplecore.approval.domain.repository.ProfileRuleCurrentActivationRepository;
import id.mydev.peoplecore.approval.domain.repository.ProfileRuleRepository;
import id.mydev.peoplecore.approval.domain.repository.ProfileRuleSetRepository;
import id.mydev.peoplecore.approval.domain.validation.ProfileRouteEvaluator;
import id.mydev.peoplecore.approval.domain.validation.ProfileRuleSetValidator;
import id.mydev.peoplecore.approval.domain.validation.RouteBlockReasons;
import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.common.command.AdmissionGuard;
import id.mydev.peoplecore.identity.application.service.AccountAccessService;
import id.mydev.peoplecore.identity.domain.model.HrisRole;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.organization.application.policy.OrgUnitPolicy;
import jakarta.persistence.EntityManager;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class ApprovalRuleService {

    private final ProfileRuleSetRepository ruleSets;
    private final ProfileRuleRepository rules;
    private final ProfileRuleActivationRepository activations;
    private final ProfileRuleCurrentActivationRepository currentActivation;
    private final AccountAccessService access;
    private final OrgUnitPolicy orgUnits;
    private final AdmissionGuard admission;
    private final EntityManager entities;
    private final Clock clock;

    public ApprovalRuleService(
        ProfileRuleSetRepository ruleSets,
        ProfileRuleRepository rules,
        ProfileRuleActivationRepository activations,
        ProfileRuleCurrentActivationRepository currentActivation,
        AccountAccessService access,
        OrgUnitPolicy orgUnits,
        AdmissionGuard admission,
        EntityManager entities,
        Clock clock
    ) {
        this.ruleSets = ruleSets;
        this.rules = rules;
        this.activations = activations;
        this.currentActivation = currentActivation;
        this.access = access;
        this.orgUnits = orgUnits;
        this.admission = admission;
        this.entities = entities;
        this.clock = clock;
    }

    @Transactional
    public RuleSetResult publishRuleSet(UUID ruleSetPublicId, List<RulePayload> inputs, Authentication caller) {
        Objects.requireNonNull(ruleSetPublicId, "ruleSetPublicId must not be null");
        Objects.requireNonNull(inputs, "rules must not be null");
        Instant now = Instant.now(clock);
        UserAccount hr = access.requireHrOperation(caller, now);
        requirePublisherScope(hr.getId(), inputs, now);
        if (ruleSets.findByPublicId(ruleSetPublicId).isPresent()) {
            throw new RulePublishException("Profile rule set identifier is already used");
        }
        ProfileRuleSet ruleSet = new ProfileRuleSet(ruleSetPublicId, now);
        ruleSets.saveAndFlush(ruleSet);
        List<ProfileRule> published = buildRules(ruleSet.getId(), inputs, now);
        rules.saveAll(published);
        entities.flush();
        entities.clear();
        ProfileRuleSet locked = ruleSets.lockById(ruleSet.getId())
            .orElseThrow(() -> new RulePublishException("Profile rule set is missing"));
        locked.publish(Instant.now(clock));
        ruleSets.save(locked);
        return new RuleSetResult(ruleSetPublicId, locked.getStatus(), published.size(), locked.getPublishedAt());
    }

    @Transactional
    public ActivationResult activateRuleSet(UUID ruleSetPublicId, Authentication caller) {
        Objects.requireNonNull(ruleSetPublicId, "ruleSetPublicId must not be null");
        Instant now = Instant.now(clock);
        UserAccount hr = access.requireHrOperation(caller, now);
        AccountAccessService.HrAuthority authority = access.hrAuthority(hr.getId(), now);
        if (!authority.organizationWide()) {
            throw new RuleActivationException(
                "Activating profile approval rules requires organization-wide HR authority");
        }
        ProfileRuleSet ruleSet = ruleSets.findByPublicId(ruleSetPublicId)
            .orElseThrow(ResourceNotFoundException::new);
        if (!ruleSet.isPublished()) {
            throw new RuleActivationException("Only a published rule set can be activated");
        }
        ruleSets.lockById(ruleSet.getId()).orElseThrow(ResourceNotFoundException::new);
        Instant activatedAt = Instant.now(clock);
        activations.save(new ProfileRuleActivation(ruleSet.getId(), activatedAt, caller.getName()));
        Optional<ProfileRuleCurrentActivation> pointer = currentActivation.lockSingleton();
        if (pointer.isPresent()) {
            pointer.get().moveTo(ruleSet.getId(), activatedAt, caller.getName());
            currentActivation.save(pointer.get());
        } else {
            currentActivation.save(new ProfileRuleCurrentActivation(ruleSet.getId(), activatedAt, caller.getName()));
        }
        return new ActivationResult(ruleSetPublicId, activatedAt);
    }

    @Transactional
    public RoutePreview previewRoute(String orgUnit, String jobLevel, UUID employeePublicId,
                                     Authentication caller) {
        admission.acquire(AdmissionGuard.Mode.SHARED);
        Instant now = Instant.now(clock);
        UserAccount hr = access.requireHrOperation(caller, now);
        if (!access.coversUnit(hr.getId(), orgUnit, now)) {
            throw new AccessDeniedException("HR scope does not cover the previewed organization unit");
        }
        RouteResolution resolution = resolveForSubmission(orgUnit, jobLevel, employeePublicId, now);
        Instant fresh = Instant.now(clock);
        UserAccount currentHr = access.requireHrOperation(caller, fresh);
        if (!access.coversUnit(currentHr.getId(), orgUnit, fresh)) {
            throw new AccessDeniedException("HR scope does not cover the previewed organization unit");
        }
        return new RoutePreview(resolution.matched(), resolution.ruleSetPublicId(), resolution.ruleId(),
            resolution.reviewerAccountPublicId(), resolution.blockedReason(), orgUnit, jobLevel);
    }

    @Transactional
    public void verifyPublishReplayScope(UUID ruleSetPublicId, Authentication caller) {
        Objects.requireNonNull(ruleSetPublicId, "ruleSetPublicId must not be null");
        Instant now = Instant.now(clock);
        UserAccount hr = access.requireHrOperation(caller, now);
        ProfileRuleSet ruleSet = ruleSets.findByPublicId(ruleSetPublicId)
            .orElseThrow(ResourceNotFoundException::new);
        ruleSets.lockById(ruleSet.getId()).orElseThrow(ResourceNotFoundException::new);
        AccountAccessService.HrAuthority authority =
            access.hrAuthority(hr.getId(), Instant.now(clock));
        for (ProfileRule rule : rules.findByRuleSetIdOrderByPriorityDesc(ruleSet.getId())) {
            if (rule.getOrgUnit() != null && !authority.organizationWide()
                && !authority.units().contains(rule.getOrgUnit())) {
                throw new AccessDeniedException("Recorded configuration scope is no longer authorized");
            }
            if (rule.getOrgUnit() == null && !authority.organizationWide()) {
                throw new AccessDeniedException("Recorded configuration scope is no longer authorized");
            }
        }
    }

    @Transactional
    public void verifyActivateReplayScope(UUID ruleSetPublicId, Authentication caller) {
        Objects.requireNonNull(ruleSetPublicId, "ruleSetPublicId must not be null");
        Instant now = Instant.now(clock);
        UserAccount hr = access.requireHrOperation(caller, now);
        ProfileRuleSet ruleSet = ruleSets.findByPublicId(ruleSetPublicId)
            .orElseThrow(ResourceNotFoundException::new);
        ruleSets.lockById(ruleSet.getId()).orElseThrow(ResourceNotFoundException::new);
        AccountAccessService.HrAuthority authority =
            access.hrAuthority(hr.getId(), Instant.now(clock));
        if (!authority.organizationWide()) {
            throw new AccessDeniedException("Recorded configuration scope is no longer authorized");
        }
    }

    @Transactional(readOnly = true)
    public RouteResolution resolveForSubmission(String orgUnit, String jobLevel,
                                                UUID requesterEmployeePublicId, Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        Optional<ProfileRuleCurrentActivation> pointer = currentActivation.findById(
            ProfileRuleCurrentActivation.SINGLETON_ID);
        if (pointer.isEmpty()) {
            return blocked(RouteBlockReasons.NO_ACTIVE_CONFIGURATION);
        }
        ProfileRuleSet active = ruleSets.findById(pointer.get().getRuleSetId())
            .orElseThrow(() -> new IllegalStateException("Active profile rule set is missing"));
        List<ProfileRule> candidates = rules.findByRuleSetIdOrderByPriorityDesc(active.getId());
        ProfileRouteEvaluator.Outcome outcome = ProfileRouteEvaluator.evaluate(candidates, orgUnit, jobLevel);
        if (outcome instanceof ProfileRouteEvaluator.Outcome.NoMatch) {
            return blocked(active.getPublicId(), RouteBlockReasons.NO_MATCH);
        }
        if (outcome instanceof ProfileRouteEvaluator.Outcome.Ambiguous ambiguous) {
            return blocked(active.getPublicId(), RouteBlockReasons.AMBIGUOUS_RULE);
        }
        ProfileRule rule = ((ProfileRouteEvaluator.Outcome.Matched) outcome).rule();
        return resolveReviewer(active.getPublicId(), rule, orgUnit, requesterEmployeePublicId, now);
    }

    private RouteResolution resolveReviewer(UUID ruleSetPublicId, ProfileRule rule, String orgUnit,
                                            UUID requesterEmployeePublicId, Instant now) {
        Optional<UserAccount> reviewer = access.findAccountByPublicId(rule.getReviewerAccountPublicId());
        if (reviewer.isEmpty() || !reviewer.get().isActiveAt(now)) {
            return blocked(ruleSetPublicId, RouteBlockReasons.NO_DESIGNATED_REVIEWER);
        }
        UserAccount account = reviewer.get();
        if (!access.hasRole(account.getId(), HrisRole.HR_ADMIN, now)
            || !access.coversUnit(account.getId(), orgUnit, now)) {
            return blocked(ruleSetPublicId, RouteBlockReasons.REVIEWER_INELIGIBLE);
        }
        if (requesterEmployeePublicId != null && requesterEmployeePublicId.equals(
            access.ownEmployeePublicId(account).orElse(null))) {
            return blocked(ruleSetPublicId, RouteBlockReasons.SELF_REVIEW_PROHIBITED);
        }
        return new RouteResolution(true, ruleSetPublicId, rule.getRuleId(),
            account.getPublicId(), null);
    }

    private void requirePublisherScope(Long hrAccountId, List<RulePayload> inputs, Instant now) {
        AccountAccessService.HrAuthority authority = access.hrAuthority(hrAccountId, now);
        Set<String> seen = new HashSet<>();
        if (inputs.isEmpty()
            || inputs.size() > ProfileRuleSetValidator.MAX_RULES) {
            throw new RulePublishException("Profile rule snapshot is invalid");
        }
        for (RulePayload input : inputs) {
            if (input == null) {
                throw new RulePublishException("Profile rule must not be null");
            }
            if (input.ruleId() == null || input.ruleId().isBlank()) {
                throw new RulePublishException("Profile rule identifier is required");
            }
            if (!seen.add(input.ruleId())) {
                throw new RulePublishException("Duplicate profile rule identifier: " + input.ruleId());
            }
            if (input.orgUnit() != null) {
                orgUnits.requireAllowed(input.orgUnit());
                if (!authority.organizationWide() && !authority.units().contains(input.orgUnit())) {
                    throw new RulePublishException(
                        "HR scope does not cover configured unit " + input.orgUnit());
                }
            } else if (!authority.organizationWide()) {
                throw new RulePublishException("Catch-all profile rules require organization-wide HR authority");
            }
            requireDesignatedReviewer(input, now);
        }
    }

    private void requireDesignatedReviewer(RulePayload input, Instant now) {
        if (input.reviewerAccountPublicId() == null) {
            throw new RulePublishException("Rule " + input.ruleId() + " must designate a reviewer");
        }
        UserAccount reviewer = access.findAccountByPublicId(input.reviewerAccountPublicId())
            .orElseThrow(() -> new RulePublishException(
                "Rule " + input.ruleId() + " designates an unknown reviewer"));
        if (!reviewer.isActiveAt(now)) {
            throw new RulePublishException("Rule " + input.ruleId() + " designates an inactive reviewer");
        }
        if (!access.hasRole(reviewer.getId(), HrisRole.HR_ADMIN, now)
            || !access.coversUnit(reviewer.getId(), input.orgUnit(), now)) {
            throw new RulePublishException("Rule " + input.ruleId() + " designates an ineligible reviewer");
        }
    }

    private List<ProfileRule> buildRules(Long ruleSetId, List<RulePayload> inputs, Instant now) {
        List<ProfileRule> published = new ArrayList<>(inputs.size());
        for (RulePayload input : inputs) {
            try {
                published.add(new ProfileRule(ruleSetId, input.ruleId(), input.priority(),
                    input.orgUnit(), input.jobLevel(), input.reviewerAccountPublicId(), now));
            } catch (IllegalArgumentException ex) {
                throw new RulePublishException("Profile rule snapshot is invalid");
            }
        }
        return published;
    }

    private static RouteResolution blocked(String blockedReason) {
        return new RouteResolution(false, null, null, null, blockedReason);
    }

    private static RouteResolution blocked(UUID ruleSetPublicId, String blockedReason) {
        return new RouteResolution(false, ruleSetPublicId, null, null, blockedReason);
    }
}
