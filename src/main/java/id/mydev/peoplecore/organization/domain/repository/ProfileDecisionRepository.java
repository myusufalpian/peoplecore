package id.mydev.peoplecore.organization.domain.repository;

import id.mydev.peoplecore.organization.domain.model.ProfileDecision;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProfileDecisionRepository extends JpaRepository<ProfileDecision, Long> {
    List<ProfileDecision> findByProposalIdOrderByCreatedAtDescIdDesc(Long proposalId, org.springframework.data.domain.Pageable pageable);
    long countByProposalId(Long proposalId);
    List<ProfileDecision> findByProposalIdOrderByCreatedAtAsc(Long proposalId);

    List<ProfileDecision> findByRevisionIdIn(List<Long> revisionIds);

    List<ProfileDecision> findByRevisionIdInOrderByCreatedAtDesc(List<Long> revisionIds,
                                                                  Pageable pageable);

    long countByRevisionIdIn(List<Long> revisionIds);
}
