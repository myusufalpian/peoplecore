package id.mydev.peoplecore.approval.domain.repository;

import id.mydev.peoplecore.approval.domain.model.ProfileRuleCurrentActivation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface ProfileRuleCurrentActivationRepository
    extends JpaRepository<ProfileRuleCurrentActivation, Integer> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select pointer from ProfileRuleCurrentActivation pointer where pointer.id = 1")
    Optional<ProfileRuleCurrentActivation> lockSingleton();
}
