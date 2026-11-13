package id.mydev.peoplecore.attendance.application.service;

import id.mydev.peoplecore.attendance.domain.repository.AttendanceAccountLockRepository;
import org.springframework.stereotype.Component;

@Component
public class AttendanceAccountGuard {
    private final AttendanceAccountLockRepository accounts;

    public AttendanceAccountGuard(AttendanceAccountLockRepository accounts) {
        this.accounts = accounts;
    }

    public void ensureLocked(Long employeeId) {
        accounts.ensureLocked(employeeId);
    }
}
