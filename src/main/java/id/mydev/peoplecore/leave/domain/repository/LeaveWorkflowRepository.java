package id.mydev.peoplecore.leave.domain.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public interface LeaveWorkflowRepository {
    record Request(Long id,UUID publicId,Long employeeId,String status,int revision,
                           long version,LocalDate from,LocalDate to,String portion,String reason) { }
    record Reviewer(UUID account,UUID employee,boolean fallback) { }
    record Cancellation(Long id,UUID publicId,Long requestId,String status,long version,String reason,String unit) { }
    record RouteSnapshot(int step,UUID reviewerAccountPublicId,UUID reviewerEmployeePublicId,boolean fallback,boolean active) { }

    record HistoryRow(int revision, int step, UUID reviewerAccountPublicId,
                          String outcome, String reason, Instant createdAt) { }

    record PortionViewRow(LocalDate workDate, int half, Instant startAt, Instant endAt) { }

    record TimelineEntryRow(UUID publicId, String action, int revision,
                                UUID cancellationPublicId, JsonNode payload, Instant createdAt) { }

    record SummaryRow(UUID publicId, UUID requestPublicId, LocalDate workDate,
                          int half, Instant startAt, Instant endAt, String status) { }

    void createDraft(UUID publicId, Long employeeId, LocalDate fromDate, LocalDate toDate, String portion, String reason, Instant createdAt);
    void editDraft(LocalDate fromDate, LocalDate toDate, String portion, String reason, Long id);
    Long findRuleSetId(UUID publicId);
    Long findPeriodPolicyId(Long employeeId, int periodYear);
    void insertRevision(Long requestId, int revision, int year, Long policyRevisionId, int halfUnits, String orgUnit, UUID managerEmployeePublicId, LocalDate fromDate, LocalDate toDate, String portion, String reason, Instant submittedAt, Long ruleSetId);
    void markPending(int revision, Long id);
    Integer countOverlappingOccupancy(Long employeeId, Instant startAt, Instant endAt);
    void insertPortion(Long requestId, int revision, Long employeeId, Long occurrenceId, LocalDate workDate, int half, Instant startAt, Instant endAt);
    Long findPortionId(Long requestId, int revision, LocalDate workDate, int half);
    void insertOccupancy(Long portionId, Long requestId, int revision, Long employeeId, LocalDate workDate, int half, Instant startAt, Instant endAt);
    List<UUID> findApprovingManagerEmployees(Long requestId, int revision);
    Integer findRevisionHalfUnits(Long requestId, int revision);
    Integer countActiveOccupancy(Long requestId, int revision);
    void insertDecision(Long requestId, int revision, int step, UUID reviewerAccountPublicId, UUID reviewerEmployeePublicId, String outcome, String reason, Instant createdAt);
    void incrementRequestVersion(Long id);
    void releaseOccupancy(Long requestId, int revision);
    List<UUID> findManagerDecisionEmployees(Long requestId, int revision);
    void deactivateStepAssignment(Long requestId, int revision, int step);
    void incrementReassignedRequestVersion(Long id);
    UUID findRevisionManager(Long requestId, int revision);
    void deactivateAssignments(Long requestId, int revision);
    void incrementReroutedRequestVersion(Long id);
    List<Long> findPendingCancellationIds(Long requestId);
    Long findCancellationRuleSetId(UUID publicId);
    void insertCancellation(UUID publicId, Long requestId, String reason, String orgUnit, Long ruleSetId, Instant createdAt);
    void incrementCancellationRequestVersion(Long id);
    void deactivateCancellationAssignments(Long cancellationId);
    void incrementCancellationVersion(Long id);
    void releaseCancelledOccupancy(Long requestId, int revision);
    void insertCancellationDecision(Long cancellationId, UUID reviewerAccountPublicId, String outcome, String reason, Instant createdAt);
    List<Request> findVisibleRequests(Long employeeId, long id, boolean reviewerAccountPublicId, UUID reviewerEmployeePublicId, UUID parameter5, int parameter6);
    List<RouteSnapshot> findRouteSnapshot(Long requestId, int revision);
    List<PortionViewRow> findCapturedPortions(Long requestId, int revision);
    void insertHistory(UUID publicId, Long requestId, UUID cancellationPublicId, int revision, String action, String payload, Instant createdAt);
    List<Long> findHistoryCursor(UUID publicId, Long requestId, UUID cancellationPublicId, UUID parameter4);
    List<TimelineEntryRow> findTimeline(Long requestId, long id, UUID cancellationPublicId, UUID parameter4, int parameter5);
    List<Long> findSummaryCursor(UUID publicId, Long employeeId, LocalDate workDate, LocalDate workDate2);
    List<SummaryRow> findSummary(Long employeeId, LocalDate workDate, LocalDate workDate2, long id, int parameter5);
    List<HistoryRow> findDecisionHistory(Long requestId);
    List<PortionViewRow> findPortions(Long requestId, int revision);
    List<HistoryRow> findCancellationHistory(Long cancellationId);
    String findRevisionUnit(Long requestId, int revision);
    List<Request> findRequest(boolean lock, UUID parameter1);
    List<String> findBlockedReasons(Long requestId, int revision);
    Integer countManagerApprovals(Long requestId, int revision);
    List<Reviewer> findReviewer(Long requestId, int revision, int step);
    void insertAssignment(Long requestId, int revision, int step, UUID reviewerAccountPublicId, UUID reviewerEmployeePublicId, boolean fallback, Instant createdAt);
    void insertRecovery(Long requestId, int revision, String action, String reason, Instant createdAt);
    void transitionRequest(String status, Long id);
    List<Cancellation> findCancellation(boolean lock, UUID publicId);
    List<Reviewer> findCancellationReviewer(Long cancellationId);
    void insertCancellationAssignment(Long cancellationId, UUID reviewerAccountPublicId, UUID reviewerEmployeePublicId, Instant createdAt);
    void transitionCancellation(String status, Long id);
    void insertRouteEvaluation(Long requestId, int revision, UUID cancellationPublicId, UUID configurationPublicId, UUID managerAccountPublicId, UUID hrAccountPublicId, UUID cancellationAccountPublicId, String blockedReason, Instant createdAt);
}
