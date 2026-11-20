package id.mydev.peoplecore.common.security;

import id.mydev.peoplecore.identity.application.service.AccountAccessService;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import java.time.Instant;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
public class OperationAccessService {
    private final AccountAccessService access;

    public OperationAccessService(AccountAccessService access) {
        this.access = access;
    }

    public UserAccount requireOperator(Authentication caller, Instant now) {
        return requireGlobalRole(caller, "SYSTEM_ADMIN", now);
    }

    public UserAccount requireGlobalRole(Authentication caller, String role, Instant now) {
        UserAccount account = access.requireActive(caller, now);
        if (!access.hasScopedRole(account.getId(), role, null, now)) {
            throw new AccessDeniedException("Organization-wide " + role + " authority required");
        }
        return account;
    }

    public UserAccount requireRecoveryViewer(Authentication caller, Instant now) {
        UserAccount account = access.requireActive(caller, now);
        if (!access.hasScopedRole(account.getId(), "SYSTEM_ADMIN", null, now)
            && !access.hasScopedRole(account.getId(), "HR_ADMIN", null, now)
            && !access.hasScopedRole(account.getId(), "FINANCE_PAYROLL", null, now)) {
            throw new AccessDeniedException("Organization-wide recovery authority required");
        }
        return account;
    }
}
