package id.mydev.peoplecore.reimbursement.domain.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReimbursementSubmissionRepository {
    void ensureAccount(long employeeId);

    void lockAccount(long employeeId);

    Optional<ClaimRecord> lockClaim(UUID claimPublicId, long employeeId);

    List<UUID> findDraftEvidence(long claimId);

    Optional<CategoryLimitRecord> lockApplicableCategory(String categoryCode, LocalDate transactionDate);

    void insertRevision(long claimId, int revision, long categoryRevisionId, LocalDate transactionDate, long amount,
                        String description, String orgUnit, UUID managerPublicId, Instant submittedAt);

    void insertEvidence(long claimId, int revision, long documentVersionId);

    void sealRevision(long claimId, int revision);

    void insertReviewAssignments(long claimId, int revision, UUID managerAccountPublicId, UUID managerEmployeePublicId,
                                 Instant createdAt);

    void markSubmitted(long claimId, int revision);

    record ClaimRecord(long id, String status, int revision, long version, String category, LocalDate date,
                       long amount, String description) { }

    record CategoryLimitRecord(long id, long limit) { }
}
