package id.mydev.peoplecore.document.domain.repository;

import id.mydev.peoplecore.document.domain.exception.DocumentVerificationException;
import java.util.Optional;
import java.util.UUID;

/** State conflicts use {@link DocumentVerificationException}; the worker abandons the attempt before propagating them. */
public interface DocumentVerificationRepository {
    boolean recover(long retryDelayMillis);
    Optional<Attempt> claim(UUID token, long leaseSeconds);
    void finalizeVersion(Attempt attempt, UUID token, VerifiedContent content);
    void finishFailure(Attempt attempt, UUID token, boolean rejected);

    record Attempt(long id, UUID attemptId, long documentId, long generation, UUID uploadId,
                   String mediaType, long size) { }
    record VerifiedContent(String mediaType, long byteSize, String sha256, String scannerVersion) { }
}
