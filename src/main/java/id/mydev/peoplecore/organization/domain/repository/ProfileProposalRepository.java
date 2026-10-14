package id.mydev.peoplecore.organization.domain.repository;

import id.mydev.peoplecore.organization.domain.model.ProfileProposal;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProfileProposalRepository extends JpaRepository<ProfileProposal, Long> {
    Optional<ProfileProposal> findByPublicId(UUID publicId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select proposal from ProfileProposal proposal where proposal.id = :id")
    Optional<ProfileProposal> lockById(Long id);

    @Query("select proposal from ProfileProposal proposal where proposal.employeeId = :employeeId"
        + " and proposal.state in ('DRAFT', 'PENDING', 'RETURNED')")
    Optional<ProfileProposal> findOpenByEmployeeId(Long employeeId);

    List<ProfileProposal> findByEmployeeIdOrderByCreatedAtDesc(Long employeeId);

    @Query(value = "select proposal.* from profile_proposal proposal"
        + " join lateral (select assignment.org_unit from employee_assignments assignment"
        + " where assignment.employee_id = proposal.employee_id and assignment.superseded_at is null"
        + " and assignment.valid_from <= now() and (assignment.valid_to is null or now() < assignment.valid_to)"
        + " order by assignment.valid_from desc limit 1) current_unit on true"
        + " where proposal.state = 'PENDING' and current_unit.org_unit in :units"
        + " order by proposal.created_at asc limit :limit offset :offset", nativeQuery = true)
    List<ProfileProposal> findPendingQueueScoped(@Param("units") List<String> units,
                                                 @Param("limit") int limit,
                                                 @Param("offset") int offset);

    @Query(value = "select count(*) from profile_proposal proposal"
        + " join lateral (select assignment.org_unit from employee_assignments assignment"
        + " where assignment.employee_id = proposal.employee_id and assignment.superseded_at is null"
        + " and assignment.valid_from <= now() and (assignment.valid_to is null or now() < assignment.valid_to)"
        + " order by assignment.valid_from desc limit 1) current_unit on true"
        + " where proposal.state = 'PENDING' and current_unit.org_unit in :units", nativeQuery = true)
    long countPendingQueueScoped(@Param("units") List<String> units);

    @Query(value = "select proposal.* from profile_proposal proposal"
        + " where proposal.state = 'PENDING'"
        + " order by proposal.created_at asc limit :limit offset :offset", nativeQuery = true)
    List<ProfileProposal> findPendingQueueAll(@Param("limit") int limit,
                                              @Param("offset") int offset);

    @Query(value = "select count(*) from profile_proposal proposal where proposal.state = 'PENDING'",
        nativeQuery = true)
    long countPendingQueueAll();
}
