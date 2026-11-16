package id.mydev.peoplecore.identity.infrastructure.persistence;

import id.mydev.peoplecore.identity.domain.repository.AuthorizationFenceRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.Assert;

@Repository
public class JdbcAuthorizationFenceRepository implements AuthorizationFenceRepository {
    private final JdbcTemplate jdbc;

    public JdbcAuthorizationFenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long generation(boolean sharedLock) {
        Assert.state(TransactionSynchronizationManager.isActualTransactionActive(), "Authorization fence requires a transaction");
        Long value = jdbc.queryForObject("SELECT generation FROM auth_generation WHERE id=1"
            + (sharedLock ? " FOR SHARE" : ""), Long.class);
        if (value == null) throw new IllegalStateException("Authorization generation is unavailable");
        return value;
    }

    @Override
    public Instant nextBoundary(Instant at) {
        OffsetDateTime boundary = jdbc.queryForObject("""
            SELECT min(boundary) FROM (
              SELECT effective_from AS boundary FROM role_assignments
              UNION ALL SELECT effective_to FROM role_assignments
              UNION ALL SELECT valid_from FROM employee_assignments WHERE superseded_at IS NULL
              UNION ALL SELECT valid_to FROM employee_assignments WHERE superseded_at IS NULL
              UNION ALL SELECT access_ends_at FROM user_accounts
              UNION ALL SELECT (employment_start_date::timestamp AT TIME ZONE 'Asia/Jakarta') FROM employees
              UNION ALL SELECT ((employment_end_date+1)::timestamp AT TIME ZONE 'Asia/Jakarta') FROM employees
              UNION ALL SELECT (from_date::timestamp AT TIME ZONE 'Asia/Jakarta') FROM employee_schedule_assignment WHERE NOT superseded
              UNION ALL SELECT (to_date::timestamp AT TIME ZONE 'Asia/Jakarta') FROM employee_schedule_assignment WHERE NOT superseded
              UNION ALL SELECT (from_date::timestamp AT TIME ZONE 'Asia/Jakarta') FROM employee_calendar_assignment WHERE NOT superseded
              UNION ALL SELECT (to_date::timestamp AT TIME ZONE 'Asia/Jakarta') FROM employee_calendar_assignment WHERE NOT superseded
            ) boundaries WHERE boundary > ?
            """, (rs, row) -> rs.getObject(1, OffsetDateTime.class), at.atOffset(ZoneOffset.UTC));
        return boundary == null ? null : boundary.toInstant();
    }
}
