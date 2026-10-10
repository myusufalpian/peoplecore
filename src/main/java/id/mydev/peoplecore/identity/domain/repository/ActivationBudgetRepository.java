package id.mydev.peoplecore.identity.domain.repository;

import id.mydev.peoplecore.identity.domain.model.ActivationBudgetSlot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

import java.time.Instant;
import java.util.Optional;

public interface ActivationBudgetRepository extends JpaRepository<ActivationBudgetSlot, Long> {
    Optional<ActivationBudgetSlot> findByScopeAndRefAAndRefB(String scope, String refA, String refB);

    @Modifying
    void deleteByWindowStartBefore(Instant cutoff);
}
