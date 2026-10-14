package id.mydev.peoplecore.approval.domain.validation;

import id.mydev.peoplecore.approval.domain.model.ProfileRule;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ProfileRouteEvaluator {

    public sealed interface Outcome permits Outcome.Matched, Outcome.NoMatch, Outcome.Ambiguous {
        record Matched(ProfileRule rule) implements Outcome { }

        record NoMatch() implements Outcome { }

        record Ambiguous(List<ProfileRule> rules) implements Outcome { }
    }

    private ProfileRouteEvaluator() {
    }

    public static Outcome evaluate(List<ProfileRule> rules, String unit, String jobLevel) {
        Objects.requireNonNull(rules, "rules must not be null");
        List<ProfileRule> matching = new ArrayList<>();
        int best = Integer.MIN_VALUE;
        for (ProfileRule rule : rules) {
            Objects.requireNonNull(rule, "rule must not be null");
            if (!rule.matches(unit, jobLevel)) {
                continue;
            }
            if (rule.getPriority() > best) {
                best = rule.getPriority();
                matching.clear();
                matching.add(rule);
            } else if (rule.getPriority() == best) {
                matching.add(rule);
            }
        }
        if (matching.isEmpty()) {
            return new Outcome.NoMatch();
        }
        if (matching.size() > 1) {
            return new Outcome.Ambiguous(List.copyOf(matching));
        }
        return new Outcome.Matched(matching.get(0));
    }
}
