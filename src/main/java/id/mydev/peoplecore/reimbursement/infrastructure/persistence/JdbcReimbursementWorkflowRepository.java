package id.mydev.peoplecore.reimbursement.infrastructure.persistence;

import id.mydev.peoplecore.reimbursement.domain.repository.ReimbursementWorkflowRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcReimbursementWorkflowRepository implements ReimbursementWorkflowRepository {
    private final JdbcTemplate jdbc;
    public JdbcReimbursementWorkflowRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override
    public List<UUID> findEmployee(UUID claimId) {
        return jdbc.query("SELECT e.public_id FROM reimbursement_claim c JOIN employees e ON e.id=c.employee_id WHERE c.public_id=?", (rs, row) -> rs.getObject(1, UUID.class), claimId);
    }

    @Override
    public List<EvidenceRecord> findEvidenceContent(long claimId, int revision, UUID documentId, long employeeId) {
        return jdbc.query("SELECT d.public_id, v.public_id, e.public_id, v.media_type "
                + "FROM reimbursement_evidence ev "
                + "JOIN document_version v ON v.id=ev.document_version_id "
                + "JOIN private_document d ON d.id=v.document_id "
                + "JOIN employees e ON e.id=d.employee_id "
                + "WHERE ev.claim_id=? AND ev.revision=? AND d.public_id=? AND d.employee_id=? "
                + "AND d.purpose='CLAIM_EVIDENCE' AND d.status='VERIFIED' AND d.deletion_state='LIVE'", (rs, row) -> new EvidenceRecord(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getString(4)), claimId, revision, documentId, employeeId);
    }

    @Override
    public List<Long> findHistoryCursor(long claimId, UUID cursor) {
        return jdbc.query("SELECT id FROM reimbursement_history WHERE claim_id=? AND public_id=?", (rs, row) -> rs.getLong(1), claimId, cursor);
    }

    @Override
    public List<HistoryRecord> findHistory(long claimId, Long beforeId, Long beforeAgain, int limit) {
        return jdbc.query("SELECT public_id,revision,action,actor_public_id,reason,reviewer_public_id,review_step,created_at "
                + "FROM reimbursement_history WHERE claim_id=? AND (?::bigint IS NULL OR id<?) ORDER BY id DESC LIMIT ?", (rs, row) -> new HistoryRecord(rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3), rs.getObject(4, UUID.class),
                rs.getString(5), rs.getObject(6, UUID.class), rs.getObject(7, Integer.class), rs.getTimestamp(8).toInstant()), claimId, beforeId, beforeAgain, limit);
    }

    @Override
    public List<SummaryRecord> list(long actorId, UUID actorPublicId, UUID ownEmployee, Instant at, UUID cursor,
        UUID cursorAgain, int limit) {
        return jdbc.query("""
            WITH actor AS (SELECT ?::bigint AS id, ?::uuid AS public_id, ?::uuid AS employee_id, ?::timestamptz AS at_time)
            SELECT c.public_id,e.public_id,c.status,c.revision,c.version,c.category_code,c.transaction_date,c.amount
            FROM reimbursement_claim c JOIN employees e ON e.id=c.employee_id CROSS JOIN actor a
            LEFT JOIN LATERAL (
                SELECT ea.org_unit FROM employee_assignments ea
                WHERE ea.employee_id=e.id AND ea.superseded_at IS NULL AND ea.valid_from<=a.at_time
                  AND (ea.valid_to IS NULL OR ea.valid_to>a.at_time)
                ORDER BY ea.valid_from DESC LIMIT 1
            ) org ON TRUE
            WHERE (?::uuid IS NULL OR c.public_id<?::uuid) AND (
                e.public_id=a.employee_id
                OR EXISTS (SELECT 1 FROM role_assignments role WHERE role.account_id=a.id
                    AND role.role IN ('HR_ADMIN','FINANCE_PAYROLL') AND role.effective_from<=a.at_time
                    AND (role.effective_to IS NULL OR role.effective_to>a.at_time)
                    AND (role.scope IS NULL OR btrim(role.scope)='' OR role.scope=org.org_unit))
                OR (c.status='PENDING' AND a.employee_id IS NOT NULL AND a.employee_id<>e.public_id
                    AND EXISTS (SELECT 1 FROM reimbursement_review_assignment ra
                        WHERE ra.claim_id=c.id AND ra.revision=c.revision AND ra.active
                          AND ra.reviewer_account_public_id=a.public_id AND ra.reviewer_employee_public_id=a.employee_id
                          AND ra.step=CASE WHEN EXISTS (SELECT 1 FROM reimbursement_decision d
                              WHERE d.claim_id=c.id AND d.revision=c.revision AND d.step=1 AND d.outcome='APPROVE')
                              THEN 2 ELSE 1 END
                          AND EXISTS (SELECT 1 FROM role_assignments role WHERE role.account_id=a.id
                              AND role.role=CASE WHEN ra.step=1 THEN 'MANAGER' ELSE 'FINANCE_PAYROLL' END
                              AND role.effective_from<=a.at_time AND (role.effective_to IS NULL OR role.effective_to>a.at_time)
                              AND (role.scope IS NULL OR btrim(role.scope)='' OR role.scope=org.org_unit))))
            ) ORDER BY c.public_id DESC LIMIT ?
            """, (rs, row) -> new SummaryRecord(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
            rs.getInt(4), rs.getLong(5), rs.getString(6), rs.getObject(7, LocalDate.class), rs.getLong(8)), actorId, actorPublicId, ownEmployee, ts(at), cursor, cursorAgain, limit);
    }

    @Override
    public void create(UUID publicId, long employeeId, String category, LocalDate date, long amount,
        String description, Instant at) {
        jdbc.update("INSERT INTO reimbursement_claim(public_id,employee_id,status,category_code,transaction_date,amount,description,created_at) "
                + "VALUES (?,?,'DRAFT',?,?,?,?,?)", publicId, employeeId, category, date, amount, description, ts(at));
    }

    @Override
    public void edit(String category, LocalDate date, long amount, String description, long claimId) {
        jdbc.update("UPDATE reimbursement_claim SET category_code=?,transaction_date=?,amount=?,description=?,version=version+1 WHERE id=?", category, date, amount, description, claimId);
    }

    @Override
    public void recordHistory(UUID publicId, long claimId, int revision, String action, UUID actor, String reason,
        Instant at) {
        jdbc.update("INSERT INTO reimbursement_history(public_id,claim_id,revision,action,actor_public_id,reason,created_at) "
                + "VALUES (?,?,?,?,?,?,?)", publicId, claimId, revision, action, actor, reason, ts(at));
    }

    @Override
    public void deactivateManager(long claimId, int revision) {
        jdbc.update("UPDATE reimbursement_review_assignment SET active=FALSE WHERE claim_id=? AND revision=? AND step=1 AND active", claimId, revision);
    }

    @Override
    public void assignManager(long claimId, int revision, UUID account, UUID employee, Instant at) {
        jdbc.update("INSERT INTO reimbursement_review_assignment(claim_id,revision,step,reviewer_account_public_id,reviewer_employee_public_id,created_at) VALUES (?,?,1,?,?,?)", claimId, revision, account, employee, ts(at));
    }

    @Override
    public void recordRouteRetry(UUID publicId, long claimId, int revision, UUID actor, String reason, Instant at) {
        jdbc.update("INSERT INTO reimbursement_history(public_id,claim_id,revision,action,actor_public_id,reason,created_at) VALUES (?,?,?,'RETRY_ROUTE',?,?,?)", publicId, claimId, revision, actor, reason, ts(at));
    }

    @Override
    public void incrementVersion(long claimId) {
        jdbc.update("UPDATE reimbursement_claim SET version=version+1 WHERE id=?", claimId);
    }

    @Override
    public Integer countDecisions(long claimId, int revision, int step) {
        return jdbc.queryForObject("SELECT count(id) FROM reimbursement_decision WHERE claim_id=? AND revision=? AND step=?", Integer.class, claimId, revision, step);
    }

    @Override
    public void deactivateReviewer(long claimId, int revision, int step) {
        jdbc.update("UPDATE reimbursement_review_assignment SET active=FALSE WHERE claim_id=? AND revision=? AND step=? AND active", claimId, revision, step);
    }

    @Override
    public void assignReviewer(long claimId, int revision, int step, UUID account, UUID employee, Instant at) {
        jdbc.update("INSERT INTO reimbursement_review_assignment(claim_id,revision,step,reviewer_account_public_id,reviewer_employee_public_id,created_at) VALUES (?,?,?,?,?,?)", claimId, revision, step, account, employee, ts(at));
    }

    @Override
    public void recordReassignment(UUID publicId, long claimId, int revision, UUID actor, String reason,
        UUID reviewer, int step, Instant at) {
        jdbc.update("INSERT INTO reimbursement_history(public_id,claim_id,revision,action,actor_public_id,reason,reviewer_public_id,review_step,created_at) VALUES (?,?,?,'REASSIGN',?,?,?,?,?)", publicId, claimId, revision, actor, reason, reviewer, step, ts(at));
    }

    @Override
    public UUID findApprovedManager(long claimId, int revision) {
        return jdbc.queryForObject("SELECT reviewer_employee_public_id FROM reimbursement_decision "
                + "WHERE claim_id=? AND revision=? AND step=1 AND outcome='APPROVE'", UUID.class, claimId, revision);
    }

    @Override
    public void recordDecision(long claimId, int revision, int step, UUID account, UUID employee, String outcome,
        String reason, Instant at) {
        jdbc.update("INSERT INTO reimbursement_decision(claim_id,revision,step,reviewer_account_public_id,reviewer_employee_public_id,outcome,reason,created_at) VALUES (?,?,?,?,?,?,?,?)", claimId, revision, step, account, employee, outcome, reason, ts(at));
    }

    @Override
    public void recordPayment(UUID publicId, long claimId, int revision, long amount, LocalDate paidDate,
        String reference, UUID actor, Instant at) {
        jdbc.update("INSERT INTO reimbursement_payment(public_id,claim_id,revision,amount,paid_date,payment_reference,created_by,created_at) VALUES (?,?,?,?,?,?,?,?)", publicId, claimId, revision, amount, paidDate, reference, actor, ts(at));
    }

    @Override
    public List<Payment> lockPayment(long claimId) {
        return jdbc.query("SELECT id,paid_date,payment_reference FROM reimbursement_payment WHERE claim_id=? FOR UPDATE", (rs, row) -> new Payment(rs.getLong(1), rs.getObject(2, LocalDate.class), rs.getString(3)), claimId);
    }

    @Override
    public List<Payment> findPaymentCorrections(long paymentId) {
        return jdbc.query("SELECT payment_id,corrected_paid_date,corrected_reference FROM reimbursement_payment_correction "
                + "WHERE payment_id=? ORDER BY id DESC LIMIT 1", (rs, row) -> new Payment(rs.getLong(1), rs.getObject(2, LocalDate.class), rs.getString(3)), paymentId);
    }

    @Override
    public void recordPaymentCorrection(UUID publicId, long paymentId, LocalDate priorDate, String priorReference,
        LocalDate paidDate, String reference, String reason, UUID actor, Instant at) {
        jdbc.update("INSERT INTO reimbursement_payment_correction(public_id,payment_id,prior_paid_date,prior_reference,corrected_paid_date,corrected_reference,reason,created_by,created_at) VALUES (?,?,?,?,?,?,?,?,?)", publicId, paymentId, priorDate, priorReference, paidDate, reference, reason, actor, ts(at));
    }

    @Override
    public void ensureAccount(long employeeId) {
        jdbc.update("INSERT INTO reimbursement_account(employee_id) VALUES (?) ON CONFLICT DO NOTHING", employeeId);
    }

    @Override
    public Long lockAccount(long employeeId) {
        return jdbc.queryForObject("SELECT employee_id FROM reimbursement_account WHERE employee_id=? FOR UPDATE", Long.class, employeeId);
    }

    @Override
    public List<Claim> findClaim(UUID id, long employeeId, boolean lock) {
        return jdbc.query("SELECT id,public_id,status,revision,version,category_code,transaction_date,amount,description FROM reimbursement_claim "
                + "WHERE public_id=? AND employee_id=?" + (lock ? " FOR UPDATE" : ""), (rs, row) -> new Claim(rs.getLong(1), rs.getObject(2, UUID.class), rs.getString(3), rs.getInt(4),
                rs.getLong(5), rs.getString(6), rs.getObject(7, LocalDate.class), rs.getLong(8), rs.getString(9)), id, employeeId);
    }

    @Override
    public List<Reviewer> findReviewers(long claimId, int revision, int step) {
        return jdbc.query("SELECT reviewer_account_public_id,reviewer_employee_public_id FROM reimbursement_review_assignment "
                + "WHERE claim_id=? AND revision=? AND step=? AND active", (rs, row) -> new Reviewer(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)), claimId, revision, step);
    }

    @Override
    public Integer countManagerApprovals(long claimId, int revision) {
        return jdbc.queryForObject("SELECT count(id) FROM reimbursement_decision WHERE claim_id=? AND revision=? AND step=1 AND outcome='APPROVE'", Integer.class, claimId, revision);
    }

    @Override
    public void transition(String status, long claimId) {
        jdbc.update("UPDATE reimbursement_claim SET status=?,version=version+1 WHERE id=?", status, claimId);
    }

    @Override
    public void clearEvidence(long claimId) {
        jdbc.update("DELETE FROM reimbursement_draft_evidence WHERE claim_id=?", claimId);
    }

    @Override
    public void insertEvidence(long claimId, UUID document) {
        jdbc.update("INSERT INTO reimbursement_draft_evidence(claim_id,document_public_id) VALUES (?,?)", claimId, document);
    }

    @Override
    public List<UUID> findEvidence(long claimId) {
        return jdbc.query("SELECT document_public_id FROM reimbursement_draft_evidence WHERE claim_id=? ORDER BY document_public_id", (rs, row) -> rs.getObject(1, UUID.class), claimId);
    }

    private static Timestamp ts(Instant value) { return value == null ? null : Timestamp.from(value); }
}
