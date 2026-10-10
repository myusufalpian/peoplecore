package id.mydev.peoplecore.organization.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import id.mydev.peoplecore.organization.domain.model.EmployeeAssignment;
public interface EmployeeAssignmentRepository extends JpaRepository<EmployeeAssignment, Long> {
    List<EmployeeAssignment> findByEmployeeIdOrderByValidFromAsc(Long employeeId);

    List<EmployeeAssignment> findByEmployeeIdAndSupersededAtIsNullOrderByValidFromAsc(Long employeeId);

    @Query("""
        select new id.mydev.peoplecore.organization.domain.repository.AssignmentHistoryRow(
            a.publicId, a.orgUnit, a.jobLevel, a.validFrom, a.validTo,
            m.publicId, a.supersededAt, a.supersedesId)
        from EmployeeAssignment a left join Employee m on m.id = a.managerEmployeeId
        where a.employeeId = :employeeId
        order by a.validFrom, a.id
        """)
    List<AssignmentHistoryRow> findHistory(@Param("employeeId") Long employeeId);

    Optional<EmployeeAssignment> findByPublicId(UUID publicId);
}
