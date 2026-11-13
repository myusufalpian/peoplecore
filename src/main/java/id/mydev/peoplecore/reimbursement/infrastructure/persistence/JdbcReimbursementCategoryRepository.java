package id.mydev.peoplecore.reimbursement.infrastructure.persistence;

import id.mydev.peoplecore.reimbursement.domain.repository.ReimbursementCategoryRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcReimbursementCategoryRepository implements ReimbursementCategoryRepository {
    private final JdbcTemplate jdbc;

    public JdbcReimbursementCategoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<CategoryRecord> findApplicable(LocalDate date, UUID cursor, int limitPlusOne) {
        return jdbc.query("SELECT public_id,category_code,effective_from,effective_to,claim_limit "
                + "FROM reimbursement_category_revision WHERE NOT superseded AND effective_from<=? "
                + "AND (effective_to IS NULL OR effective_to>?) AND (?::uuid IS NULL OR public_id<?::uuid) "
                + "ORDER BY public_id DESC LIMIT ?",
            (rs, row) -> new CategoryRecord(rs.getObject(1, UUID.class), rs.getString(2),
                rs.getObject(3, LocalDate.class), rs.getObject(4, LocalDate.class), rs.getLong(5)),
            date, date, cursor, cursor, limitPlusOne);
    }

    @Override
    public void lockPublication(String categoryCode) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", rs -> { }, categoryCode);
    }

    @Override
    public List<OverlapRecord> lockOverlaps(String categoryCode, LocalDate from, LocalDate to) {
        return jdbc.query("SELECT id,effective_from,effective_to,claim_limit FROM reimbursement_category_revision "
                + "WHERE category_code=? AND NOT superseded AND (effective_to IS NULL OR effective_to>?) "
                + "AND (?::date IS NULL OR effective_from<?) ORDER BY id FOR UPDATE",
            (rs, row) -> new OverlapRecord(rs.getLong(1), rs.getObject(2, LocalDate.class),
                rs.getObject(3, LocalDate.class), rs.getLong(4)),
            categoryCode, from, to, to);
    }

    @Override
    public void markSuperseded(long id) {
        jdbc.update("UPDATE reimbursement_category_revision SET superseded=TRUE WHERE id=?", id);
    }

    @Override
    public void insert(UUID publicId, String code, LocalDate from, LocalDate to, long limit, Instant createdAt) {
        jdbc.update("INSERT INTO reimbursement_category_revision(public_id,category_code,effective_from,"
                + "effective_to,claim_limit,created_at) VALUES (?,?,?,?,?,?)",
            publicId, code, from, to, limit, Timestamp.from(createdAt));
    }
}
