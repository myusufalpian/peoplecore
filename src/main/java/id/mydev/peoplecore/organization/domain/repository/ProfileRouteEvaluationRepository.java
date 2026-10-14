package id.mydev.peoplecore.organization.domain.repository;

import id.mydev.peoplecore.organization.domain.model.ProfileRouteEvaluation;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProfileRouteEvaluationRepository extends JpaRepository<ProfileRouteEvaluation, Long> {
    List<ProfileRouteEvaluation> findByProposalIdOrderByEvaluatedAtDescIdDesc(Long proposalId, org.springframework.data.domain.Pageable pageable);
    long countByProposalId(Long proposalId);
    List<ProfileRouteEvaluation> findByProposalIdOrderByEvaluatedAtAsc(Long proposalId);

    List<ProfileRouteEvaluation> findByRevisionIdIn(List<Long> revisionIds);

    List<ProfileRouteEvaluation> findByRevisionIdInOrderByEvaluatedAtDesc(List<Long> revisionIds,
                                                                          Pageable pageable);

    long countByRevisionIdIn(List<Long> revisionIds);
}
