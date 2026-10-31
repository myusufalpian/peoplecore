package id.mydev.peoplecore.attendance.application.command;

import id.mydev.peoplecore.attendance.application.service.AttendanceTimePolicy.Action;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class AttendancePayloads {
    private AttendancePayloads() { }
    public record ClockPayload(Action action) { }
    public record CorrectionPayload(UUID publicId, LocalDate workDate, Instant proposedIn,
                                    Instant proposedOut, String reason) { }
    public record CorrectionAction(UUID correctionId, long expectedVersion, UUID revisionId,
                                   UUID expectedEvidenceId, UUID reviewerId, String reason,
                                   Instant proposedIn, Instant proposedOut) { }
}
