package id.mydev.peoplecore.attendance.domain.repository;

public interface AttendanceAccountLockRepository {
    void ensureLocked(Long employeeId);
}
