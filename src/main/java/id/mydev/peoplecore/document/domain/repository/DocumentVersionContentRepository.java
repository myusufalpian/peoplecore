package id.mydev.peoplecore.document.domain.repository;

import java.util.Optional;
import java.util.UUID;

public interface DocumentVersionContentRepository {
    Optional<MetadataRecord> findMetadata(UUID documentPublicId, UUID versionPublicId, UUID employeePublicId,
                                          String purpose);

    record MetadataRecord(UUID attemptPublicId, String mediaType, long byteSize) { }
}
