package id.mydev.peoplecore.organization.domain.repository;

import id.mydev.peoplecore.organization.domain.model.ProfileAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;

public interface ProfileAssignmentRepository extends JpaRepository<ProfileAssignment, Long> {
    List<ProfileAssignment> findByProposalIdOrderByIdAsc(Long proposalId, Pageable pageable);
    long countByProposalId(Long proposalId);
    Optional<ProfileAssignment> findByPublicId(UUID publicId);

    @Query("select assignment from ProfileAssignment assignment"
        + " where assignment.proposalId = :proposalId and assignment.status = 'ACTIVE'")
    Optional<ProfileAssignment> findActiveByProposalId(Long proposalId);

    Optional<ProfileAssignment> findFirstByProposalIdOrderByIdDesc(Long proposalId);

    List<ProfileAssignment> findByProposalIdOrderByCreatedAtAsc(Long proposalId);
}
