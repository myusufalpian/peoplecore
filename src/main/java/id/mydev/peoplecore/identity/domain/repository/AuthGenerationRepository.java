package id.mydev.peoplecore.identity.domain.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

import id.mydev.peoplecore.identity.domain.model.AuthGeneration;
public interface AuthGenerationRepository extends JpaRepository<AuthGeneration, Integer> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select generation from AuthGeneration generation where generation.id = 1")
    Optional<AuthGeneration> lockSingleton();
}
