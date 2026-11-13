package id.mydev.peoplecore.document.domain.repository;

import java.util.Optional;
import java.util.UUID;

public interface VerifiedDocumentRepository {
    Optional<VerifiedVersion> findForUpdate(UUID documentPublicId, UUID employeePublicId, String purpose);
    Optional<Long> findId(UUID documentPublicId);
    record VerifiedVersion(long documentId, long versionId, UUID documentPublicId, UUID versionPublicId) { }
}
