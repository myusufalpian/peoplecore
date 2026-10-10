package id.mydev.peoplecore.identity.application.service;

import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import id.mydev.peoplecore.identity.domain.model.AccountBinding;
import id.mydev.peoplecore.identity.domain.model.HrisRole;
import id.mydev.peoplecore.identity.domain.model.RoleAssignment;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.identity.domain.repository.AccountBindingRepository;
import id.mydev.peoplecore.identity.domain.repository.RoleAssignmentRepository;
import id.mydev.peoplecore.identity.domain.repository.UserAccountRepository;
import id.mydev.peoplecore.identity.application.mapper.JwtIdentityMapper;
@Service
public class AccountAccessService {

    private final UserAccountRepository accounts;
    private final RoleAssignmentRepository roles;
    private final AccountBindingRepository bindings;
    private final AuthGenerationService generations;
    private final Clock clock;

    public AccountAccessService(
        UserAccountRepository accounts,
        RoleAssignmentRepository roles,
        AccountBindingRepository bindings,
        AuthGenerationService generations,
        Clock clock
    ) {
        this.accounts = accounts;
        this.roles = roles;
        this.bindings = bindings;
        this.generations = generations;
        this.clock = clock;
    }

    public record AccessSnapshot(
        UUID accountId,
        String status,
        Instant accessEndsAt,
        List<String> roles,
        UUID employeeId,
        long authorizationGeneration
    ) { }

    public record HrAuthority(boolean organizationWide, Set<String> units) { }

    public static JwtIdentityMapper.OidcIdentity identityOf(Authentication caller) {
        if (caller == null) {
            throw new AuthenticationCredentialsNotFoundException("Authenticated caller required");
        }
        if (caller instanceof JwtAuthenticationToken jwt) {
            return JwtIdentityMapper.toIdentity(jwt.getToken());
        }
        throw new AccessDeniedException("Caller identity is not independently verified");
    }

    @Transactional(readOnly = true)
    public Optional<UserAccount> resolveAccount(Authentication caller) {
        JwtIdentityMapper.OidcIdentity identity = identityOf(caller);
        return accounts.findByOidcIssuerAndOidcSubject(identity.issuer(), identity.subject());
    }

    @Transactional(readOnly = true)
    public UserAccount requireActive(Authentication caller, Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        UserAccount account = resolveAccount(caller)
            .orElseThrow(() -> new AccessDeniedException("Account is not linked to this identity"));
        requireActive(account, now);
        return account;
    }

    public static void requireActive(UserAccount account, Instant now) {
        Objects.requireNonNull(account, "account must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (!account.isActiveAt(now)) {
            throw new AccessDeniedException("Account access has ended");
        }
    }

    @Transactional(readOnly = true)
    public UserAccount requireHrScope(Authentication caller, String targetOrgUnit, Instant now) {
        UserAccount account = requireActive(caller, now);
        if (!coversUnit(account.getId(), targetOrgUnit, now)) {
            throw new AccessDeniedException("HR scope does not cover this organization unit");
        }
        return account;
    }

    @Transactional(readOnly = true)
    public UserAccount requireHrOperation(Authentication caller, Instant now) {
        UserAccount account = requireActive(caller, now);
        if (!hasRole(account.getId(), HrisRole.HR_ADMIN, now)) {
            throw new AccessDeniedException("HR operation requires the HR role");
        }
        return account;
    }

    @Transactional(readOnly = true)
    public void requireIdentityAuthority(Long accountId, Instant now) {
        boolean authorized = roles.findByAccountId(accountId).stream()
            .anyMatch(role -> HrisRole.SYSTEM_ADMIN.equals(role.getRole())
                && role.isEffectiveAt(now) && !StringUtils.hasText(role.getScope()));
        if (!authorized) {
            throw new AccessDeniedException("Identity and privileged grants require organization-wide SYSTEM_ADMIN");
        }
    }

    @Transactional(readOnly = true)
    public void requireTargetScope(Long accountId, String targetOrgUnit, Instant now) {
        if (!coversUnit(accountId, targetOrgUnit, now)) {
            throw new ResourceNotFoundException();
        }
    }

    @Transactional(readOnly = true)
    public boolean coversUnit(Long accountId, String targetOrgUnit, Instant now) {
        List<RoleAssignment> assignments = roles.findByAccountId(accountId);
        for (RoleAssignment assignment : assignments) {
            if (!HrisRole.HR_ADMIN.equals(assignment.getRole()) || !assignment.isEffectiveAt(now)) {
                continue;
            }
            if (targetOrgUnit == null) {
                // Without a target organization unit only an organization-wide grant applies.
                if (!StringUtils.hasText(assignment.getScope())) {
                    return true;
                }
            } else if (!StringUtils.hasText(assignment.getScope())
                || targetOrgUnit.equals(assignment.getScope())) {
                return true;
            }
        }
        return false;
    }

    @Transactional(readOnly = true)
    public HrAuthority hrAuthority(Long accountId, Instant now) {
        boolean organizationWide = false;
        Set<String> units = new TreeSet<>();
        for (RoleAssignment assignment : roles.findByAccountId(accountId)) {
            if (HrisRole.HR_ADMIN.equals(assignment.getRole()) && assignment.isEffectiveAt(now)) {
                if (!StringUtils.hasText(assignment.getScope())) {
                    organizationWide = true;
                } else {
                    units.add(assignment.getScope());
                }
            }
        }
        return new HrAuthority(organizationWide, Collections.unmodifiableSet(units));
    }

    @Transactional(readOnly = true)
    public boolean hasRole(Long accountId, String role, Instant now) {
        List<RoleAssignment> assignments = roles.findByAccountId(accountId);
        for (RoleAssignment assignment : assignments) {
            if (role.equals(assignment.getRole()) && assignment.isEffectiveAt(now)) {
                return true;
            }
        }
        return false;
    }

    @Transactional(readOnly = true)
    public List<String> activeRoles(Long accountId, Instant now) {
        return roles.findByAccountId(accountId).stream()
            .filter(assignment -> assignment.isEffectiveAt(now))
            .map(RoleAssignment::getRole)
            .sorted()
            .toList();
    }

    @Transactional(readOnly = true)
    public Optional<UUID> ownEmployeePublicId(UserAccount account) {
        Objects.requireNonNull(account, "account must not be null");
        return bindings.findByAccountIdAndRevokedAtIsNull(account.getId())
            .map(AccountBinding::getEmployeePublicId);
    }

    @Transactional(readOnly = true)
    public AccessSnapshot describeAccess(Authentication caller) {
        Instant now = Instant.now(clock);
        UserAccount account = requireActive(caller, now);
        UUID employeeId = ownEmployeePublicId(account).orElse(null);
        return new AccessSnapshot(account.getPublicId(), account.getStatus(), account.getAccessEndsAt(),
            activeRoles(account.getId(), now), employeeId, generations.current());
    }
}
