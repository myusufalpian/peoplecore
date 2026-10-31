package id.mydev.peoplecore.attendance.domain.repository;

import id.mydev.peoplecore.attendance.domain.model.AttendanceOccurrence;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AttendanceOccurrenceRepository extends JpaRepository<AttendanceOccurrence, Long> {
    Optional<AttendanceOccurrence> findByEmployeeIdAndWorkDate(Long employeeId, LocalDate workDate);

    List<AttendanceOccurrence> findByEmployeeIdAndWorkDateBetweenOrderByWorkDateAsc(
        Long employeeId, LocalDate from, LocalDate to);

    List<AttendanceOccurrence> findByEmployeeIdAndWorkDateGreaterThanEqualOrderByWorkDateAsc(
        Long employeeId, LocalDate from);
}
