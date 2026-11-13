package id.mydev.peoplecore.leave.application.service;

import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.identity.application.service.AccountAccessService;
import id.mydev.peoplecore.identity.domain.model.HrisRole;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.leave.application.command.LeaveWorkflowModels.Configuration;
import id.mydev.peoplecore.leave.application.command.LeaveWorkflowModels.Publish;
import id.mydev.peoplecore.leave.application.command.LeaveWorkflowModels.Route;
import id.mydev.peoplecore.leave.application.command.LeaveWorkflowModels.Rule;
import id.mydev.peoplecore.leave.domain.repository.LeaveRoutingRepository;
import id.mydev.peoplecore.leave.domain.repository.LeaveRoutingRepository.RuleRecord;
import id.mydev.peoplecore.organization.application.service.EmployeeService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LeaveRoutingService {
    private final LeaveRoutingRepository rules;
    private final AccountAccessService access;
    private final EmployeeService employees;
    private final Clock clock;

    public LeaveRoutingService(LeaveRoutingRepository rules, AccountAccessService access, EmployeeService employees,
                               Clock clock) {
        this.rules = rules;
        this.access = access;
        this.employees = employees;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Configuration publish(UUID id, Publish payload, Authentication caller) {
        Instant now = clock.instant();
        UserAccount hr = access.requireHrOperation(caller, now);
        if (payload.rules() == null || payload.rules().isEmpty() || payload.rules().size() > 100
            || payload.rules().stream().anyMatch(Objects::isNull)
            || payload.rules().stream().map(Rule::ruleId).distinct().count() != payload.rules().size()) {
            throw new IllegalArgumentException("Invalid leave rule snapshot");
        }
        for (Rule rule : payload.rules()) {
            access.requireTargetScope(hr.getId(), rule.orgUnit(), now);
            requireHrReviewer(rule.hrAccountPublicId(), rule.orgUnit(), null, now);
            requireHrReviewer(rule.cancellationAccountPublicId(), rule.orgUnit(), null, now);
        }
        rules.insertRuleSet(id, now);
        long internal = rules.ruleSetId(id);
        for (Rule rule : payload.rules()) {
            rules.insertRule(internal, new RuleRecord(rule.ruleId(), rule.priority(), rule.orgUnit(),
                rule.hrAccountPublicId(), rule.cancellationAccountPublicId()));
        }
        return new Configuration(id, payload.rules());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Configuration activate(UUID id, Authentication caller) {
        UserAccount hr = access.requireHrOperation(caller, clock.instant());
        if (!access.hrAuthority(hr.getId(), clock.instant()).organizationWide()) {
            throw new AccessDeniedException("Global activation requires organization-wide HR");
        }
        long internal = rules.findRuleSetId(id).orElseThrow(ResourceNotFoundException::new);
        rules.insertActivation(internal, clock.instant());
        rules.activateCurrent(internal);
        return configuration(id);
    }

    public Configuration configuration(UUID id) {
        List<Rule> configured = rules.findRules(id).stream()
            .map(record -> new Rule(record.ruleId(), record.priority(), record.orgUnit(),
                record.hrAccountPublicId(), record.cancellationAccountPublicId()))
            .toList();
        if (configured.isEmpty()) {
            throw new ResourceNotFoundException();
        }
        return new Configuration(id, configured);
    }

    public Route resolve(Long employeeId, UUID employeePublicId, String unit, Instant now) {
        return resolveWithManager(employeePublicId, unit, employees.currentManagerPublicId(employeeId, now).orElse(null), now);
    }

    public Route resolveWithManager(UUID employeePublicId, String unit, UUID managerEmployeePublicId, Instant now) {
        if (unit == null || unit.isBlank()) {
            return new Route(null, null, null, null, "NO_ORG_UNIT");
        }
        UUID config = rules.findCurrentRuleSetPublicId().orElse(null);
        if (config == null) {
            return new Route(null, null, null, null, "NO_ACTIVE_CONFIGURATION");
        }
        List<Rule> matches = configuration(config).rules().stream().filter(rule -> rule.orgUnit().equals(unit)).toList();
        if (matches.isEmpty()) {
            return new Route(config, null, null, null, "NO_MATCH");
        }
        if (matches.size() > 1 && matches.get(0).priority() == matches.get(1).priority()) {
            return new Route(config, null, null, null, "AMBIGUOUS_RULE");
        }
        Rule rule = matches.getFirst();
        Optional<UserAccount> manager = Optional.ofNullable(managerEmployeePublicId).flatMap(access::accountBoundToEmployee);
        if (manager.isEmpty()) {
            return new Route(config, null, rule.hrAccountPublicId(), rule.cancellationAccountPublicId(),
                "NO_DESIGNATED_REVIEWER");
        }
        UUID managerId = manager.get().getPublicId();
        Route route = new Route(config, managerId, rule.hrAccountPublicId(), rule.cancellationAccountPublicId(), null);
        if (!eligible(managerId, employeePublicId, unit, 1, false, now)
            || !eligible(rule.hrAccountPublicId(), employeePublicId, unit, 2, false, now)
            || !eligible(rule.cancellationAccountPublicId(), employeePublicId, unit, 2, false, now)
            || samePerson(managerId, rule.hrAccountPublicId())) {
            return new Route(config, managerId, rule.hrAccountPublicId(), rule.cancellationAccountPublicId(),
                "REVIEWER_INELIGIBLE");
        }
        return route;
    }

    public boolean eligible(UUID reviewer, UUID requester, String unit, int step, boolean fallback, Instant now) {
        Optional<UserAccount> account = access.findAccountByPublicId(reviewer);
        if (account.isEmpty() || !account.get().isActiveAt(now)) {
            return false;
        }
        Optional<UUID> own = access.ownEmployeePublicId(account.get());
        if (own.isEmpty() || own.get().equals(requester)) {
            return false;
        }
        if (step == 2 || fallback) {
            return access.hasRole(account.get().getId(), HrisRole.HR_ADMIN, now)
                && access.coversUnit(account.get().getId(), unit, now);
        }
        if (!access.hasRole(account.get().getId(), HrisRole.MANAGER, now)) {
            return false;
        }
        return access.hasScopedRole(account.get().getId(), HrisRole.MANAGER, unit, now);
    }

    public UUID reviewerEmployee(UUID reviewer) {
        return access.findAccountByPublicId(reviewer).flatMap(access::ownEmployeePublicId)
            .orElseThrow(() -> new IllegalArgumentException("Reviewer requires active employee binding"));
    }

    public boolean samePerson(UUID first, UUID second) {
        if (first == null || second == null) {
            return false;
        }
        Optional<UUID> left = access.findAccountByPublicId(first).flatMap(access::ownEmployeePublicId);
        Optional<UUID> right = access.findAccountByPublicId(second).flatMap(access::ownEmployeePublicId);
        return left.isPresent() && left.equals(right);
    }

    private void requireHrReviewer(UUID id, String unit, UUID requester, Instant now) {
        UserAccount account = access.findAccountByPublicId(id)
            .orElseThrow(() -> new IllegalArgumentException("Unknown reviewer"));
        if (!account.isActiveAt(now) || !access.hasRole(account.getId(), HrisRole.HR_ADMIN, now)
            || !access.coversUnit(account.getId(), unit, now) || access.ownEmployeePublicId(account).isEmpty()
            || Objects.equals(requester, access.ownEmployeePublicId(account).orElse(null))) {
            throw new IllegalArgumentException("Ineligible designated leave reviewer");
        }
    }
}
