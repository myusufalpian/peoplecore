package id.mydev.peoplecore.leave.infrastructure.persistence;

import id.mydev.peoplecore.leave.domain.repository.LeaveEntitlementRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcLeaveEntitlementRepository implements LeaveEntitlementRepository {
    private final JdbcTemplate jdbc;

    public JdbcLeaveEntitlementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void lock(long employeeId, Instant now) {
        jdbc.update("INSERT INTO leave_account(employee_id,created_at) VALUES (?,?) ON CONFLICT(employee_id) DO NOTHING",
            employeeId, Timestamp.from(now));
        jdbc.queryForObject("SELECT employee_id FROM leave_account WHERE employee_id=? FOR UPDATE", Long.class,
            employeeId);
    }

    @Override
    public boolean lockExisting(long employeeId) {
        return !jdbc.query("SELECT employee_id FROM leave_account WHERE employee_id=? FOR UPDATE",
            (row, index) -> row.getLong(1), employeeId).isEmpty();
    }

    @Override
    public long period(long employeeId, int year) {
        jdbc.update("INSERT INTO leave_period(employee_id,period_year) VALUES (?,?) "
            + "ON CONFLICT(employee_id,period_year) DO NOTHING", employeeId, year);
        Long id = jdbc.queryForObject("SELECT id FROM leave_period WHERE employee_id=? AND period_year=? FOR UPDATE",
            Long.class, employeeId, year);
        if (id == null) {
            throw new IllegalStateException("Leave period is missing");
        }
        return id;
    }

    @Override
    public List<GrantRecord> findGrants(long periodId, String entryType, String businessReference) {
        return jdbc.query("SELECT half_units,source,reason,source_reference FROM leave_ledger "
                + "WHERE period_id=? AND entry_type=? AND business_reference=?",
            (row, index) -> new GrantRecord(row.getInt(1), row.getString(2), row.getString(3), row.getString(4)),
            periodId, entryType, businessReference);
    }

    @Override
    public void appendLedger(long periodId, String entryType, String businessReference, int halfUnits, UUID requestId,
                             Integer revision, Long originalEntryId, String source, String reason,
                             String sourceReference, Instant createdAt) {
        jdbc.update("INSERT INTO leave_ledger(public_id,period_id,entry_type,business_reference,half_units,"
                + "request_public_id,request_revision,original_entry_id,source,reason,source_reference,created_at) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
            UUID.randomUUID(), periodId, entryType, businessReference, halfUnits, requestId, revision,
            originalEntryId, source, reason, sourceReference, Timestamp.from(createdAt));
    }

    @Override
    public Optional<Long> ledgerEntryId(UUID entryPublicId, long employeeId, int year) {
        return jdbc.query("SELECT l.id FROM leave_ledger l JOIN leave_period p ON p.id=l.period_id "
                + "WHERE l.public_id=? AND p.employee_id=? AND p.period_year=?",
            (row, index) -> row.getLong(1), entryPublicId, employeeId, year).stream().findFirst();
    }

    @Override
    public List<LedgerRecord> findLedger(long employeeId, int year, long afterId) {
        return jdbc.query("SELECT l.public_id,l.entry_type,l.business_reference,l.source_reference,l.half_units,"
                + "l.source,l.reason,l.created_at FROM leave_ledger l JOIN leave_period p ON p.id=l.period_id "
                + "WHERE p.employee_id=? AND p.period_year=? AND l.id>? ORDER BY l.id LIMIT 100",
            (row, index) -> new LedgerRecord(row.getObject(1, UUID.class), row.getString(2), row.getString(3),
                row.getString(4), row.getInt(5), row.getString(6), row.getString(7),
                row.getTimestamp(8).toInstant()),
            employeeId, year, afterId);
    }

    @Override
    public List<Integer> findReservationUnits(long periodId, UUID requestId, int revision) {
        return jdbc.query("SELECT half_units FROM leave_reservation WHERE request_public_id=? "
                + "AND request_revision=? AND period_id=? AND active=TRUE",
            (row, index) -> row.getInt(1), requestId, revision, periodId);
    }

    @Override
    public void insertReservation(long periodId, UUID requestId, int revision, int halfUnits, Instant createdAt) {
        jdbc.update("INSERT INTO leave_reservation(period_id,request_public_id,request_revision,half_units,active,created_at) "
                + "VALUES (?,?,?,?,TRUE,?)", periodId, requestId, revision, halfUnits, Timestamp.from(createdAt));
    }

    @Override
    public void releaseReservation(long periodId, UUID requestId, int revision) {
        jdbc.update("UPDATE leave_reservation SET active=FALSE WHERE period_id=? AND request_public_id=? "
                + "AND request_revision=? AND active=TRUE", periodId, requestId, revision);
    }

    @Override
    public Optional<Long> findEntryId(long periodId, String entryType, String businessReference) {
        return jdbc.query("SELECT id FROM leave_ledger WHERE period_id=? AND entry_type=? AND business_reference=?",
            (row, index) -> row.getLong(1), periodId, entryType, businessReference).stream().findFirst();
    }

    @Override
    public Integer findEntryUnits(long entryId) {
        return jdbc.queryForObject("SELECT half_units FROM leave_ledger WHERE id=?", Integer.class, entryId);
    }

    @Override
    public BalanceRecord balance(long employeeId, int year) {
        return jdbc.query("SELECT COALESCE(SUM(CASE WHEN l.entry_type='OPENING' THEN l.half_units ELSE 0 END),0), "
                + "COALESCE(SUM(CASE WHEN l.entry_type='ADJUSTMENT' THEN l.half_units ELSE 0 END),0), "
                + "COALESCE(SUM(CASE WHEN l.entry_type='CONSUMPTION' THEN l.half_units "
                + "WHEN l.entry_type='REVERSAL' THEN -l.half_units ELSE 0 END),0), "
                + "(SELECT COALESCE(SUM(r.half_units),0) FROM leave_reservation r JOIN leave_period rp ON rp.id=r.period_id "
                + "WHERE rp.employee_id=? AND rp.period_year=? AND r.active=TRUE) "
                + "FROM leave_period p LEFT JOIN leave_ledger l ON l.period_id=p.id WHERE p.employee_id=? AND p.period_year=?",
            (row, index) -> new BalanceRecord(row.getLong(1), row.getLong(2), row.getLong(3), row.getLong(4)),
            employeeId, year, employeeId, year).getFirst();
    }
}
