package id.mydev.peoplecore.approval.domain.validation;

import id.mydev.peoplecore.approval.domain.model.ProfileRule;
import id.mydev.peoplecore.approval.domain.model.ProfileRuleActivation;
import id.mydev.peoplecore.approval.domain.model.ProfileRuleCurrentActivation;
import id.mydev.peoplecore.approval.domain.model.ProfileRuleSet;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileApprovalRulesTest {

    private static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");

    private static ProfileRule rule(String ruleId) {
        return new ProfileRule(1L, ruleId, 10, "ENG", "L3", UUID.randomUUID(), NOW);
    }

    @Test
    void draftSetPublishesOnceWithImmediateInstant() {
        ProfileRuleSet set = new ProfileRuleSet(UUID.randomUUID(), NOW);
        assertEquals(ProfileRuleSet.REQUEST_PROFILE_CHANGE, set.getRequestType());
        assertEquals(ProfileRuleSet.STATUS_DRAFT, set.getStatus());
        assertFalse(set.isPublished());
        assertNull(set.getPublishedAt());

        set.publish(NOW);
        assertTrue(set.isPublished());
        assertEquals(NOW, set.getPublishedAt());

        assertThrows(IllegalStateException.class, () -> set.publish(NOW.plusSeconds(1)));
        assertThrows(NullPointerException.class,
            () -> new ProfileRuleSet(null, NOW));
        assertThrows(NullPointerException.class,
            () -> new ProfileRuleSet(UUID.randomUUID(), null));
        ProfileRuleSet draft = new ProfileRuleSet(UUID.randomUUID(), NOW);
        assertThrows(NullPointerException.class, () -> draft.publish(null));
    }

    @Test
    void rulePredicatesMatchExactlyAndRejectBlank() {
        ProfileRule any = new ProfileRule(1L, "any", 1, null, null, UUID.randomUUID(), NOW);
        assertTrue(any.matches("ENG", "L3"));
        assertTrue(any.matches(null, null));

        ProfileRule scoped = rule("scoped");
        assertTrue(scoped.matches("ENG", "L3"));
        assertFalse(scoped.matches("FIN", "L3"));
        assertFalse(scoped.matches("ENG", "L4"));
        assertFalse(scoped.matches(null, "L3"));

        assertThrows(IllegalArgumentException.class,
            () -> new ProfileRule(1L, "  ", 1, null, null, UUID.randomUUID(), NOW));
        assertThrows(IllegalArgumentException.class,
            () -> new ProfileRule(1L, "r", 1, "  ", null, UUID.randomUUID(), NOW));
        assertThrows(IllegalArgumentException.class,
            () -> new ProfileRule(1L, "r", 1, null, "", UUID.randomUUID(), NOW));
        assertThrows(IllegalArgumentException.class,
            () -> new ProfileRule(1L, "x".repeat(65), 1, null, null, UUID.randomUUID(), NOW));
        assertThrows(IllegalArgumentException.class,
            () -> new ProfileRule(1L, "r", 1, "x".repeat(129), null, UUID.randomUUID(), NOW));
        assertThrows(IllegalArgumentException.class,
            () -> new ProfileRule(1L, "r", 1, null, "x".repeat(65), UUID.randomUUID(), NOW));
        assertThrows(NullPointerException.class,
            () -> new ProfileRule(null, "r", 1, null, null, UUID.randomUUID(), NOW));
        assertThrows(NullPointerException.class,
            () -> new ProfileRule(1L, "r", 1, null, null, null, NOW));
        assertThrows(NullPointerException.class,
            () -> new ProfileRule(1L, "r", 1, null, null, UUID.randomUUID(), null));
    }

    @Test
    void activationHistoryAndPointerRequireExplicitActor() {
        ProfileRuleActivation history = new ProfileRuleActivation(7L, NOW, "hr-1");
        assertEquals(7L, history.getRuleSetId());
        assertEquals(NOW, history.getActivatedAt());
        assertEquals("hr-1", history.getActivatedBy());
        assertNull(history.getId());
        assertThrows(IllegalArgumentException.class,
            () -> new ProfileRuleActivation(7L, NOW, "  "));
        assertThrows(IllegalArgumentException.class,
            () -> new ProfileRuleActivation(7L, NOW, null));
        assertThrows(NullPointerException.class,
            () -> new ProfileRuleActivation(7L, null, "hr-1"));
        assertThrows(NullPointerException.class,
            () -> new ProfileRuleActivation(null, NOW, "hr-1"));

        ProfileRuleCurrentActivation pointer = new ProfileRuleCurrentActivation(7L, NOW, "hr-1");
        assertEquals(ProfileRuleCurrentActivation.SINGLETON_ID, pointer.getId());
        pointer.moveTo(9L, NOW.plusSeconds(5), "hr-2");
        assertEquals(9L, pointer.getRuleSetId());
        assertEquals("hr-2", pointer.getActivatedBy());
        assertEquals(NOW.plusSeconds(5), pointer.getActivatedAt());
        assertThrows(IllegalArgumentException.class,
            () -> pointer.moveTo(9L, NOW, " "));
        assertThrows(IllegalArgumentException.class,
            () -> new ProfileRuleCurrentActivation(7L, NOW, null));
        assertThrows(NullPointerException.class,
            () -> new ProfileRuleCurrentActivation(null, NOW, "hr-1"));
        assertThrows(NullPointerException.class,
            () -> new ProfileRuleCurrentActivation(7L, null, "hr-1"));
        assertThrows(NullPointerException.class,
            () -> pointer.moveTo(null, NOW, "hr-1"));
        assertThrows(NullPointerException.class,
            () -> pointer.moveTo(9L, null, "hr-1"));
        assertThrows(IllegalArgumentException.class,
            () -> pointer.moveTo(9L, NOW, null));
    }

    @Test
    void entitiesExposePersistedStateThroughGetters() {
        UUID setPublicId = UUID.randomUUID();
        UUID reviewer = UUID.randomUUID();
        ProfileRuleSet set = new ProfileRuleSet(setPublicId, NOW);
        assertEquals(setPublicId, set.getPublicId());
        assertEquals(NOW, set.getCreatedAt());
        assertNull(set.getId());

        ProfileRule rule = new ProfileRule(3L, "r1", 7, "ENG", "L3", reviewer, NOW);
        assertNull(rule.getId());
        assertEquals(3L, rule.getRuleSetId());
        assertEquals("r1", rule.getRuleId());
        assertEquals(7, rule.getPriority());
        assertEquals("ENG", rule.getOrgUnit());
        assertEquals("L3", rule.getJobLevel());
        assertEquals(reviewer, rule.getReviewerAccountPublicId());
        assertEquals(NOW, rule.getCreatedAt());

        ProfileRuleActivation history = new ProfileRuleActivation(7L, NOW, "hr-1");
        assertNull(history.getId());

        ProfileRuleCurrentActivation pointer = new ProfileRuleCurrentActivation(7L, NOW, "hr-1");
        assertEquals(NOW, pointer.getActivatedAt());
    }

    @Test
    void snapshotRequiresOneToOneHundredUniqueRules() {
        ProfileRuleSetValidator.validateSnapshot(List.of(rule("r1")));

        assertThrows(IllegalArgumentException.class,
            () -> ProfileRuleSetValidator.validateSnapshot(List.of()));
        List<ProfileRule> tooMany = new ArrayList<>();
        for (int i = 0; i <= ProfileRuleSetValidator.MAX_RULES; i++) {
            tooMany.add(rule("r" + i));
        }
        assertEquals(101, tooMany.size());
        assertThrows(IllegalArgumentException.class,
            () -> ProfileRuleSetValidator.validateSnapshot(tooMany));

        List<ProfileRule> duplicate = new ArrayList<>();
        duplicate.add(rule("same"));
        duplicate.add(rule("same"));
        assertThrows(IllegalArgumentException.class,
            () -> ProfileRuleSetValidator.validateSnapshot(duplicate));

        List<ProfileRule> withNull = new ArrayList<>();
        withNull.add(null);
        assertThrows(NullPointerException.class,
            () -> ProfileRuleSetValidator.validateSnapshot(withNull));
        assertThrows(NullPointerException.class,
            () -> ProfileRuleSetValidator.validateSnapshot(null));
    }

    @Test
    void activationIsImmediateOnly() {
        ProfileRuleSetValidator.requireImmediateActivation(NOW, NOW);
        assertThrows(IllegalArgumentException.class, () -> ProfileRuleSetValidator
            .requireImmediateActivation(NOW.plusSeconds(60), NOW));
        assertThrows(IllegalArgumentException.class, () -> ProfileRuleSetValidator
            .requireImmediateActivation(NOW.minusSeconds(60), NOW));
        assertThrows(NullPointerException.class, () -> ProfileRuleSetValidator
            .requireImmediateActivation(null, NOW));
        assertThrows(NullPointerException.class, () -> ProfileRuleSetValidator
            .requireImmediateActivation(NOW, null));
    }
}
