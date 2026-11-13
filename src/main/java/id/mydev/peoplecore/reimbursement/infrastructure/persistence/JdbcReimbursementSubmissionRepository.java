package id.mydev.peoplecore.reimbursement.infrastructure.persistence;

import id.mydev.peoplecore.reimbursement.domain.repository.ReimbursementSubmissionRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcReimbursementSubmissionRepository implements ReimbursementSubmissionRepository {
    private final JdbcTemplate jdbc;

    public JdbcReimbursementSubmissionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void ensureAccount(long employeeId) {
        jdbc.update("INSERT INTO reimbursement_account(employee_id) VALUES (?) ON CONFLICT DO NOTHING", employeeId);
    }

    @Override
    public void lockAccount(long employeeId) {
        jdbc.queryForObject("SELECT employee_id FROM reimbursement_account WHERE employee_id=? FOR UPDATE",
            Long.class, employeeId);
    }

    @Override
    public Optional<ClaimRecord> lockClaim(UUID claimPublicId, long employeeId) {
        return jdbc.query("SELECT id,status,revision,version,category_code,transaction_date,amount,description "
                + "FROM reimbursement_claim WHERE public_id=? AND employee_id=? FOR UPDATE",
            (rs, row) -> new ClaimRecord(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getLong(4),
                rs.getString(5), rs.getObject(6, LocalDate.class), rs.getLong(7), rs.getString(8)),
            claimPublicId, employeeId).stream().findFirst();
    }

    @Override
    public List<UUID> findDraftEvidence(long claimId) {
        return jdbc.query("SELECT document_public_id FROM reimbursement_draft_evidence WHERE claim_id=? "
                + "ORDER BY document_public_id",
            (rs, row) -> rs.getObject(1, UUID.class), claimId);
    }

    @Override
    public Optional<CategoryLimitRecord> lockApplicableCategory(String categoryCode, LocalDate transactionDate) {
        return jdbc.query("SELECT id,claim_limit FROM reimbursement_category_revision "
                + "WHERE category_code=? AND NOT superseded AND effective_from<=? "
                + "AND (effective_to IS NULL OR effective_to>?) FOR SHARE",
            (rs, row) -> new CategoryLimitRecord(rs.getLong(1), rs.getLong(2)),
            categoryCode, transactionDate, transactionDate).stream().findFirst();
    }

    @Override
    public void insertRevision(long claimId, int revision, long categoryRevisionId, LocalDate transactionDate,
                               long amount, String description, String orgUnit, UUID managerPublicId,
                               Instant submittedAt) {
        jdbc.update("INSERT INTO reimbursement_revision(claim_id,revision,category_revision_id,transaction_date,"
                + "amount,description,org_unit,manager_employee_public_id,submitted_at) "
                + "VALUES (?,?,?,?,?,?,?,?,?)",
            claimId, revision, categoryRevisionId, transactionDate, amount, description, orgUnit, managerPublicId,
            Timestamp.from(submittedAt));
    }

    @Override
    public void insertEvidence(long claimId, int revision, long documentVersionId) {
        jdbc.update("INSERT INTO reimbursement_evidence(claim_id,revision,document_version_id) VALUES (?,?,?)",
            claimId, revision, documentVersionId);
    }

    @Override
    public void sealRevision(long claimId, int revision) {
        jdbc.update("UPDATE reimbursement_revision SET sealed=TRUE WHERE claim_id=? AND revision=?",
            claimId, revision);
    }

    @Override
    public void insertReviewAssignments(long claimId, int revision, UUID managerAccountPublicId,
                                        UUID managerEmployeePublicId, Instant createdAt) {
        jdbc.update("INSERT INTO reimbursement_review_assignment(claim_id,revision,step,"
                + "reviewer_account_public_id,reviewer_employee_public_id,created_at) "
                + "VALUES (?,?,1,?,?,?),(?,?,2,NULL,NULL,?)",
            claimId, revision, managerAccountPublicId, managerEmployeePublicId, Timestamp.from(createdAt),
            claimId, revision, Timestamp.from(createdAt));
    }

    @Override
    public void markSubmitted(long claimId, int revision) {
        jdbc.update("UPDATE reimbursement_claim SET status='PENDING',revision=?,version=version+1 WHERE id=?",
            revision, claimId);
    }
}
