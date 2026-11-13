package id.mydev.peoplecore.document.domain.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentHoldRepository {
    Optional<DocumentRecord> findDocument(UUID documentPublicId);

    Optional<DocumentRecord> lockDocument(UUID documentPublicId);

    void insert(UUID holdPublicId, long documentId, String reason, long createdBy);

    Optional<HoldRecord> findHold(UUID holdPublicId);

    List<HoldRecord> findActive(UUID documentPublicId);

    boolean release(UUID holdPublicId, String releaseReason);

    record DocumentRecord(long id, long employeeId, String deletionState) { }

    record HoldRecord(UUID publicId, UUID documentPublicId, String reason, Instant createdAt, Instant releasedAt) { }
}
