package id.mydev.peoplecore.approval.domain.validation;

import id.mydev.peoplecore.approval.domain.model.ProfileRule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileRouteEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");

    private static ProfileRule rule(String ruleId, int priority, String orgUnit, String jobLevel) {
        return new ProfileRule(1L, ruleId, priority, orgUnit, jobLevel, UUID.randomUUID(), NOW);
    }

    @Test
    void uniqueHighestPriorityRuleWins() {
        List<ProfileRule> rules = List.of(
            rule("low", 5, "ENG", null),
            rule("high", 10, "ENG", "L3"),
            rule("other-unit", 20, "FIN", null));
        ProfileRouteEvaluator.Outcome outcome = ProfileRouteEvaluator.evaluate(rules, "ENG", "L3");
        assertTrue(outcome instanceof ProfileRouteEvaluator.Outcome.Matched);
        assertEquals("high", ((ProfileRouteEvaluator.Outcome.Matched) outcome).rule().getRuleId());
    }

    @Test
    void wildcardPredicatesMatchAnyContext() {
        List<ProfileRule> rules = List.of(rule("catch-all", 1, null, null));
        ProfileRouteEvaluator.Outcome outcome = ProfileRouteEvaluator.evaluate(rules, "OPS", "L9");
        assertTrue(outcome instanceof ProfileRouteEvaluator.Outcome.Matched);
    }

    @Test
    void tiedHighestPriorityIsAmbiguous() {
        List<ProfileRule> rules = List.of(
            rule("first", 10, "ENG", null),
            rule("second", 10, "ENG", "L3"),
            rule("lower", 5, "ENG", null));
        ProfileRouteEvaluator.Outcome outcome = ProfileRouteEvaluator.evaluate(rules, "ENG", "L3");
        assertTrue(outcome instanceof ProfileRouteEvaluator.Outcome.Ambiguous);
        assertEquals(2, ((ProfileRouteEvaluator.Outcome.Ambiguous) outcome).rules().size());
    }

    @Test
    void noMatchingRuleIsBlocked() {
        assertTrue(ProfileRouteEvaluator.evaluate(List.<ProfileRule>of(), "ENG", "L3")
            instanceof ProfileRouteEvaluator.Outcome.NoMatch);
        assertTrue(ProfileRouteEvaluator.evaluate(List.of(rule("fin-only", 1, "FIN", null)), "ENG", "L3")
            instanceof ProfileRouteEvaluator.Outcome.NoMatch);
    }

    @Test
    void nullInputsAreRejected() {
        assertThrows(NullPointerException.class,
            () -> ProfileRouteEvaluator.evaluate(null, "ENG", "L3"));
        List<ProfileRule> withNull = new ArrayList<>();
        withNull.add(null);
        assertThrows(NullPointerException.class,
            () -> ProfileRouteEvaluator.evaluate(withNull, "ENG", "L3"));
    }
}
