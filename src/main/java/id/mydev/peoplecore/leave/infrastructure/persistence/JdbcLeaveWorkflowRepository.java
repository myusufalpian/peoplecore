package id.mydev.peoplecore.leave.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;
import id.mydev.peoplecore.leave.domain.repository.LeaveWorkflowRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcLeaveWorkflowRepository implements LeaveWorkflowRepository {
    private static final String COLUMNS="id,public_id,employee_id,status,revision,version,from_date,to_date,portion,reason";
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcLeaveWorkflowRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public void createDraft(UUID publicId, Long employeeId, LocalDate fromDate, LocalDate toDate, String portion, String reason, Instant createdAt) {
        jdbc.update("INSERT INTO leave_request(public_id,employee_id,status,from_date,to_date,portion,reason,created_at) VALUES (?,?,'DRAFT',?,?,?,?,?)", publicId, employeeId, fromDate, toDate, portion, reason, Timestamp.from(createdAt));
    }

    @Override
    public void editDraft(LocalDate fromDate, LocalDate toDate, String portion, String reason, Long id) {
        jdbc.update("UPDATE leave_request SET from_date=?,to_date=?,portion=?,reason=?,version=version+1 WHERE id=?", fromDate, toDate, portion, reason, id);
    }

    @Override
    public Long findRuleSetId(UUID publicId) {
        return jdbc.queryForObject("SELECT id FROM leave_rule_set WHERE public_id=?", Long.class, publicId);
    }

    @Override
    public Long findPeriodPolicyId(Long employeeId, int periodYear) {
        return jdbc.queryForObject("SELECT policy_revision_id FROM leave_period WHERE employee_id=? AND period_year=?", Long.class, employeeId, periodYear);
    }

    @Override
    public void insertRevision(Long requestId, int revision, int year, Long policyRevisionId, int halfUnits, String orgUnit, UUID managerEmployeePublicId, LocalDate fromDate, LocalDate toDate, String portion, String reason, Instant submittedAt, Long ruleSetId) {
        jdbc.update("INSERT INTO leave_revision(request_id,revision,year,policy_revision_id,half_units,org_unit,manager_employee_public_id,from_date,to_date,portion,reason,submitted_at,rule_set_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)", requestId, revision, year, policyRevisionId, halfUnits, orgUnit, managerEmployeePublicId, fromDate, toDate, portion, reason, Timestamp.from(submittedAt), ruleSetId);
    }

    @Override
    public void markPending(int revision, Long id) {
        jdbc.update("UPDATE leave_request SET status='PENDING',revision=?,version=version+1 WHERE id=?", revision, id);
    }

    @Override
    public Integer countOverlappingOccupancy(Long employeeId, Instant startAt, Instant endAt) {
        return jdbc.queryForObject("SELECT count(id) FROM leave_occupancy WHERE employee_id=? AND active=TRUE AND start_at<? AND end_at>?", Integer.class, employeeId, Timestamp.from(startAt), Timestamp.from(endAt));
    }

    @Override
    public void insertPortion(Long requestId, int revision, Long employeeId, Long occurrenceId, LocalDate workDate, int half, Instant startAt, Instant endAt) {
        jdbc.update("INSERT INTO leave_portion(request_id,revision,employee_id,occurrence_id,work_date,half,start_at,end_at) VALUES (?,?,?,?,?,?,?,?)", requestId, revision, employeeId, occurrenceId, workDate, half, Timestamp.from(startAt), Timestamp.from(endAt));
    }

    @Override
    public Long findPortionId(Long requestId, int revision, LocalDate workDate, int half) {
        return jdbc.queryForObject("SELECT id FROM leave_portion WHERE request_id=? AND revision=? AND work_date=? AND half=?", Long.class, requestId, revision, workDate, half);
    }

    @Override
    public void insertOccupancy(Long portionId, Long requestId, int revision, Long employeeId, LocalDate workDate, int half, Instant startAt, Instant endAt) {
        jdbc.update("INSERT INTO leave_occupancy(portion_id,request_id,revision,employee_id,work_date,half,start_at,end_at) VALUES (?,?,?,?,?,?,?,?)", portionId, requestId, revision, employeeId, workDate, half, Timestamp.from(startAt), Timestamp.from(endAt));
    }

    @Override
    public List<UUID> findApprovingManagerEmployees(Long requestId, int revision) {
        return jdbc.query("SELECT reviewer_employee_public_id FROM leave_decision WHERE request_id=? AND revision=? AND step=1 AND outcome='APPROVE'", (rs,n)->rs.getObject(1,UUID.class), requestId, revision);
    }

    @Override
    public Integer findRevisionHalfUnits(Long requestId, int revision) {
        return jdbc.queryForObject("SELECT half_units FROM leave_revision WHERE request_id=? AND revision=?", Integer.class, requestId, revision);
    }

    @Override
    public Integer countActiveOccupancy(Long requestId, int revision) {
        return jdbc.queryForObject("SELECT count(id) FROM leave_occupancy WHERE request_id=? AND revision=? AND active=TRUE", Integer.class, requestId, revision);
    }

    @Override
    public void insertDecision(Long requestId, int revision, int step, UUID reviewerAccountPublicId, UUID reviewerEmployeePublicId, String outcome, String reason, Instant createdAt) {
        jdbc.update("INSERT INTO leave_decision(request_id,revision,step,reviewer_account_public_id,reviewer_employee_public_id,outcome,reason,created_at) VALUES (?,?,?,?,?,?,?,?)", requestId, revision, step, reviewerAccountPublicId, reviewerEmployeePublicId, outcome, reason, Timestamp.from(createdAt));
    }

    @Override
    public void incrementRequestVersion(Long id) {
        jdbc.update("UPDATE leave_request SET version=version+1 WHERE id=?", id);
    }

    @Override
    public void releaseOccupancy(Long requestId, int revision) {
        jdbc.update("UPDATE leave_occupancy SET active=FALSE WHERE request_id=? AND revision=? AND active=TRUE", requestId, revision);
    }

    @Override
    public List<UUID> findManagerDecisionEmployees(Long requestId, int revision) {
        return jdbc.query("SELECT reviewer_employee_public_id FROM leave_decision WHERE request_id=? AND revision=? AND step=1", (rs,n)->rs.getObject(1,UUID.class), requestId, revision);
    }

    @Override
    public void deactivateStepAssignment(Long requestId, int revision, int step) {
        jdbc.update("UPDATE leave_review_assignment SET active=FALSE WHERE request_id=? AND revision=? AND step=? AND active=TRUE", requestId, revision, step);
    }

    @Override
    public void incrementReassignedRequestVersion(Long id) {
        jdbc.update("UPDATE leave_request SET version=version+1 WHERE id=?", id);
    }

    @Override
    public UUID findRevisionManager(Long requestId, int revision) {
        return jdbc.queryForObject("SELECT manager_employee_public_id FROM leave_revision WHERE request_id=? AND revision=?", UUID.class, requestId, revision);
    }

    @Override
    public void deactivateAssignments(Long requestId, int revision) {
        jdbc.update("UPDATE leave_review_assignment SET active=FALSE WHERE request_id=? AND revision=? AND active=TRUE", requestId, revision);
    }

    @Override
    public void incrementReroutedRequestVersion(Long id) {
        jdbc.update("UPDATE leave_request SET version=version+1 WHERE id=?", id);
    }

    @Override
    public List<Long> findPendingCancellationIds(Long requestId) {
        return jdbc.query("SELECT id FROM leave_cancellation WHERE request_id=? AND status='PENDING'", (rs,n)->rs.getLong(1), requestId);
    }

    @Override
    public Long findCancellationRuleSetId(UUID publicId) {
        return jdbc.queryForObject("SELECT id FROM leave_rule_set WHERE public_id=?", Long.class, publicId);
    }

    @Override
    public void insertCancellation(UUID publicId, Long requestId, String reason, String orgUnit, Long ruleSetId, Instant createdAt) {
        jdbc.update("INSERT INTO leave_cancellation(public_id,request_id,status,reason,org_unit,rule_set_id,created_at) VALUES (?,?,'PENDING',?,?,?,?)", publicId, requestId, reason, orgUnit, ruleSetId, Timestamp.from(createdAt));
    }

    @Override
    public void incrementCancellationRequestVersion(Long id) {
        jdbc.update("UPDATE leave_request SET version=version+1 WHERE id=?", id);
    }

    @Override
    public void deactivateCancellationAssignments(Long cancellationId) {
        jdbc.update("UPDATE leave_cancellation_assignment SET active=FALSE WHERE cancellation_id=? AND active=TRUE", cancellationId);
    }

    @Override
    public void incrementCancellationVersion(Long id) {
        jdbc.update("UPDATE leave_cancellation SET version=version+1 WHERE id=?", id);
    }

    @Override
    public void releaseCancelledOccupancy(Long requestId, int revision) {
        jdbc.update("UPDATE leave_occupancy SET active=FALSE WHERE request_id=? AND revision=? AND active=TRUE", requestId, revision);
    }

    @Override
    public void insertCancellationDecision(Long cancellationId, UUID reviewerAccountPublicId, String outcome, String reason, Instant createdAt) {
        jdbc.update("INSERT INTO leave_cancellation_decision(cancellation_id,reviewer_account_public_id,outcome,reason,created_at) VALUES (?,?,?,?,?)", cancellationId, reviewerAccountPublicId, outcome, reason, Timestamp.from(createdAt));
    }

    @Override
    public List<Request> findVisibleRequests(Long employeeId, long id, boolean reviewerAccountPublicId, UUID reviewerEmployeePublicId, UUID parameter5, int parameter6) {
        return jdbc.query("SELECT r.id,r.public_id,r.employee_id,r.status,r.revision,r.version,r.from_date,r.to_date,r.portion,r.reason FROM leave_request r WHERE r.employee_id=? AND r.id>? AND (? OR (r.status='PENDING' AND EXISTS (SELECT a.id FROM leave_review_assignment a WHERE a.request_id=r.id AND a.revision=r.revision AND a.step=1 AND a.active=TRUE AND a.reviewer_account_public_id=? AND a.reviewer_employee_public_id=?) AND NOT EXISTS (SELECT d.id FROM leave_decision d WHERE d.request_id=r.id AND d.revision=r.revision AND d.step=1 AND d.outcome='APPROVE'))) ORDER BY r.id LIMIT ?", this::mapRequest, employeeId, id, reviewerAccountPublicId, reviewerEmployeePublicId, parameter5, parameter6);
    }

    @Override
    public List<RouteSnapshot> findRouteSnapshot(Long requestId, int revision) {
        return jdbc.query("SELECT step,reviewer_account_public_id,reviewer_employee_public_id,fallback,active FROM leave_review_assignment WHERE request_id=? AND revision=? AND active=TRUE ORDER BY id", (rs,n)->new RouteSnapshot(rs.getInt(1),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),rs.getBoolean(4),rs.getBoolean(5)), requestId, revision);
    }

    @Override
    public List<PortionViewRow> findCapturedPortions(Long requestId, int revision) {
        return jdbc.query("SELECT work_date,half,start_at,end_at FROM leave_portion WHERE request_id=? AND revision=? ORDER BY work_date,half", (rs,n)->new PortionViewRow(rs.getObject(1,LocalDate.class),rs.getInt(2),rs.getTimestamp(3).toInstant(),rs.getTimestamp(4).toInstant()), requestId, revision);
    }

    @Override
    public void insertHistory(UUID publicId, Long requestId, UUID cancellationPublicId, int revision, String action, String payload, Instant createdAt) {
        jdbc.update("INSERT INTO leave_history(public_id,request_id,cancellation_public_id,revision,action,payload,created_at) VALUES (?,?,?,?,?,?,?)", publicId, requestId, cancellationPublicId, revision, action, payload, Timestamp.from(createdAt));
    }

    @Override
    public List<Long> findHistoryCursor(UUID publicId, Long requestId, UUID cancellationPublicId, UUID parameter4) {
        return jdbc.query("SELECT id FROM leave_history WHERE public_id=? AND request_id=? AND (CAST(? AS UUID) IS NULL OR cancellation_public_id=?)", (rs,n)->rs.getLong(1), publicId, requestId, cancellationPublicId, parameter4);
    }

    @Override
    public List<TimelineEntryRow> findTimeline(Long requestId, long id, UUID cancellationPublicId, UUID parameter4, int parameter5) {
        return jdbc.query("SELECT public_id,action,revision,cancellation_public_id,payload,created_at FROM leave_history WHERE request_id=? AND id>? AND (CAST(? AS UUID) IS NULL OR cancellation_public_id=?) ORDER BY id LIMIT ?", (rs,n)->new TimelineEntryRow(rs.getObject(1,UUID.class),rs.getString(2),rs.getInt(3),rs.getObject(4,UUID.class),mapper.readTree(rs.getString(5)),rs.getTimestamp(6).toInstant()), requestId, id, cancellationPublicId, parameter4, parameter5);
    }

    @Override
    public List<Long> findSummaryCursor(UUID publicId, Long employeeId, LocalDate workDate, LocalDate workDate2) {
        return jdbc.query("SELECT p.id FROM leave_portion p JOIN leave_request r ON r.id=p.request_id WHERE p.public_id=? AND r.employee_id=? AND p.work_date>=? AND p.work_date<? AND r.status IN ('APPROVED','CANCELLED')", (rs,n)->rs.getLong(1), publicId, employeeId, workDate, workDate2);
    }

    @Override
    public List<SummaryRow> findSummary(Long employeeId, LocalDate workDate, LocalDate workDate2, long id, int parameter5) {
        return jdbc.query("SELECT p.public_id,r.public_id,p.work_date,p.half,p.start_at,p.end_at,r.status FROM leave_portion p JOIN leave_request r ON r.id=p.request_id AND r.revision=p.revision WHERE r.employee_id=? AND p.work_date>=? AND p.work_date<? AND r.status IN ('APPROVED','CANCELLED') AND p.id>? ORDER BY p.id LIMIT ?", (rs,n)->new SummaryRow(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,LocalDate.class),rs.getInt(4),rs.getTimestamp(5).toInstant(),rs.getTimestamp(6).toInstant(),rs.getString(7)), employeeId, workDate, workDate2, id, parameter5);
    }

    @Override
    public List<HistoryRow> findDecisionHistory(Long requestId) {
        return jdbc.query("SELECT revision,step,reviewer_account_public_id,outcome,reason,created_at FROM leave_decision WHERE request_id=? ORDER BY id", (rs,n)->new HistoryRow(rs.getInt(1),rs.getInt(2),rs.getObject(3,UUID.class),rs.getString(4),rs.getString(5),rs.getTimestamp(6).toInstant()), requestId);
    }

    @Override
    public List<PortionViewRow> findPortions(Long requestId, int revision) {
        return jdbc.query("SELECT work_date,half,start_at,end_at FROM leave_portion WHERE request_id=? AND revision=? ORDER BY work_date,half", (rs,n)->new PortionViewRow(rs.getObject(1,LocalDate.class),rs.getInt(2),rs.getTimestamp(3).toInstant(),rs.getTimestamp(4).toInstant()), requestId, revision);
    }

    @Override
    public List<HistoryRow> findCancellationHistory(Long cancellationId) {
        return jdbc.query("SELECT reviewer_account_public_id,outcome,reason,created_at FROM leave_cancellation_decision WHERE cancellation_id=? ORDER BY id", (rs,n)->new HistoryRow(1,1,rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getTimestamp(4).toInstant()), cancellationId);
    }

    @Override
    public String findRevisionUnit(Long requestId, int revision) {
        return jdbc.queryForObject("SELECT org_unit FROM leave_revision WHERE request_id=? AND revision=?", String.class, requestId, revision);
    }

    @Override
    public List<Request> findRequest(boolean lock, UUID parameter1) {
        return jdbc.query("SELECT "+COLUMNS+" FROM leave_request WHERE public_id=?"+(lock?" FOR UPDATE":""), this::mapRequest, parameter1);
    }

    @Override
    public List<String> findBlockedReasons(Long requestId, int revision) {
        return jdbc.query("SELECT blocked_reason FROM leave_route_evaluation WHERE request_id=? AND revision=? AND cancellation_public_id IS NULL ORDER BY id DESC LIMIT 1", (rs,n)->rs.getString(1), requestId, revision);
    }

    @Override
    public Integer countManagerApprovals(Long requestId, int revision) {
        return jdbc.queryForObject("SELECT count(id) FROM leave_decision WHERE request_id=? AND revision=? AND step=1 AND outcome='APPROVE'", Integer.class, requestId, revision);
    }

    @Override
    public List<Reviewer> findReviewer(Long requestId, int revision, int step) {
        return jdbc.query("SELECT reviewer_account_public_id,reviewer_employee_public_id,fallback FROM leave_review_assignment WHERE request_id=? AND revision=? AND step=? AND active=TRUE", (rs,n)->new Reviewer(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getBoolean(3)), requestId, revision, step);
    }

    @Override
    public void insertAssignment(Long requestId, int revision, int step, UUID reviewerAccountPublicId, UUID reviewerEmployeePublicId, boolean fallback, Instant createdAt) {
        jdbc.update("INSERT INTO leave_review_assignment(request_id,revision,step,reviewer_account_public_id,reviewer_employee_public_id,fallback,created_at) VALUES (?,?,?,?,?,?,?)", requestId, revision, step, reviewerAccountPublicId, reviewerEmployeePublicId, fallback, Timestamp.from(createdAt));
    }

    @Override
    public void insertRecovery(Long requestId, int revision, String action, String reason, Instant createdAt) {
        jdbc.update("INSERT INTO leave_recovery(request_id,revision,action,reason,created_at) VALUES (?,?,?,?,?)", requestId, revision, action, reason, Timestamp.from(createdAt));
    }

    @Override
    public void transitionRequest(String status, Long id) {
        jdbc.update("UPDATE leave_request SET status=?,version=version+1 WHERE id=?", status, id);
    }

    @Override
    public List<Cancellation> findCancellation(boolean lock, UUID publicId) {
        return jdbc.query("SELECT id,public_id,request_id,status,version,reason,org_unit FROM leave_cancellation WHERE public_id=?"+(lock?" FOR UPDATE":""), (rs,n)->new Cancellation(rs.getLong(1),rs.getObject(2,UUID.class),rs.getLong(3),rs.getString(4),rs.getLong(5),rs.getString(6),rs.getString(7)), publicId);
    }

    @Override
    public List<Reviewer> findCancellationReviewer(Long cancellationId) {
        return jdbc.query("SELECT reviewer_account_public_id,reviewer_employee_public_id FROM leave_cancellation_assignment WHERE cancellation_id=? AND active=TRUE", (rs,n)->new Reviewer(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),false), cancellationId);
    }

    @Override
    public void insertCancellationAssignment(Long cancellationId, UUID reviewerAccountPublicId, UUID reviewerEmployeePublicId, Instant createdAt) {
        jdbc.update("INSERT INTO leave_cancellation_assignment(cancellation_id,reviewer_account_public_id,reviewer_employee_public_id,created_at) VALUES (?,?,?,?)", cancellationId, reviewerAccountPublicId, reviewerEmployeePublicId, Timestamp.from(createdAt));
    }

    @Override
    public void transitionCancellation(String status, Long id) {
        jdbc.update("UPDATE leave_cancellation SET status=?,version=version+1 WHERE id=?", status, id);
    }

    @Override
    public void insertRouteEvaluation(Long requestId, int revision, UUID cancellationPublicId, UUID configurationPublicId, UUID managerAccountPublicId, UUID hrAccountPublicId, UUID cancellationAccountPublicId, String blockedReason, Instant createdAt) {
        jdbc.update("INSERT INTO leave_route_evaluation(request_id,revision,cancellation_public_id,configuration_public_id,manager_account_public_id,hr_account_public_id,cancellation_account_public_id,blocked_reason,created_at) VALUES (?,?,?,?,?,?,?,?,?)", requestId, revision, cancellationPublicId, configurationPublicId, managerAccountPublicId, hrAccountPublicId, cancellationAccountPublicId, blockedReason, Timestamp.from(createdAt));
    }
    private Request mapRequest(ResultSet rs,int n) throws SQLException {
        return new Request(rs.getLong("id"),rs.getObject("public_id",UUID.class),rs.getLong("employee_id"),rs.getString("status"),rs.getInt("revision"),rs.getLong("version"),rs.getObject("from_date",LocalDate.class),rs.getObject("to_date",LocalDate.class),rs.getString("portion"),rs.getString("reason"));
    }
}
