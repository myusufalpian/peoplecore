package id.mydev.peoplecore.attendance.application.command;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public final class WorkCalendarPayloads {

    private WorkCalendarPayloads() {
    }

    public record ScheduleDayPayload(int weekday, LocalTime startTime, LocalTime endTime, int overnightOffset) {
    }

    public record HolidayPayload(LocalDate holidayDay, String holidayName) {
    }

    public record PublishSchedulePayload(UUID publicId, String timezone, List<ScheduleDayPayload> days) {
    }

    public record PublishCalendarPayload(UUID publicId, String location, List<HolidayPayload> holidays) {
    }

    public record AssignmentPayload(UUID assignmentPublicId, UUID employeePublicId, UUID revisionPublicId,
                                    LocalDate fromDate, LocalDate toDate,
                                    UUID supersedesPublicId, String backdateReason,
                                    String expectedFingerprint) {
    }

    public record PreviewPayload(UUID scheduleRevisionPublicId, UUID calendarRevisionPublicId,
                                 LocalDate fromDate, LocalDate toDate,
                                 List<UUID> employeePublicIds, UUID supersedesPublicId,
                                 String fingerprint) {
    }
}
