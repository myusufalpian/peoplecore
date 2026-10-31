package id.mydev.peoplecore.attendance.domain.repository;

import id.mydev.peoplecore.attendance.domain.model.AttendanceAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;

import java.util.Optional;

public interface AttendanceAccountRepository extends JpaRepository<AttendanceAccount, Long> {
    Optional<AttendanceAccount> findByEmployeeId(Long employeeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AttendanceAccount a where a.employeeId = :employeeId")
    Optional<AttendanceAccount> lockByEmployeeId(Long employeeId);
}
