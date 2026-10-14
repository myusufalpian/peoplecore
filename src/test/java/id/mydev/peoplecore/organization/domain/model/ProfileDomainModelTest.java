package id.mydev.peoplecore.organization.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileDomainModelTest {

    private static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");

    @Test
    void contactProfileAppliesGroupsAndVersions() {
        ContactProfile profile = new ContactProfile(7L, NOW);
        assertNull(profile.getId());
        assertEquals(7L, profile.getEmployeeId());
        assertEquals(NOW, profile.getCreatedAt());
        assertEquals(NOW, profile.getUpdatedAt());

        profile.applyValue(ProfileRevisionChange.FIELD_PHONE, "+62 812 345 678", NOW);
        profile.applyValue(ProfileRevisionChange.FIELD_EMAIL, "member@example.id", NOW);
        profile.applyValue(ProfileRevisionChange.FIELD_ADDRESS, "Jl. Merdeka\nJakarta", NOW);
        profile.applyValue(ProfileRevisionChange.FIELD_EMERGENCY_NAME, "Siti", NOW);
        profile.applyValue(ProfileRevisionChange.FIELD_EMERGENCY_RELATIONSHIP, "Saudara", NOW);
        profile.applyValue(ProfileRevisionChange.FIELD_EMERGENCY_PHONE, "+62 812 999 999", NOW);

        assertEquals("+62 812 345 678", profile.valueOf(ProfileRevisionChange.FIELD_PHONE));
        assertEquals("member@example.id", profile.valueOf(ProfileRevisionChange.FIELD_EMAIL));
        assertEquals("Jl. Merdeka\nJakarta",
            profile.valueOf(ProfileRevisionChange.FIELD_ADDRESS));
        assertEquals("Siti", profile.valueOf(ProfileRevisionChange.FIELD_EMERGENCY_NAME));
        assertEquals("Saudara",
            profile.valueOf(ProfileRevisionChange.FIELD_EMERGENCY_RELATIONSHIP));
        assertEquals("+62 812 999 999",
            profile.valueOf(ProfileRevisionChange.FIELD_EMERGENCY_PHONE));
        assertEquals("member@example.id", profile.getPersonalEmail());
        assertEquals("Jl. Merdeka\nJakarta", profile.getResidentialAddress());
        assertEquals("Siti", profile.getEmergencyContactName());
        assertEquals("Saudara", profile.getEmergencyContactRelationship());
        assertEquals("+62 812 999 999", profile.getEmergencyContactPhone());
        assertEquals(1, profile.versionOf(ContactProfile.GROUP_PHONE));
        assertEquals(1, profile.versionOf(ContactProfile.GROUP_EMAIL));
        assertEquals(1, profile.versionOf(ContactProfile.GROUP_ADDRESS));
        assertEquals(3, profile.versionOf(ContactProfile.GROUP_EMERGENCY));

        assertThrows(IllegalArgumentException.class,
            () -> profile.applyValue("salary", "100", NOW));
        assertThrows(IllegalArgumentException.class, () -> profile.valueOf("salary"));
        assertThrows(IllegalArgumentException.class, () -> profile.versionOf("SALARY"));
        assertThrows(NullPointerException.class, () -> profile.applyValue(null, "x", NOW));
    }

    @Test
    void unchangedValuesDoNotBumpConflictCounters() {
        ContactProfile profile = new ContactProfile(7L, NOW);
        profile.applyValue(ProfileRevisionChange.FIELD_PHONE, "+62 812 345 678", NOW);
        assertEquals(1, profile.versionOf(ContactProfile.GROUP_PHONE));

        profile.applyValue(ProfileRevisionChange.FIELD_PHONE, "+62 812 345 678", NOW.plusSeconds(1));
        assertEquals(1, profile.versionOf(ContactProfile.GROUP_PHONE));

        Map<String, String> mixed = new LinkedHashMap<>();
        mixed.put(ProfileRevisionChange.FIELD_PHONE, "+62 812 345 678");
        mixed.put(ProfileRevisionChange.FIELD_EMAIL, "member@example.id");
        profile.applyChanges(mixed, NOW.plusSeconds(2));
        assertEquals(1, profile.versionOf(ContactProfile.GROUP_PHONE));
        assertEquals(1, profile.versionOf(ContactProfile.GROUP_EMAIL));

        Map<String, String> emergency = new LinkedHashMap<>();
        emergency.put(ProfileRevisionChange.FIELD_EMERGENCY_NAME, "Siti");
        emergency.put(ProfileRevisionChange.FIELD_EMERGENCY_RELATIONSHIP, "Saudara");
        emergency.put(ProfileRevisionChange.FIELD_EMERGENCY_PHONE, "+62 812 999 999");
        profile.applyChanges(emergency, NOW.plusSeconds(3));
        assertEquals(1, profile.versionOf(ContactProfile.GROUP_EMERGENCY));
        profile.applyChanges(emergency, NOW.plusSeconds(4));
        assertEquals(1, profile.versionOf(ContactProfile.GROUP_EMERGENCY));

        assertThrows(NullPointerException.class, () -> profile.applyChanges(null, NOW));
        assertThrows(NullPointerException.class,
            () -> profile.applyChanges(Map.of(ProfileRevisionChange.FIELD_PHONE, "+62 1"), null));
    }

    @Test
    void changeFieldGroupsResolve() {
        assertEquals(ContactProfile.GROUP_PHONE,
            ProfileRevisionChange.groupOf(ProfileRevisionChange.FIELD_PHONE));
        assertEquals(ContactProfile.GROUP_EMAIL,
            ProfileRevisionChange.groupOf(ProfileRevisionChange.FIELD_EMAIL));
        assertEquals(ContactProfile.GROUP_ADDRESS,
            ProfileRevisionChange.groupOf(ProfileRevisionChange.FIELD_ADDRESS));
        assertEquals(ContactProfile.GROUP_EMERGENCY,
            ProfileRevisionChange.groupOf(ProfileRevisionChange.FIELD_EMERGENCY_NAME));
        assertTrue(ProfileRevisionChange.isKnownField(ProfileRevisionChange.FIELD_EMERGENCY_PHONE));
        assertFalse(ProfileRevisionChange.isKnownField("salary"));
        assertThrows(IllegalArgumentException.class,
            () -> ProfileRevisionChange.groupOf("salary"));
        assertThrows(IllegalArgumentException.class,
            () -> ProfileRevisionChange.requireKnownField("salary"));
        ProfileRevisionChange change = new ProfileRevisionChange(1L,
            ProfileRevisionChange.FIELD_PHONE, null, "+62 1", 0);
        assertNull(change.getId());
        assertEquals(1L, change.getRevisionId());
        assertEquals("+62 1", change.getProposedValue());
        assertNull(change.getBaselineValue());
    }

    @Test
    void proposalTracksOpenLifecycle() {
        ProfileProposal proposal = new ProfileProposal(UUID.randomUUID(), 7L, NOW);
        assertTrue(proposal.isOpen());
        assertEquals("DRAFT", proposal.getState());
        proposal.transitionTo(ProfileProposal.STATE_PENDING, NOW);
        assertTrue(proposal.isOpen());
        proposal.markBlocked("NO_MATCH", NOW);
        assertEquals("NO_MATCH", proposal.getBlockedReason());
        proposal.clearBlocked(NOW);
        assertNull(proposal.getBlockedReason());
        proposal.pointTo(11L, 22L, NOW);
        assertEquals(11L, proposal.getCurrentRevisionId());
        assertEquals(22L, proposal.getCurrentAssignmentId());
        proposal.transitionTo(ProfileProposal.STATE_RETURNED, NOW);
        assertTrue(proposal.isOpen());
        assertEquals(NOW, proposal.getUpdatedAt());
        proposal.transitionTo(ProfileProposal.STATE_APPROVED, NOW);
        assertFalse(proposal.isOpen());
    }

    @Test
    void assignmentTransitionsOnce() {
        ProfileAssignment assignment = new ProfileAssignment(UUID.randomUUID(), 1L, 2L,
            UUID.randomUUID(), NOW);
        assertTrue(assignment.isActive());
        assertEquals("ACTIVE", assignment.getStatus());
        assignment.supersede("Covering leave", "hr-1", NOW);
        assertEquals("SUPERSEDED", assignment.getStatus());
        assertEquals("Covering leave", assignment.getReason());
        assertEquals("hr-1", assignment.getActor());
        assertEquals(NOW, assignment.getClosedAt());
        assertThrows(IllegalStateException.class, () -> assignment.close(NOW));

        ProfileAssignment second = new ProfileAssignment(UUID.randomUUID(), 1L, 2L,
            UUID.randomUUID(), NOW);
        second.close(NOW);
        assertEquals("CLOSED", second.getStatus());
        assertThrows(IllegalStateException.class,
            () -> second.supersede("reason", "hr-1", NOW));
    }
    @Test
    void decisionAndEvaluationRejectUnknownOutcomes() {

        ProfileDecision decision = new ProfileDecision(1L, 2L, 3L, ProfileDecision.ACTION_APPROVE,
            UUID.randomUUID(), null, NOW);
        assertEquals("APPROVE", decision.getAction());
        assertEquals(3L, decision.getAssignmentId());
        assertThrows(IllegalArgumentException.class, () -> new ProfileDecision(1L, 2L, null,
            "ESCALATE", UUID.randomUUID(), null, NOW));

        ProfileRouteEvaluation evaluation = new ProfileRouteEvaluation(1L, 2L, NOW, null, "ENG",
            "L3", ProfileRouteEvaluation.RESULT_BLOCKED, null, null, "NO_MATCH");
        assertEquals("BLOCKED", evaluation.getResult());
        assertEquals("NO_MATCH", evaluation.getBlockedReason());
        assertThrows(IllegalArgumentException.class, () -> new ProfileRouteEvaluation(1L, null,
            NOW, null, null, null, "UNKNOWN", null, null, null));
    }

    @Test
    void sealedRevisionStartsOpenAndSealsOnce() {
        ProfileRevision revision = new ProfileRevision(UUID.randomUUID(), 7L, 1, null, null, null,
            null, null, null, NOW);
        assertFalse(revision.isSealed());
        revision.seal();
        assertTrue(revision.isSealed());
        revision.seal();
        assertTrue(revision.isSealed());
    }
}
