package id.mydev.peoplecore.identity.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

import id.mydev.peoplecore.identity.domain.model.UserAccount;
public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {
    Optional<UserAccount> findByOidcIssuerAndOidcSubject(String oidcIssuer, String oidcSubject);

    Optional<UserAccount> findByPublicId(UUID publicId);
}
