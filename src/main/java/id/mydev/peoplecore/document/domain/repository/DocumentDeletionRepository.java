package id.mydev.peoplecore.document.domain.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentDeletionRepository {
    Optional<TargetRecord> findDocument(UUID publicId);

    boolean hasActiveHold(long documentId);

    boolean underRetention(long documentId);

    boolean claim(long documentId, Instant requestedAt, Instant restoreDeadline);

    boolean restore(long documentId, Instant restoredAt);

    List<Long> findExpired(Instant now, int limit);

    List<String> candidateKeys(long documentId);

    boolean markPurged(long documentId, Instant purgedAt);

    boolean readyForObjectPurge(long documentId);

    boolean completeObjectPurge(long documentId, Instant completedAt);

    void recordPurgeAudit(long documentId, Instant purgedAt);

    record TargetRecord(long id, UUID publicId, UUID employeePublicId, String deletionState,
                        Instant requestedAt, Instant restoreDeadline) { }
}
