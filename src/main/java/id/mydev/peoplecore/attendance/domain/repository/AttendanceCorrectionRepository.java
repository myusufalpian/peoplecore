package id.mydev.peoplecore.attendance.domain.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AttendanceCorrectionRepository {
    Long countOpen(long employeeId, LocalDate workDate);
    void create(UUID publicId, long employeeId, UUID employeePublicId, LocalDate workDate, Instant proposedIn,
        Instant proposedOut, String reason, Instant createdAt);
    void edit(Instant proposedIn, Instant proposedOut, String reason, long id);
    void insertRevision(UUID publicId, long correctionId, long occurrenceId, UUID baselineId, Instant proposedIn,
        Instant proposedOut, String reason, UUID assignmentId, String orgUnit, Long reviewerId, Instant createdAt);
    void markPending(UUID revisionId, long id);
    List<Submitted> findSubmitted(UUID revisionId);
    void insertDecision(UUID publicId, UUID revisionId, UUID assignmentId, long reviewerId, String status,
        String reason, Instant createdAt);
    void incrementVersion(long id);
    CorrectionRows list(long employeeId, LocalDate from, LocalDate to, String status, boolean owner, boolean hr,
        long reviewerId, int page, int size);
    record CorrectionRows(List<Correction> rows, long total) { }
    List<HistoryRecord> history(String kind, long correctionId, UUID after, int size);
    record HistoryRecord(UUID publicId, String kind, Instant proposedIn, Instant proposedOut, String reason,
        String decision, Instant createdAt) { }
    Optional<Assignment> assignment(UUID revision);
    void assign(UUID revision,Long reviewer,UUID previous,String reason,Instant now);
    Correction find(UUID id);
    void changeState(Correction current,String state);
    record Assignment(UUID publicId,Long reviewerId) { }
    record Submitted(Long occurrenceId,UUID baselineId,Instant proposedIn,Instant proposedOut) { }
    record Correction(Long id,UUID publicId,Long employeeId,UUID employeePublicId,LocalDate workDate,
        String status,Instant proposedIn,Instant proposedOut,String reason,long version,UUID revisionId) { }
    UUID findBaseline(UUID revisionId);
}
