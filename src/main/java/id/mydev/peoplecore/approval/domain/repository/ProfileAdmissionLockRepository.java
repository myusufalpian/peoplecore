package id.mydev.peoplecore.approval.domain.repository;

import id.mydev.peoplecore.common.command.AdmissionGuard.Mode;

public interface ProfileAdmissionLockRepository {
    void acquire(Mode mode);
}
