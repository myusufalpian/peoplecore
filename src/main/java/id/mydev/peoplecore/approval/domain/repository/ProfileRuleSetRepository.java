package id.mydev.peoplecore.approval.domain.repository;

import id.mydev.peoplecore.approval.domain.model.ProfileRuleSet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface ProfileRuleSetRepository extends JpaRepository<ProfileRuleSet, Long> {
    Optional<ProfileRuleSet> findByPublicId(UUID publicId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select ruleSet from ProfileRuleSet ruleSet where ruleSet.id = :id")
    Optional<ProfileRuleSet> lockById(Long id);
}
