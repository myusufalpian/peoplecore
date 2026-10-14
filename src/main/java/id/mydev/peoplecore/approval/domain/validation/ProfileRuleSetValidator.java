package id.mydev.peoplecore.approval.domain.validation;

import id.mydev.peoplecore.approval.domain.model.ProfileRule;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class ProfileRuleSetValidator {

    public static final int MIN_RULES = 1;
    public static final int MAX_RULES = 100;

    private ProfileRuleSetValidator() {
    }

    public static void validateSnapshot(List<ProfileRule> rules) {
        Objects.requireNonNull(rules, "rules must not be null");
        if (rules.isEmpty() || rules.size() > MAX_RULES) {
            throw new IllegalArgumentException(
                "Rule set must contain between 1 and 100 rules");
        }
        Set<String> seen = new HashSet<>();
        for (ProfileRule rule : rules) {
            Objects.requireNonNull(rule, "rule must not be null");
            if (!seen.add(rule.getRuleId())) {
                throw new IllegalArgumentException(
                    "Duplicate rule identifier: " + rule.getRuleId());
            }
            if (rule.getReviewerAccountPublicId() == null) {
                throw new IllegalArgumentException(
                    "Rule " + rule.getRuleId() + " must designate a reviewer");
            }
        }
    }

    public static void requireImmediateActivation(java.time.Instant requestedAt, java.time.Instant now) {
        Objects.requireNonNull(requestedAt, "requestedAt must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (!requestedAt.equals(now)) {
            throw new IllegalArgumentException(
                "Activation is immediate only; scheduled or backdated activation is not supported");
        }
    }
}
