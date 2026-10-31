package id.mydev.peoplecore.attendance.domain.repository;

import id.mydev.peoplecore.attendance.domain.model.EmployeeCalendarAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EmployeeCalendarAssignmentRepository extends JpaRepository<EmployeeCalendarAssignment, Long> {
    List<EmployeeCalendarAssignment> findByEmployeeIdAndSupersededFalse(Long employeeId);

    @Query("select a from EmployeeCalendarAssignment a where a.employeeId = :employeeId and a.superseded = false "
        + "and a.fromDate < :toDate and (a.toDate is null or a.toDate > :fromDate)")
    List<EmployeeCalendarAssignment> findOverlapping(Long employeeId, java.time.LocalDate fromDate, java.time.LocalDate toDate);

    Optional<EmployeeCalendarAssignment> findByPublicId(UUID publicId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from EmployeeCalendarAssignment a where a.id = :id")
    Optional<EmployeeCalendarAssignment> lockById(Long id);
}
