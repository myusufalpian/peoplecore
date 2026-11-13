package id.mydev.peoplecore.attendance.domain.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AttendanceRecordingRepository {
    List<ClockEventRecord> findRecent(long employeeId, String action, Instant from, Instant to);

    long countOuts(UUID occurrencePublicId);

    void insertClockEvent(UUID publicId, long occurrenceId, long employeeId, String action, Instant receivedAt,
                          Instant acceptedAt);

    Optional<EffectiveRecord> findCurrentEvidence(long occurrenceId);

    void insertEffective(UUID publicId, long occurrenceId, UUID predecessorId, Instant inAt, Instant outAt,
                         UUID sourceId, Instant createdAt);

    record ClockEventRecord(UUID publicId, UUID occurrencePublicId, String action, Instant receivedAt,
                            Instant acceptedAt) { }

    record EffectiveRecord(UUID publicId, Instant inAt, Instant outAt) { }
}
