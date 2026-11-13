package id.mydev.peoplecore.attendance.infrastructure.persistence;

import id.mydev.peoplecore.attendance.domain.repository.AttendanceReviewRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAttendanceReviewRepository implements AttendanceReviewRepository {
    private final JdbcTemplate jdbc;

    public JdbcAttendanceReviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertRuleSet(UUID publicId, Instant createdAt) {
        jdbc.update("INSERT INTO attendance_rule_set(public_id,created_at) VALUES(?,?)",
            publicId, Timestamp.from(createdAt));
    }

    @Override
    public void insertRule(UUID rulePublicId, UUID ruleSetId, int priority, String orgUnit, Long designatedHrId) {
        jdbc.update("INSERT INTO attendance_rule(public_id,rule_set_id,priority,org_unit,designated_hr_id) "
                + "VALUES(?,?,?,?,?)",
            rulePublicId, ruleSetId, priority, orgUnit, designatedHrId);
    }

    @Override
    public void markPublished(UUID ruleSetId) {
        jdbc.update("UPDATE attendance_rule_set SET published=TRUE WHERE public_id=?", ruleSetId);
    }

    @Override
    public long countPublished(UUID ruleSetId) {
        Long count = jdbc.queryForObject("SELECT count(id) FROM attendance_rule_set WHERE public_id=? "
            + "AND published=TRUE", Long.class, ruleSetId);
        return count == null ? 0L : count;
    }

    @Override
    public void insertActivation(UUID publicId, UUID ruleSetId, String reason, Instant createdAt) {
        jdbc.update("INSERT INTO attendance_rule_activation(public_id,rule_set_id,reason,created_at) VALUES(?,?,?,?)",
            publicId, ruleSetId, reason, Timestamp.from(createdAt));
    }

    @Override
    public List<RuleMatchRecord> findMatchingRules(String orgUnit) {
        return jdbc.query("SELECT r.priority,r.designated_hr_id FROM attendance_rule r "
                + "WHERE r.rule_set_id=(SELECT rule_set_id FROM attendance_rule_activation ORDER BY id DESC LIMIT 1) "
                + "AND (r.org_unit IS NULL OR r.org_unit=?)",
            (rs, row) -> new RuleMatchRecord(rs.getInt("priority"), rs.getObject("designated_hr_id", Long.class)),
            orgUnit);
    }

    @Override
    public Optional<UUID> findActiveRuleSetPublicId() {
        return jdbc.query("SELECT s.public_id FROM attendance_rule_activation a "
                + "JOIN attendance_rule_set s ON s.public_id=a.rule_set_id ORDER BY a.id DESC LIMIT 1",
            (rs, row) -> rs.getObject(1, UUID.class)).stream().findFirst();
    }

    @Override
    public long countDesignatedHr(long reviewerId, String orgUnit) {
        Long count = jdbc.queryForObject("SELECT count(id) FROM attendance_rule WHERE "
                + "rule_set_id=(SELECT rule_set_id FROM attendance_rule_activation ORDER BY id DESC LIMIT 1) "
                + "AND designated_hr_id=? AND (org_unit IS NULL OR org_unit=?)", Long.class, reviewerId, orgUnit);
        return count == null ? 0L : count;
    }
}
