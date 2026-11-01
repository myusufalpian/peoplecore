package id.mydev.peoplecore.attendance.api.dto;

import id.mydev.peoplecore.attendance.application.service.AttendanceTimePolicy.Action;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class AttendanceRecordingDto {
    private AttendanceRecordingDto() { }
    public record ClockRequest(@NotNull Action action) { }
    public record CreateCorrectionRequest(@NotNull LocalDate workDate,@NotNull Instant proposedIn,
        Instant proposedOut,@NotBlank @Size(max=2000) String reason) { }
    public record CorrectionActionRequest(@NotNull @PositiveOrZero Long expectedVersion,UUID revisionId,
        UUID expectedEvidenceId,UUID reviewerId,@Size(max=2000) String reason,Instant proposedIn,Instant proposedOut) { }
}
