package id.mydev.peoplecore.attendance.application.command;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class AttendanceResults {
    private AttendanceResults() { }
    public record ClockResult(UUID publicId, UUID employeePublicId, UUID occurrencePublicId,
                              String action, Instant receivedAt, Instant acceptedAt, boolean duplicate) { }
    public record EffectiveResult(UUID publicId, Instant inAt, Instant outAt) { }
    public record CorrectionResult(UUID publicId, UUID employeePublicId, LocalDate workDate,
                                   String status, long version, UUID revisionId, String blockedReason) { }
    public record EvidenceHistory(UUID occurrencePublicId, List<ClockResult> originalEvents,
                                  List<EffectiveResult> effectiveRevisions) { }
}
