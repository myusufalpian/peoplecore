package id.mydev.peoplecore.document.domain.repository;

import java.util.Optional;
import java.util.UUID;

public interface DocumentContentRepository {
    Optional<TargetRecord> findOwnedVerifiedClaimEvidence(UUID documentPublicId, UUID employeePublicId);

    record TargetRecord(UUID documentPublicId, UUID versionPublicId, UUID employeePublicId, String mediaType) { }
}
