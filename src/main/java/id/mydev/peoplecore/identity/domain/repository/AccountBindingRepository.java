package id.mydev.peoplecore.identity.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

import id.mydev.peoplecore.identity.domain.model.AccountBinding;
public interface AccountBindingRepository extends JpaRepository<AccountBinding, Long> {
    Optional<AccountBinding> findByAccountIdAndRevokedAtIsNull(Long accountId);

    Optional<AccountBinding> findByEmployeeIdAndRevokedAtIsNull(Long employeeId);

    Optional<AccountBinding> findByOidcIssuerAndOidcSubjectAndRevokedAtIsNull(String oidcIssuer, String oidcSubject);

    List<AccountBinding> findByEmployeeId(Long employeeId);
}
