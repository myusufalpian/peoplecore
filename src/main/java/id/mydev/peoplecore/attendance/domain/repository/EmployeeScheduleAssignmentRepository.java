package id.mydev.peoplecore.attendance.domain.repository;

import id.mydev.peoplecore.attendance.domain.model.EmployeeScheduleAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EmployeeScheduleAssignmentRepository extends JpaRepository<EmployeeScheduleAssignment, Long> {
    List<EmployeeScheduleAssignment> findByEmployeeIdAndSupersededFalse(Long employeeId);

    @Query("select a from EmployeeScheduleAssignment a where a.employeeId = :employeeId and a.superseded = false "
        + "and a.fromDate < :toDate and (a.toDate is null or a.toDate > :fromDate)")
    List<EmployeeScheduleAssignment> findOverlapping(Long employeeId, java.time.LocalDate fromDate, java.time.LocalDate toDate);

    Optional<EmployeeScheduleAssignment> findByPublicId(UUID publicId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from EmployeeScheduleAssignment a where a.id = :id")
    Optional<EmployeeScheduleAssignment> lockById(Long id);
}
