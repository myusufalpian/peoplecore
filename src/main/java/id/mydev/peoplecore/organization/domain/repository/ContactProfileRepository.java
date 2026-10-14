package id.mydev.peoplecore.organization.domain.repository;

import id.mydev.peoplecore.organization.domain.model.ContactProfile;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface ContactProfileRepository extends JpaRepository<ContactProfile, Long> {
    Optional<ContactProfile> findByEmployeeId(Long employeeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select profile from ContactProfile profile where profile.employeeId = :employeeId")
    Optional<ContactProfile> lockByEmployeeId(Long employeeId);
}
