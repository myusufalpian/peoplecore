package id.mydev.peoplecore.reimbursement.domain.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface ReimbursementWorkflowRepository {
    List<UUID> findEmployee(UUID claimId);
    List<EvidenceRecord> findEvidenceContent(long claimId, int revision, UUID documentId, long employeeId);
    List<Long> findHistoryCursor(long claimId, UUID cursor);
    List<HistoryRecord> findHistory(long claimId, Long beforeId, Long beforeAgain, int limit);
    List<SummaryRecord> list(long actorId, UUID actorPublicId, UUID ownEmployee, Instant at, UUID cursor,
        UUID cursorAgain, int limit);
    void create(UUID publicId, long employeeId, String category, LocalDate date, long amount, String description,
        Instant at);
    void edit(String category, LocalDate date, long amount, String description, long claimId);
    void recordHistory(UUID publicId, long claimId, int revision, String action, UUID actor, String reason, Instant at);
    void deactivateManager(long claimId, int revision);
    void assignManager(long claimId, int revision, UUID account, UUID employee, Instant at);
    void recordRouteRetry(UUID publicId, long claimId, int revision, UUID actor, String reason, Instant at);
    void incrementVersion(long claimId);
    Integer countDecisions(long claimId, int revision, int step);
    void deactivateReviewer(long claimId, int revision, int step);
    void assignReviewer(long claimId, int revision, int step, UUID account, UUID employee, Instant at);
    void recordReassignment(UUID publicId, long claimId, int revision, UUID actor, String reason, UUID reviewer,
        int step, Instant at);
    UUID findApprovedManager(long claimId, int revision);
    void recordDecision(long claimId, int revision, int step, UUID account, UUID employee, String outcome,
        String reason, Instant at);
    void recordPayment(UUID publicId, long claimId, int revision, long amount, LocalDate paidDate,
        String reference, UUID actor, Instant at);
    List<Payment> lockPayment(long claimId);
    List<Payment> findPaymentCorrections(long paymentId);
    void recordPaymentCorrection(UUID publicId, long paymentId, LocalDate priorDate, String priorReference,
        LocalDate paidDate, String reference, String reason, UUID actor, Instant at);
    void ensureAccount(long employeeId);
    Long lockAccount(long employeeId);
    List<Claim> findClaim(UUID id, long employeeId, boolean lock);
    List<Reviewer> findReviewers(long claimId, int revision, int step);
    Integer countManagerApprovals(long claimId, int revision);
    void transition(String status, long claimId);
    void clearEvidence(long claimId);
    void insertEvidence(long claimId, UUID document);
    List<UUID> findEvidence(long claimId);
    record Claim(long id, UUID publicId, String status, int revision, long version, String category,
                         LocalDate date, long amount, String description) { }
    record Reviewer(UUID account, UUID employee) { }
    record Payment(long id, LocalDate date, String reference) { }
    record EvidenceRecord(UUID publicId, UUID versionId, UUID employeeId, String mediaType) { }
    record HistoryRecord(UUID publicId, int revision, String action, UUID actor, String reason, UUID reviewer,
        Integer step, Instant createdAt) { }
    record SummaryRecord(UUID publicId, UUID employeeId, String status, int revision, long version,
        String category, LocalDate date, long amount) { }
}
