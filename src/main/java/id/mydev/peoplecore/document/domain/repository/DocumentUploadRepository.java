package id.mydev.peoplecore.document.domain.repository;

import id.mydev.peoplecore.document.domain.model.Purpose;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface DocumentUploadRepository {
    void ensureAccount(long employeeId, Instant now);

    void lockAccount(long employeeId);

    void insertDocument(UUID documentPublicId, long employeeId, Purpose purpose, String fileName,
                        String mediaType, long byteSize, Instant now);

    Optional<PendingDocumentRecord> findDocument(UUID documentPublicId, boolean lock);

    boolean abandonReservedUpload(UUID uploadPublicId);

    long advanceUploadGeneration(long documentId, Instant now);

    long insertUploadAttempt(UUID uploadPublicId, long documentId, long generation, String stagingKey,
                             long byteSize, Instant now);

    void attachUpload(long documentId, long uploadId);

    boolean completeUpload(UUID uploadPublicId, String etag, Instant now);

    long advanceVerificationGeneration(long documentId, Instant now);

    void queueVerificationAttempt(UUID attemptPublicId, long documentId, long generation, String candidateKey,
                                  Instant now);

    Optional<MetadataRecord> findMetadata(UUID documentPublicId);

    record PendingDocumentRecord(long id, UUID publicId, long employeeId, UUID employeePublicId, Purpose purpose,
                                 String status, String deletionState, String mediaType, long byteSize,
                                 UUID uploadPublicId) { }

    record MetadataRecord(UUID publicId, Purpose purpose, String status, String mediaType, Long byteSize,
                          Instant createdAt, Instant verifiedAt) { }
}
