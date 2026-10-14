package id.mydev.peoplecore.organization.domain.repository;

import id.mydev.peoplecore.organization.domain.model.ProfileRevision;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProfileRevisionRepository extends JpaRepository<ProfileRevision, Long> {
    Optional<ProfileRevision> findByPublicId(UUID publicId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select revision from ProfileRevision revision where revision.id = :id")
    Optional<ProfileRevision> lockById(Long id);

    List<ProfileRevision> findByProposalIdOrderByRevisionNumberAsc(Long proposalId);

    List<ProfileRevision> findByProposalIdOrderByRevisionNumberAsc(Long proposalId, Pageable pageable);

    long countByProposalId(Long proposalId);

    @Query("select coalesce(max(revision.revisionNumber), 0) from ProfileRevision revision"
        + " where revision.proposalId = :proposalId")
    int findMaxRevisionNumberByProposalId(@Param("proposalId") Long proposalId);

    Optional<ProfileRevision> findByProposalIdAndRevisionNumber(Long proposalId, int revisionNumber);
}
