package id.mydev.peoplecore.attendance.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttendanceDomainModelTest {

    static final Instant NOW = Instant.parse("2026-10-08T10:00:00Z");

    @Test
    void scheduleDayRejectsInvalidShapes() {
        assertThrows(IllegalArgumentException.class,
            () -> new WorkScheduleDay(1L, 0, LocalTime.of(9, 0), LocalTime.of(17, 0), 0));
        assertThrows(IllegalArgumentException.class,
            () -> new WorkScheduleDay(1L, 8, LocalTime.of(9, 0), LocalTime.of(17, 0), 0));
        assertThrows(IllegalArgumentException.class,
            () -> new WorkScheduleDay(1L, 1, LocalTime.of(17, 0), LocalTime.of(9, 0), 0));
        assertThrows(IllegalArgumentException.class,
            () -> new WorkScheduleDay(1L, 7, LocalTime.of(9, 0), LocalTime.of(17, 0), 1));
        assertThrows(IllegalArgumentException.class,
            () -> new WorkScheduleDay(1L, 1, LocalTime.of(9, 0), LocalTime.of(17, 0), 2));
        WorkScheduleDay overnight =
            new WorkScheduleDay(1L, 7, LocalTime.of(22, 0), LocalTime.of(6, 0), 1);
        assertEquals(1, overnight.getOvernightOffset());
    }

    @Test
    void revisionsPublishOnce() {
        WorkScheduleRevision schedule = new WorkScheduleRevision(UUID.randomUUID(), "Asia/Jakarta", NOW);
        assertFalse(schedule.isPublished());
        schedule.publish(NOW);
        assertTrue(schedule.isPublished());
        assertThrows(IllegalStateException.class, () -> schedule.publish(NOW));

        HolidayCalendarRevision calendar = new HolidayCalendarRevision(UUID.randomUUID(), "JKT", NOW);
        assertFalse(calendar.isPublished());
        calendar.publish(NOW);
        assertTrue(calendar.isPublished());
        assertThrows(IllegalStateException.class, () -> calendar.publish(NOW));

        assertThrows(IllegalArgumentException.class,
            () -> new HolidayDate(1L, LocalDate.of(2026, 10, 12), " "));
    }

    @Test
    void scheduleAssignmentCoversHalfOpenInterval() {
        EmployeeScheduleAssignment assignment = new EmployeeScheduleAssignment(UUID.randomUUID(), 7L, 9L,
            LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 15), NOW, null, null);
        assertTrue(assignment.covers(LocalDate.of(2026, 10, 8)));
        assertTrue(assignment.covers(LocalDate.of(2026, 10, 14)));
        assertFalse(assignment.covers(LocalDate.of(2026, 10, 7)));
        assertFalse(assignment.covers(LocalDate.of(2026, 10, 15)));
        assertTrue(assignment.overlaps(LocalDate.of(2026, 10, 10), null));
        assertFalse(assignment.overlaps(LocalDate.of(2026, 10, 15), null));
        assertFalse(assignment.overlaps(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 8)));
        assertTrue(assignment.overlaps(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 9)));
        assertThrows(IllegalArgumentException.class,
            () -> new EmployeeScheduleAssignment(UUID.randomUUID(), 7L, 9L,
                LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 15), NOW, null, null));
        assertThrows(IllegalArgumentException.class,
            () -> assignment.overlaps(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 10)));
        UUID predecessor = UUID.randomUUID();
        EmployeeScheduleAssignment replacement = new EmployeeScheduleAssignment(UUID.randomUUID(), 7L, 9L,
            LocalDate.of(2026, 10, 15), null, NOW, predecessor, "Pattern change");
        assertEquals(predecessor, replacement.getSupersedesPublicId());
        assertEquals("Pattern change", replacement.getBackdateReason());
        assignment.supersede("pattern change");
        assertTrue(assignment.isSuperseded());
        assertNull(assignment.getSupersedesPublicId());
        assertEquals("pattern change", assignment.getBackdateReason());
        assertFalse(assignment.covers(LocalDate.of(2026, 10, 10)));
        assertFalse(assignment.overlaps(LocalDate.of(2026, 10, 10), null));
        assertThrows(IllegalStateException.class, () -> assignment.supersede(null));
    }

    @Test
    void calendarAssignmentCoversHalfOpenInterval() {
        EmployeeCalendarAssignment assignment = new EmployeeCalendarAssignment(UUID.randomUUID(), 7L, 9L,
            LocalDate.of(2026, 10, 8), null, NOW, null, null);
        assertTrue(assignment.covers(LocalDate.of(2027, 1, 1)));
        assertFalse(assignment.covers(LocalDate.of(2026, 10, 7)));
        assertTrue(assignment.overlaps(LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 9)));
        assertFalse(assignment.overlaps(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 8)));
        assignment.supersede(null);
        assertTrue(assignment.isSuperseded());
        assertFalse(assignment.covers(LocalDate.of(2026, 10, 10)));
        assertFalse(assignment.overlaps(LocalDate.of(2026, 10, 10), null));
        assertThrows(IllegalStateException.class, () -> assignment.supersede(null));
    }

    @Test
    void occurrenceRejectsInvalidShapes() {
        UUID publicId = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new AttendanceOccurrence(publicId, 1L,
            LocalDate.of(2026, 10, 12), Instant.parse("2026-10-12T10:00:00Z"),
            Instant.parse("2026-10-12T02:00:00Z"), "Asia/Jakarta", 3L, 4L, 5L, 6L, null, NOW));
        assertThrows(IllegalArgumentException.class, () -> new AttendanceOccurrence(publicId, 1L,
            LocalDate.of(2026, 10, 12), Instant.parse("2026-10-12T02:00:00Z"),
            Instant.parse("2026-10-12T10:00:00Z"), " ", 3L, 4L, 5L, 6L, null, NOW));
        AttendanceOccurrence plain = new AttendanceOccurrence(publicId, 1L,
            LocalDate.of(2026, 10, 12), Instant.parse("2026-10-12T02:00:00Z"),
            Instant.parse("2026-10-12T10:00:00Z"), "Asia/Jakarta", 3L, null, 5L, null, null, NOW);
        assertFalse(plain.isHoliday());
        AttendanceOccurrence holiday = new AttendanceOccurrence(UUID.randomUUID(), 1L,
            LocalDate.of(2026, 10, 12), Instant.parse("2026-10-12T02:00:00Z"),
            Instant.parse("2026-10-12T10:00:00Z"), "Asia/Jakarta", 3L, 4L, 5L, 6L, "Cuti", NOW);
        assertTrue(holiday.isHoliday());
        assertEquals("Cuti", holiday.getHolidayName());
    }
}
