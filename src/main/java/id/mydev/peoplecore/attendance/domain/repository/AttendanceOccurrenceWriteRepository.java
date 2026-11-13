package id.mydev.peoplecore.attendance.domain.repository;

import java.time.Instant;
import java.time.LocalDate;

public interface AttendanceOccurrenceWriteRepository {
    void insertConflictSafe(Long employeeId, LocalDate workDate, OccurrenceValues effective, Instant created);

    record OccurrenceValues(Instant startAt, Instant endAt, String timezone,
        Long scheduleRevisionId, Long calendarRevisionId, Long scheduleAssignmentId,
        Long calendarAssignmentId, String holidayName) { }
}
