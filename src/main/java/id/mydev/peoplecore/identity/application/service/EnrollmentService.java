package id.mydev.peoplecore.identity.application.service;

import id.mydev.peoplecore.identity.application.command.EnrollmentResults.BindingResult;
import id.mydev.peoplecore.identity.application.command.EnrollmentResults.InvitationResult;
import id.mydev.peoplecore.identity.application.command.EnrollmentResults.IssuedInvitation;
import id.mydev.peoplecore.identity.application.command.EnrollmentResults.OffboardResult;
import id.mydev.peoplecore.identity.application.command.EnrollmentResults.RebindResult;
import id.mydev.peoplecore.identity.application.command.EnrollmentResults.RehireResult;
import id.mydev.peoplecore.identity.application.command.EnrollmentResults.RestoreResult;
import id.mydev.peoplecore.identity.application.mapper.JwtIdentityMapper;
import id.mydev.peoplecore.identity.application.service.AccountAccessService.HrAuthority;
import id.mydev.peoplecore.identity.domain.exception.BindingConflictException;import id.mydev.peoplecore.identity.domain.exception.InvalidRoleException;
import id.mydev.peoplecore.identity.domain.exception.InvitationConflictException;
import id.mydev.peoplecore.identity.domain.exception.InvitationExpiredException;
import id.mydev.peoplecore.identity.domain.exception.LifecycleConflictException;
import id.mydev.peoplecore.identity.domain.model.AccountBinding;
import id.mydev.peoplecore.identity.domain.model.EnrollmentInvitation;
import id.mydev.peoplecore.identity.domain.model.HrisRole;
import id.mydev.peoplecore.identity.domain.model.RoleAssignment;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.identity.domain.repository.AccountBindingRepository;
import id.mydev.peoplecore.identity.domain.repository.EnrollmentInvitationRepository;
import id.mydev.peoplecore.identity.domain.repository.RoleAssignmentRepository;
import id.mydev.peoplecore.identity.domain.repository.UserAccountRepository;
import id.mydev.peoplecore.organization.application.service.EmployeeService;
import id.mydev.peoplecore.organization.domain.model.Employee;
import id.mydev.peoplecore.organization.application.mapper.EmployeeResultMapper;
import jakarta.persistence.EntityManager;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class EnrollmentService {

    public static final Duration DEFAULT_INVITATION_TTL = Duration.ofDays(7);
    static final int SECRET_BYTES = 32;

    private static final Set<String> GRANTABLE_ROLES = Set.of(
        HrisRole.EMPLOYEE, HrisRole.MANAGER, HrisRole.HR_ADMIN,
        HrisRole.FINANCE_PAYROLL, HrisRole.SYSTEM_ADMIN);
    private static final Set<String> SCOPED_GRANTABLE_ROLES = Set.of(
        HrisRole.EMPLOYEE, HrisRole.MANAGER);

    private final EnrollmentInvitationRepository invitations;
    private final AccountBindingRepository bindings;
    private final UserAccountRepository accounts;
    private final RoleAssignmentRepository roles;
    private final EmployeeService employees;
    private final AccountAccessService access;
    private final AuthGenerationService generations;
    private final ActivationAttemptService attempts;
    private final EntityManager entities;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public EnrollmentService(
        EnrollmentInvitationRepository invitations,
        AccountBindingRepository bindings,
        UserAccountRepository accounts,
        RoleAssignmentRepository roles,
        EmployeeService employees,
        AccountAccessService access,
        AuthGenerationService generations,
        ActivationAttemptService attempts,
        EntityManager entities,
        Clock clock
    ) {
        this.invitations = invitations;
        this.bindings = bindings;
        this.accounts = accounts;
        this.roles = roles;
        this.employees = employees;
        this.access = access;
        this.generations = generations;
        this.attempts = attempts;
        this.entities = entities;
        this.clock = clock;
    }

    @Transactional
    public IssuedInvitation issue(UUID employeePublicId, UUID invitationPublicId, String intendedIdentityRef,
                                  String expectedIssuer, String expectedSubject,
                                  Duration timeToLive, Authentication caller) {
        Objects.requireNonNull(employeePublicId, "employeePublicId must not be null");
        Objects.requireNonNull(invitationPublicId, "invitationPublicId must not be null");
        Instant now = Instant.now(clock);
        UserAccount hr = access.requireHrOperation(caller, now);
        Employee employee = employees.requireInternalByPublicId(employeePublicId);
        access.requireTargetScope(hr.getId(), employees.currentOrgUnit(employee.getId(), now).orElse(null), now);
        if (!StringUtils.hasText(intendedIdentityRef)) {
            throw new IllegalArgumentException("intendedIdentityRef must not be blank");
        }
        if (!StringUtils.hasText(expectedIssuer) || !StringUtils.hasText(expectedSubject)) {
            throw new IllegalArgumentException("Expected verified identity must not be blank");
        }
        employees.lockEmployee(employee.getId());
        recheckTarget(caller, employees.currentOrgUnit(employee.getId(), Instant.now(clock)).orElse(null));
        access.requireIdentityAuthority(hr.getId(), Instant.now(clock));
        if (!employees.requireInternalByPublicId(employeePublicId).isActive()) {
            throw new InvitationConflictException("Employee is not in active employment");
        }
        revokeOutstanding(employee.getId());
        String secret = newSecret();
        EnrollmentInvitation invitation = new EnrollmentInvitation(
            invitationPublicId, employee.getId(), intendedIdentityRef.trim(),
            expectedIssuer.trim(), expectedSubject.trim(), sha256Hex(secret),
            now.plus(timeToLive == null ? DEFAULT_INVITATION_TTL : timeToLive), now);
        invitations.save(invitation);
        generations.increment();
        return new IssuedInvitation(new InvitationResult(invitation.getPublicId(), employeePublicId,
            invitation.getIntendedIdentityRef(), invitation.getStatus(), invitation.getDeliveryStatus(),
            invitation.getExpiresAt()), secret);
    }

    @Transactional
    public IssuedInvitation reissue(UUID employeePublicId, UUID invitationPublicId, String intendedIdentityRef,
                                    String expectedIssuer, String expectedSubject, Authentication caller) {
        return issue(employeePublicId, invitationPublicId, intendedIdentityRef,
            expectedIssuer, expectedSubject, null, caller);
    }

    private BindingResult activateGuarded(UUID invitationPublicId, String secret, Authentication caller,
                                          JwtIdentityMapper.OidcIdentity principal) {
        EnrollmentInvitation preview = invitations.findByPublicId(invitationPublicId)
            .orElseThrow(() -> new InvitationConflictException("Invitation is not available"));
        Employee lockedEmployee = employees.lockEmployee(preview.getEmployeeId())
            .orElseThrow(() -> new InvitationConflictException("Employee for invitation is missing"));
        refreshEntities();
        EnrollmentInvitation invitation = invitations.lockByPublicId(invitationPublicId)
            .orElseThrow(() -> new InvitationConflictException("Invitation is not available"));
        Employee employee = employees.lockEmployee(invitation.getEmployeeId())
            .orElseThrow(() -> new InvitationConflictException("Employee for invitation is missing"));
        if (!lockedEmployee.getId().equals(employee.getId())) {
            throw new InvitationConflictException("Invitation is not available");
        }
        Instant checked = Instant.now(clock);
        if (!EnrollmentInvitation.STATUS_ISSUED.equals(invitation.getStatus())) {
            throw new InvitationConflictException("Invitation has already been used or revoked");
        }
        if (!checked.isBefore(invitation.getExpiresAt())) {
            throw new InvitationExpiredException("Invitation has expired");
        }
        if (!StringUtils.hasText(secret) || !matchesSecret(secret, invitation.getSecretHash())) {
            throw new InvitationConflictException("Invitation credentials are not valid");
        }
        if (!invitation.getExpectedIssuer().equals(principal.issuer())
            || !invitation.getExpectedSubject().equals(principal.subject())) {
            throw new InvitationConflictException("Invitation was not issued for this identity");
        }
        if (!employee.isActive()) {
            throw new InvitationConflictException("Employee is not in active employment");
        }
        if (bindings.findByEmployeeIdAndRevokedAtIsNull(employee.getId()).isPresent()) {
            throw new BindingConflictException("Employee already has an active binding");
        }
        if (bindings.findByOidcIssuerAndOidcSubjectAndRevokedAtIsNull(
            principal.issuer(), principal.subject()).isPresent()) {
            throw new BindingConflictException("Identity is already bound to another employee");
        }
        UserAccount account = accounts
            .findByOidcIssuerAndOidcSubject(principal.issuer(), principal.subject())
            .orElseGet(() -> accounts.save(new UserAccount(
                UUID.randomUUID(), principal.issuer(), principal.subject(), checked)));
        AccountAccessService.requireActive(account, checked);
        AccountBinding binding = new AccountBinding(employee.getId(), employee.getPublicId(),
            account.getId(), principal.issuer(), principal.subject(), checked);
        try {
            bindings.saveAndFlush(binding);
        } catch (RuntimeException ex) {
            throw new BindingConflictException("Concurrent binding already claimed this association");
        }
        invitation.consume(checked);
        invitations.save(invitation);
        employee.linkAccount(account.getId());
        grantRoleIfAbsent(account.getId(), HrisRole.EMPLOYEE, checked);
        generations.increment();
        attempts.clearAttempts(invitationPublicId);
        return new BindingResult(employee.getPublicId(), invitation.getPublicId(),
            binding.getOidcIssuer(), binding.getOidcSubject(), binding.getCreatedAt());
    }

    @Transactional
    public OffboardResult offboard(UUID employeePublicId, Instant accessEndsAt, LocalTime cutoffTime,
                                   String cutoffTimezone, Authentication caller) {
        Objects.requireNonNull(employeePublicId, "employeePublicId must not be null");
        Objects.requireNonNull(accessEndsAt, "accessEndsAt must not be null");
        Instant now = Instant.now(clock);
        UserAccount hr = access.requireHrOperation(caller, now);
        Employee employee = employees.requireInternalByPublicId(employeePublicId);
        access.requireTargetScope(hr.getId(), employees.currentOrgUnit(employee.getId(), now).orElse(null), now);
        employees.lockEmployee(employee.getId()).orElseThrow(IllegalStateException::new);
        recheckTarget(caller, employees.currentOrgUnit(employee.getId(), Instant.now(clock)).orElse(null));
        boolean wasEmploymentActive = employees.findInternalById(employee.getId())
            .map(Employee::isActive).orElse(false);
        AccountBinding activeBinding = bindings.findByEmployeeIdAndRevokedAtIsNull(employee.getId())
            .orElse(null);
        UserAccount boundAccount = activeBinding == null ? null
            : accounts.findById(activeBinding.getAccountId()).orElse(null);
        Instant settled = Instant.now(clock);
        boolean accessEnded = boundAccount != null && !boundAccount.isActiveAt(settled)
            && boundAccount.getAccessEndsAt() != null && !settled.isBefore(boundAccount.getAccessEndsAt());
        if (!wasEmploymentActive && accessEnded) {
            throw new LifecycleConflictException(
                "Access has already ended; use rehire and access restoration");
        }
        var before = EmployeeResultMapper.state(employees.findInternalById(employee.getId()).orElseThrow());
        employee = employees.markOffboarded(employee.getId());
        revokeOutstanding(employee.getId());
        UUID accountPublicId = null;
        if (boundAccount != null && boundAccount.isActiveAt(settled)
            && (boundAccount.getAccessEndsAt() == null || settled.isBefore(boundAccount.getAccessEndsAt()))) {
            boundAccount.applyCutoff(accessEndsAt, cutoffTime, cutoffTimezone, settled);
            accounts.save(boundAccount);
            accountPublicId = boundAccount.getPublicId();
        }
        generations.increment();
        return new OffboardResult(employeePublicId, accountPublicId, before, EmployeeResultMapper.state(employee));
    }

    @Transactional
    public RebindResult rebind(UUID employeePublicId, String newIssuer, String newSubject, Authentication caller) {
        Objects.requireNonNull(employeePublicId, "employeePublicId must not be null");
        Instant now = Instant.now(clock);
        UserAccount hr = access.requireHrOperation(caller, now);
        Employee employee = employees.requireInternalByPublicId(employeePublicId);
        access.requireTargetScope(hr.getId(), employees.currentOrgUnit(employee.getId(), now).orElse(null), now);
        if (!StringUtils.hasText(newIssuer) || !StringUtils.hasText(newSubject)) {
            throw new IllegalArgumentException("Replacement identity must not be blank");
        }
        employees.lockEmployee(employee.getId()).orElseThrow(IllegalStateException::new);
        recheckTarget(caller, employees.currentOrgUnit(employee.getId(), Instant.now(clock)).orElse(null));
        Instant settled = Instant.now(clock);
        access.requireIdentityAuthority(hr.getId(), settled);
        Employee settledEmployee = employees.findInternalById(employee.getId())
            .orElseThrow(() -> new BindingConflictException("Employee is missing"));
        if (!settledEmployee.isActive()) {
            throw new BindingConflictException("Employee is not in active employment");
        }
        List<AccountBinding> history = bindings.findByEmployeeId(employee.getId());
        if (history.isEmpty()) {
            throw new BindingConflictException("Employee has no binding history to reassociate");
        }
        if (bindings.findByOidcIssuerAndOidcSubjectAndRevokedAtIsNull(newIssuer, newSubject).isPresent()) {
            throw new BindingConflictException("Identity is already bound to another employee");
        }
        AccountBinding current = bindings.findByEmployeeIdAndRevokedAtIsNull(employee.getId())
            .orElse(null);
        Instant carriedEndsAt = null;
        LocalTime carriedTime = null;
        String carriedTimezone = null;
        if (current != null) {
            UserAccount oldAccount = accounts.findById(current.getAccountId()).orElse(null);
            if (oldAccount != null && !oldAccount.isActiveAt(settled)) {
                throw new BindingConflictException("Current account is not active");
            }
            if (oldAccount != null) {
                carriedEndsAt = oldAccount.getAccessEndsAt();
                carriedTime = oldAccount.getCutoffTime();
                carriedTimezone = oldAccount.getCutoffTimezone();
            }
            current.revoke(settled);
            bindings.saveAndFlush(current);
            if (oldAccount != null) {
                oldAccount.applyCutoff(settled, null, null, settled);
                accounts.save(oldAccount);
                endRoles(oldAccount.getId(), settled);
            }
        }
        UserAccount account = accounts.findByOidcIssuerAndOidcSubject(newIssuer, newSubject)
            .orElseGet(() -> accounts.save(new UserAccount(UUID.randomUUID(), newIssuer, newSubject, settled)));
        if (!account.isActiveAt(settled)) {
            throw new BindingConflictException("Replacement account is not active");
        }
        if (carriedEndsAt != null && (account.getAccessEndsAt() == null
            || carriedEndsAt.isBefore(account.getAccessEndsAt()))) {
            account.applyCutoff(carriedEndsAt, carriedTime, carriedTimezone, settled);
            accounts.save(account);
        }
        AccountBinding replacement = new AccountBinding(employee.getId(), employee.getPublicId(),
            account.getId(), newIssuer, newSubject, settled);
        try {
            bindings.saveAndFlush(replacement);
        } catch (RuntimeException ex) {
            throw new BindingConflictException("Concurrent binding already claimed this association");
        }
        employee.linkAccount(account.getId());
        employees.saveEmployee(employee);
        grantRoleIfAbsent(account.getId(), HrisRole.EMPLOYEE, settled);
        generations.increment();
        return new RebindResult(employeePublicId, account.getPublicId());
    }

    @Transactional
    public BindingResult activate(UUID invitationPublicId, String secret, Authentication caller) {
        Objects.requireNonNull(invitationPublicId, "invitationPublicId must not be null");
        JwtIdentityMapper.OidcIdentity principal = AccountAccessService.identityOf(caller);
        return activateGuarded(invitationPublicId, secret, caller, principal);
    }

    @Transactional
    public RehireResult rehire(UUID employeePublicId, Authentication caller) {
        Objects.requireNonNull(employeePublicId, "employeePublicId must not be null");
        Instant now = Instant.now(clock);
        UserAccount hr = access.requireHrOperation(caller, now);
        Employee employee = employees.requireInternalByPublicId(employeePublicId);
        access.requireTargetScope(hr.getId(), employees.currentOrgUnit(employee.getId(), now).orElse(null), now);
        employees.lockEmployee(employee.getId()).orElseThrow(IllegalStateException::new);
        recheckTarget(caller, employees.currentOrgUnit(employee.getId(), Instant.now(clock)).orElse(null));
        var before = EmployeeResultMapper.state(employees.findInternalById(employee.getId()).orElseThrow());
        Employee rehired = employees.markRehired(employee.getId());
        generations.increment();
        return new RehireResult(employeePublicId, before, EmployeeResultMapper.state(rehired));
    }

    @Transactional
    public RestoreResult restoreAccess(UUID employeePublicId, List<String> roles, String scope,
                                       Authentication caller) {
        Objects.requireNonNull(employeePublicId, "employeePublicId must not be null");
        if (roles == null || roles.isEmpty()) {
            throw new InvalidRoleException("Restored roles must not be empty");
        }
        for (String role : roles) {
            if (!GRANTABLE_ROLES.contains(role)) {
                throw new InvalidRoleException("Role is not recognized: " + role);
            }
        }
        Instant now = Instant.now(clock);
        UserAccount hrAccount = access.requireHrOperation(caller, now);
        Employee employee = employees.requireInternalByPublicId(employeePublicId);
        access.requireTargetScope(hrAccount.getId(),
            employees.currentOrgUnit(employee.getId(), now).orElse(null), now);
        employees.lockEmployee(employee.getId()).orElseThrow(IllegalStateException::new);
        recheckTarget(caller, employees.currentOrgUnit(employee.getId(), Instant.now(clock)).orElse(null));
        HrAuthority authority = access.hrAuthority(hrAccount.getId(), Instant.now(clock));
        requireGrantAuthority(authority, roles, scope);
        if (roles.stream().anyMatch(role -> !SCOPED_GRANTABLE_ROLES.contains(role))) {
            access.requireIdentityAuthority(hrAccount.getId(), Instant.now(clock));
        }
        Employee settled = employees.findInternalById(employee.getId())
            .orElseThrow(() -> new BindingConflictException("Employee is missing"));
        if (!settled.isActive()) {
            throw new BindingConflictException("Employment is not active");
        }
        AccountBinding binding = bindings.findByEmployeeIdAndRevokedAtIsNull(employee.getId())
            .orElseThrow(() -> new BindingConflictException("Employee has no active binding to restore"));
        UserAccount account = accounts.findById(binding.getAccountId())
            .orElseThrow(() -> new BindingConflictException("Bound account is missing"));
        Instant restoredAt = Instant.now(clock);
        if (account.isActiveAt(restoredAt)) {
            throw new BindingConflictException("Account access is already active");
        }
        account.reactivate();
        accounts.save(account);
        endRoles(account.getId(), restoredAt);
        for (String role : roles) {
            this.roles.save(new RoleAssignment(account.getId(), role, scope, restoredAt, restoredAt));
        }
        generations.increment();
        return new RestoreResult(employeePublicId, account.getPublicId(), List.copyOf(roles), scope);
    }

    @Transactional(readOnly = true)
    public boolean activationReplayAllowed(UUID invitationPublicId, String issuer, String subject,
                                           UUID employeePublicId) {
        EnrollmentInvitation invitation = invitations.findByPublicId(invitationPublicId).orElse(null);
        if (invitation == null || !EnrollmentInvitation.STATUS_CONSUMED.equals(invitation.getStatus())) {
            return false;
        }
        Instant now = Instant.now(clock);
        return bindings.findByOidcIssuerAndOidcSubjectAndRevokedAtIsNull(issuer, subject)
            .filter(binding -> binding.getEmployeeId().equals(
                employees.findInternalById(invitation.getEmployeeId())
                    .map(Employee::getId).orElse(null)))
            .filter(binding -> employeePublicId.equals(binding.getEmployeePublicId()))
            .flatMap(binding -> accounts.findById(binding.getAccountId()))
            .filter(account -> account.isActiveAt(now))
            .isPresent();
    }

    private void revokeOutstanding(Long employeeId) {
        List<EnrollmentInvitation> outstanding =
            invitations.findByEmployeeIdAndStatus(employeeId, EnrollmentInvitation.STATUS_ISSUED);
        Instant revokedAt = Instant.now(clock);
        for (EnrollmentInvitation invitation : outstanding) {
            invitation.revoke(revokedAt);
            invitations.save(invitation);
        }
    }

    private void grantRoleIfAbsent(Long accountId, String role, Instant now) {
        if (!access.hasRole(accountId, role, now)) {
            roles.save(new RoleAssignment(accountId, role, null, now, now));
        }
    }

    private void endRoles(Long accountId, Instant now) {
        for (RoleAssignment assignment : roles.findByAccountId(accountId)) {
            if (assignment.getEffectiveTo() == null || assignment.getEffectiveTo().isAfter(now)) {
                assignment.endAt(now);
                roles.save(assignment);
            }
        }
    }
    private void requireGrantAuthority(HrAuthority authority, List<String> roles, String scope) {
        if (authority.organizationWide()) {
            return;
        }
        if (!StringUtils.hasText(scope) || !authority.units().contains(scope)) {
            throw new AccessDeniedException("Grant scope exceeds caller authority");
        }
        for (String role : roles) {
            if (!SCOPED_GRANTABLE_ROLES.contains(role)) {
                throw new AccessDeniedException("Role grant requires organization-wide authority");
            }
        }
    }

    private void recheckTarget(Authentication caller, String targetOrgUnit) {
        refreshEntities();
        Instant now = Instant.now(clock);
        UserAccount hr = access.requireHrOperation(caller, now);
        access.requireTargetScope(hr.getId(), targetOrgUnit, now);
    }

    private void refreshEntities() {
        // Flush first: clear() would cancel pending writes, while the row locks and the
        // uncommitted flushed state remain visible inside this transaction.
        entities.flush();
        entities.clear();
    }

    String newSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static boolean matchesSecret(String secret, String expectedHash) {
        return MessageDigest.isEqual(
            sha256Hex(secret).getBytes(StandardCharsets.UTF_8),
            expectedHash.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm not available", ex);
        }
    }
}
