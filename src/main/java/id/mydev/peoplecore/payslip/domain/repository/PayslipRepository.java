package id.mydev.peoplecore.payslip.domain.repository;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PayslipRepository {
    long insertBatch(UUID publicId, long createdBy);

    long insertRevision(long batchId, long revisionNumber);

    void insertItem(long revisionId, long employeeId, LocalDate periodStart, long documentId, long documentVersionId);

    void setCurrentRevision(long batchId, long revisionId);

    void setCurrentRevisionDraft(long batchId, long revisionId);

    void reviewRevision(long revisionId, long reviewedBy);

    void markReviewed(long batchId);

    void markPublished(long batchId);

    List<PublicationItemRecord> findPublicationItems(long revisionId);

    long insertPublication(UUID publicId, long revisionId, long itemId, long employeeId, LocalDate periodStart,
                           long publishedBy);

    void upsertCurrent(long employeeId, LocalDate periodStart, long publicationId);

    List<PublishedRecord> findPublished(UUID employeePublicId, UUID cursor, int limitPlusOne);

    Optional<VersionRecord> findPublishedVersion(UUID publicationId, UUID employeePublicId);

    Long findItemVersion(long revisionId, UUID documentPublicId, LocalDate periodStart);

    void lockAccount(long employeeId);

    Optional<SnapshotRecord> findSnapshot(UUID publicId, boolean lock);

    List<ItemRecord> findItems(long revisionId);

    record PublicationItemRecord(long id, long employeeId, LocalDate periodStart, UUID employeePublicId) { }

    record PublishedRecord(UUID publicId, YearMonth period) { }

    record VersionRecord(UUID documentPublicId, UUID versionPublicId) { }

    record SnapshotRecord(long batchId, String status, long revisionId, long revision) { }

    record ItemRecord(UUID employeePublicId, YearMonth period, UUID documentPublicId) { }
}
