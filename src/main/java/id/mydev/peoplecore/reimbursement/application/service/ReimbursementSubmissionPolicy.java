package id.mydev.peoplecore.reimbursement.application.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.util.StringUtils;

public final class ReimbursementSubmissionPolicy {
    private static final int MAX_EVIDENCE = 5;
    private static final int MAX_AGE_DAYS = 90;

    public void validate(Submission submission, LocalDate today) {
        Objects.requireNonNull(submission, "submission");
        Objects.requireNonNull(today, "today");
        if (submission.transactionDate() == null || submission.transactionDate().isAfter(today)
            || submission.transactionDate().isBefore(today.minusDays(MAX_AGE_DAYS))) {
            throw new IllegalArgumentException("Transaction date is outside the submission window");
        }
        if (submission.employmentStart() == null
            || submission.transactionDate().isBefore(submission.employmentStart())
            || submission.employmentEnd() != null && submission.transactionDate().isAfter(submission.employmentEnd())) {
            throw new IllegalArgumentException("Transaction date is outside known employment");
        }
        if (submission.amount() <= 0 || submission.limit() <= 0 || submission.amount() > submission.limit()) {
            throw new IllegalArgumentException("Claim amount is outside the applicable limit");
        }
        if (!StringUtils.hasText(submission.description())) {
            throw new IllegalArgumentException("Claim description is required");
        }
        List<UUID> evidence = submission.evidence();
        if (evidence == null || evidence.isEmpty() || evidence.size() > MAX_EVIDENCE
            || evidence.stream().anyMatch(Objects::isNull) || evidence.stream().distinct().count() != evidence.size()) {
            throw new IllegalArgumentException("One to five distinct evidence documents are required");
        }
    }

    public record Submission(LocalDate transactionDate, LocalDate employmentStart, LocalDate employmentEnd,
                             long amount, long limit, String description, List<UUID> evidence) { }
}
