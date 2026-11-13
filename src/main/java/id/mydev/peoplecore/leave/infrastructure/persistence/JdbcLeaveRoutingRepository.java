package id.mydev.peoplecore.leave.infrastructure.persistence;

import id.mydev.peoplecore.leave.domain.repository.LeaveRoutingRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcLeaveRoutingRepository implements LeaveRoutingRepository {
    private final JdbcTemplate jdbc;

    public JdbcLeaveRoutingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertRuleSet(UUID publicId, Instant createdAt) {
        jdbc.update("INSERT INTO leave_rule_set(public_id,created_at) VALUES (?,?)",
            publicId, Timestamp.from(createdAt));
    }

    @Override
    public long ruleSetId(UUID publicId) {
        Long id = jdbc.queryForObject("SELECT id FROM leave_rule_set WHERE public_id=?", Long.class, publicId);
        return id == null ? 0L : id;
    }

    @Override
    public void insertRule(long ruleSetId, RuleRecord rule) {
        jdbc.update("INSERT INTO leave_rule(rule_set_id,rule_id,priority,org_unit,hr_account_public_id,"
                + "cancellation_account_public_id) VALUES (?,?,?,?,?,?)",
            ruleSetId, rule.ruleId(), rule.priority(), rule.orgUnit(), rule.hrAccountPublicId(),
            rule.cancellationAccountPublicId());
    }

    @Override
    public Optional<Long> findRuleSetId(UUID publicId) {
        return jdbc.query("SELECT id FROM leave_rule_set WHERE public_id=?",
            (rs, n) -> rs.getLong(1), publicId).stream().findFirst();
    }

    @Override
    public void insertActivation(long ruleSetId, Instant activatedAt) {
        jdbc.update("INSERT INTO leave_rule_activation(rule_set_id,activated_at) VALUES (?,?)",
            ruleSetId, Timestamp.from(activatedAt));
    }

    @Override
    public void activateCurrent(long ruleSetId) {
        jdbc.update("INSERT INTO leave_rule_current(id,rule_set_id) VALUES (1,?) "
            + "ON CONFLICT(id) DO UPDATE SET rule_set_id=EXCLUDED.rule_set_id", ruleSetId);
    }

    @Override
    public List<RuleRecord> findRules(UUID ruleSetPublicId) {
        return jdbc.query("SELECT r.rule_id,r.priority,r.org_unit,r.hr_account_public_id,"
                + "r.cancellation_account_public_id FROM leave_rule r "
                + "JOIN leave_rule_set s ON s.id=r.rule_set_id WHERE s.public_id=? "
                + "ORDER BY r.priority DESC,r.id",
            (rs, n) -> new RuleRecord(rs.getString(1), rs.getInt(2), rs.getString(3),
                rs.getObject(4, UUID.class), rs.getObject(5, UUID.class)),
            ruleSetPublicId);
    }

    @Override
    public Optional<UUID> findCurrentRuleSetPublicId() {
        return jdbc.query("SELECT s.public_id FROM leave_rule_current c JOIN leave_rule_set s ON s.id=c.rule_set_id "
            + "WHERE c.id=1", (rs, n) -> rs.getObject(1, UUID.class)).stream().findFirst();
    }
}
