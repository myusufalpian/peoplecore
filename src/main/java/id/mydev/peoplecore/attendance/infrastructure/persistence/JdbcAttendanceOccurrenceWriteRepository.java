package id.mydev.peoplecore.attendance.infrastructure.persistence;

import id.mydev.peoplecore.attendance.domain.model.AttendanceOccurrence;
import id.mydev.peoplecore.attendance.domain.repository.AttendanceOccurrenceRepository;
import id.mydev.peoplecore.attendance.domain.repository.AttendanceOccurrenceWriteRepository;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAttendanceOccurrenceWriteRepository implements AttendanceOccurrenceWriteRepository {
    private final AttendanceOccurrenceRepository occurrences;
    private final JdbcTemplate jdbc;
    private final EntityManager entities;
    private final boolean postgres;

    public JdbcAttendanceOccurrenceWriteRepository(AttendanceOccurrenceRepository occurrences,
        JdbcTemplate jdbc, EntityManager entities) {
        this.occurrences = occurrences;
        this.jdbc = jdbc;
        this.entities = entities;
        this.postgres = detectPostgres(jdbc);
    }

    public void insertConflictSafe(Long employeeId, LocalDate workDate, OccurrenceValues effective, Instant created) {
        UUID publicId = UUID.randomUUID();
        Timestamp createdAt = Timestamp.from(created);
        if (postgres) {
            jdbc.update("INSERT INTO attendance_occurrence(public_id, employee_id, work_date, "
                + "start_at, end_at, timezone, schedule_revision_id, calendar_revision_id, "
                + "schedule_assignment_id, calendar_assignment_id, holiday_name, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (employee_id, work_date) DO NOTHING",
                publicId, employeeId, workDate,
                Timestamp.from(effective.startAt()), Timestamp.from(effective.endAt()),
                effective.timezone(), effective.scheduleRevisionId(), effective.calendarRevisionId(),
                effective.scheduleAssignmentId(), effective.calendarAssignmentId(),
                effective.holidayName(), createdAt);
            entities.flush();
            entities.clear();
            return;
        }
        try {
            occurrences.saveAndFlush(new AttendanceOccurrence(publicId, employeeId, workDate,
                effective.startAt(), effective.endAt(), effective.timezone(),
                effective.scheduleRevisionId(), effective.calendarRevisionId(),
                effective.scheduleAssignmentId(), effective.calendarAssignmentId(),
                effective.holidayName(), createdAt.toInstant()));
        } catch (DataAccessException ex) {
            entities.clear();
        }
    }

    private static boolean detectPostgres(JdbcTemplate jdbc) {
        try {
            String product = jdbc.execute((Connection connection) ->
                connection.getMetaData().getDatabaseProductName());
            return "PostgreSQL".equals(product);
        } catch (DataAccessException ex) {
            return false;
        }
    }

}
