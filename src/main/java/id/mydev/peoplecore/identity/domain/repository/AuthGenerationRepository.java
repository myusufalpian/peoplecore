package id.mydev.peoplecore.identity.domain.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;

import java.util.Optional;

import id.mydev.peoplecore.identity.domain.model.AuthGeneration;
public interface AuthGenerationRepository extends JpaRepository<AuthGeneration, Integer> {
    @Query("select generation.generation from AuthGeneration generation where generation.id = 1")
    Optional<Long> currentValue();

    @Modifying(flushAutomatically = true)
    @Query("update AuthGeneration generation set generation.generation = generation.generation + 1 where generation.id = 1")
    int advance();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select generation from AuthGeneration generation where generation.id = 1")
    Optional<AuthGeneration> lockSingleton();
}
