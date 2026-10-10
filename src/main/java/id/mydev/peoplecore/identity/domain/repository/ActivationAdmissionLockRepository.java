package id.mydev.peoplecore.identity.domain.repository;

import id.mydev.peoplecore.identity.domain.model.ActivationAdmissionLock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface ActivationAdmissionLockRepository extends JpaRepository<ActivationAdmissionLock, Integer> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select lock from ActivationAdmissionLock lock where lock.id = 1")
    Optional<ActivationAdmissionLock> lockSingleton();
}
