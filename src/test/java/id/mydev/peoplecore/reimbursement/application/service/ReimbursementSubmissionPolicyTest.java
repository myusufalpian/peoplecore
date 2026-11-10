package id.mydev.peoplecore.reimbursement.application.service;

import id.mydev.peoplecore.reimbursement.application.service.ReimbursementSubmissionPolicy.Submission;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReimbursementSubmissionPolicyTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 1);
    private final ReimbursementSubmissionPolicy policy = new ReimbursementSubmissionPolicy();

    @Test
    void acceptsNinetyDaysAndExactLimit() {
        assertDoesNotThrow(() -> policy.validate(submission(TODAY.minusDays(90), 100, 100, List.of(UUID.randomUUID())), TODAY));
    }

    @Test
    void rejectsDatesOutsideTheWindowAndEmployment() {
        UUID evidence = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> policy.validate(submission(TODAY.minusDays(91), 1, 100, List.of(evidence)), TODAY));
        assertThrows(IllegalArgumentException.class, () -> policy.validate(submission(TODAY.plusDays(1), 1, 100, List.of(evidence)), TODAY));
        assertThrows(IllegalArgumentException.class, () -> policy.validate(new Submission(TODAY, null, null, 1, 100, "Travel", List.of(evidence)), TODAY));
        assertThrows(IllegalArgumentException.class, () -> policy.validate(new Submission(TODAY, TODAY.minusYears(1), TODAY.minusDays(1), 1, 100, "Travel", List.of(evidence)), TODAY));
    }

    @Test
    void rejectsInvalidAmountsAndEvidence() {
        UUID evidence = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> policy.validate(submission(TODAY, 0, 100, List.of(evidence)), TODAY));
        assertThrows(IllegalArgumentException.class, () -> policy.validate(submission(TODAY, 101, 100, List.of(evidence)), TODAY));
        assertThrows(IllegalArgumentException.class, () -> policy.validate(submission(TODAY, 1, 100, List.of()), TODAY));
        assertThrows(IllegalArgumentException.class, () -> policy.validate(submission(TODAY, 1, 100, List.of(evidence, evidence)), TODAY));
        assertThrows(IllegalArgumentException.class, () -> policy.validate(submission(TODAY, 1, 100,
            List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())), TODAY));
    }

    @Test
    void acceptsEmploymentBoundariesAndFiveDistinctDocuments() {
        List<UUID> evidence = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        assertDoesNotThrow(() -> policy.validate(new Submission(TODAY, TODAY, TODAY, 1, 1, "Travel", evidence), TODAY));
        assertDoesNotThrow(() -> policy.validate(new Submission(TODAY.minusDays(90), TODAY.minusDays(90), TODAY,
            1, 100, "Travel", evidence), TODAY));
    }

    @Test
    void rejectsMissingPolicyDescriptionAndNullEvidenceMember() {
        UUID evidence = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> policy.validate(submission(TODAY, 1, 0, List.of(evidence)), TODAY));
        assertThrows(IllegalArgumentException.class, () -> policy.validate(new Submission(TODAY, TODAY, null, 1, 100,
            "  ", List.of(evidence)), TODAY));
        assertThrows(IllegalArgumentException.class, () -> policy.validate(new Submission(TODAY, TODAY, null, 1, 100,
            "Travel", null), TODAY));
        assertThrows(IllegalArgumentException.class, () -> policy.validate(new Submission(TODAY, TODAY, null, 1, 100,
            "Travel", Arrays.asList(evidence, null)), TODAY));
        assertThrows(IllegalArgumentException.class, () -> policy.validate(new Submission(TODAY, TODAY.plusDays(1), null,
            1, 100, "Travel", List.of(evidence)), TODAY));
    }

    private static Submission submission(LocalDate date, long amount, long limit, List<UUID> evidence) {
        return new Submission(date, TODAY.minusYears(1), null, amount, limit, "Travel", evidence);
    }
}
