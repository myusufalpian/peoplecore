package id.mydev.peoplecore.attendance.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public final class WorkCalendarDto {

    private WorkCalendarDto() {
    }

    public record ScheduleDayRequest(
        @Min(1) @Max(7) int weekday,
        @NotNull LocalTime startTime,
        @NotNull LocalTime endTime,
        @Min(0) @Max(1) int overnightOffset
    ) {
    }

    public record PublishScheduleRequest(
        @Size(max = 64) String timezone,
        @NotEmpty @Size(max = 7) List<@Valid ScheduleDayRequest> days
    ) {
    }

    public record HolidayRequest(
        @NotNull LocalDate holidayDay,
        @NotBlank @Size(max = 256) String holidayName
    ) {
    }

    public record PublishCalendarRequest(
        @NotBlank @Size(max = 128) String location,
        @NotNull List<@Valid HolidayRequest> holidays
    ) {
    }

    public record AssignmentRequest(
        @NotNull UUID revisionId,
        @NotNull LocalDate fromDate,
        LocalDate toDate,
        UUID supersedesId,
        @Size(max = 1000) String backdateReason,
        @NotBlank @Size(max = 128) String expectedFingerprint
    ) {
    }

    public record PreviewRequest(
        UUID scheduleRevisionId,
        UUID calendarRevisionId,
        @NotNull LocalDate fromDate,
        @NotNull LocalDate toDate,
        @NotEmpty @Size(max = 500) List<@NotNull UUID> employeeIds,
        UUID supersedesId,
        String fingerprint
    ) {
    }

    public record RevisionResponse(
        UUID id,
        String status,
        Instant publishedAt
    ) {
    }

    public record AssignmentResponse(
        UUID id,
        UUID employeeId,
        UUID revisionId,
        LocalDate fromDate,
        LocalDate toDate
    ) {
    }

    public record ImpactPreviewResponse(
        List<UUID> affectedEmployees,
        long affectedDates,
        long pinnedOccurrences,
        String fingerprint
    ) {
    }

    public record OccurrenceResponse(
        UUID id,
        UUID employeeId,
        LocalDate workDate,
        Instant startAt,
        Instant endAt,
        String timezone,
        boolean holiday,
        String holidayName,
        boolean pinned,
        String blockedReason
    ) {
    }

    public record OccurrenceListResponse(
        List<OccurrenceResponse> occurrences
    ) {
    }
}
