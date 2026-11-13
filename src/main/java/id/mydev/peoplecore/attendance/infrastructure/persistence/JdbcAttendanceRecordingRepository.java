package id.mydev.peoplecore.attendance.infrastructure.persistence;

import id.mydev.peoplecore.attendance.domain.repository.AttendanceRecordingRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAttendanceRecordingRepository implements AttendanceRecordingRepository {
    private final JdbcTemplate jdbc;

    public JdbcAttendanceRecordingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ClockEventRecord> findRecent(long employeeId, String action, Instant from, Instant to) {
        return jdbc.query("SELECT e.public_id, o.public_id AS occurrence_public_id, e.action, e.received_at, "
                + "e.accepted_at FROM clock_event e JOIN attendance_occurrence o ON o.id=e.occurrence_id "
                + "WHERE e.employee_id=? AND e.action=? AND e.accepted_at>=? AND e.accepted_at<=? "
                + "ORDER BY e.accepted_at DESC",
            (rs, row) -> new ClockEventRecord(rs.getObject("public_id", UUID.class),
                rs.getObject("occurrence_public_id", UUID.class), rs.getString("action"),
                rs.getTimestamp("received_at").toInstant(), rs.getTimestamp("accepted_at").toInstant()),
            employeeId, action, Timestamp.from(from), Timestamp.from(to));
    }

    @Override
    public long countOuts(UUID occurrencePublicId) {
        Long count = jdbc.queryForObject("SELECT count(e.id) FROM clock_event e "
            + "JOIN attendance_occurrence o ON o.id=e.occurrence_id WHERE o.public_id=? AND e.action='OUT'",
            Long.class, occurrencePublicId);
        return count == null ? 0L : count;
    }

    @Override
    public void insertClockEvent(UUID publicId, long occurrenceId, long employeeId, String action, Instant receivedAt,
                                 Instant acceptedAt) {
        jdbc.update("INSERT INTO clock_event(public_id,occurrence_id,employee_id,action,received_at,accepted_at) "
                + "VALUES(?,?,?,?,?,?)",
            publicId, occurrenceId, employeeId, action, Timestamp.from(receivedAt), Timestamp.from(acceptedAt));
    }

    @Override
    public Optional<EffectiveRecord> findCurrentEvidence(long occurrenceId) {
        return jdbc.query("SELECT public_id,in_at,out_at FROM attendance_effective_revision "
                + "WHERE occurrence_id=? ORDER BY id DESC LIMIT 1",
            (rs, row) -> new EffectiveRecord(rs.getObject("public_id", UUID.class),
                rs.getTimestamp("in_at").toInstant(),
                rs.getTimestamp("out_at") == null ? null : rs.getTimestamp("out_at").toInstant()),
            occurrenceId).stream().findFirst();
    }

    @Override
    public void insertEffective(UUID publicId, long occurrenceId, UUID predecessorId, Instant inAt, Instant outAt,
                                UUID sourceId, Instant createdAt) {
        jdbc.update("INSERT INTO attendance_effective_revision(public_id,occurrence_id,predecessor_id,"
                + "in_at,out_at,source_id,created_at) VALUES(?,?,?,?,?,?,?)",
            publicId, occurrenceId, predecessorId, Timestamp.from(inAt),
            outAt == null ? null : Timestamp.from(outAt), sourceId, Timestamp.from(createdAt));
    }
}
