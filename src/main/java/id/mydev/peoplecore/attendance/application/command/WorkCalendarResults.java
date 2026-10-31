package id.mydev.peoplecore.attendance.application.command;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class WorkCalendarResults {

    private WorkCalendarResults() {
    }

    public record RevisionResult(UUID publicId, String status, Instant publishedAt) {
    }

    public record AssignmentResult(UUID publicId, UUID employeePublicId, UUID revisionPublicId,
                                   LocalDate fromDate, LocalDate toDate) {
    }

    public record ImpactPreview(List<UUID> affectedEmployees, long affectedDates, long pinnedOccurrences,
                                String fingerprint) {
    }

    public record OccurrenceView(UUID publicId, UUID employeePublicId, LocalDate workDate,
                                 Instant startAt, Instant endAt, String timezone,
                                 boolean holiday, String holidayName,
                                 boolean pinned, String blockedReason) {
    }

    public record OccurrenceListResult(List<OccurrenceView> occurrences) {
    }
}
