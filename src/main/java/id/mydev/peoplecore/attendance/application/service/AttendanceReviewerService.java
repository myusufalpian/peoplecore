package id.mydev.peoplecore.attendance.application.service;

import tools.jackson.databind.JsonNode;

import id.mydev.peoplecore.attendance.domain.repository.AttendanceReviewRepository;
import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.common.command.AdmissionGuard;
import id.mydev.peoplecore.identity.application.service.AccountAccessService;
import id.mydev.peoplecore.identity.domain.model.HrisRole;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.organization.application.service.EmployeeService;
import id.mydev.peoplecore.organization.domain.model.Employee;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class AttendanceReviewerService {
    public record Rule(UUID publicId, int priority, String orgUnit, UUID designatedHrId) { }
    public record PublishRules(UUID publicId, List<Rule> rules) { }
    public record ActivateRules(UUID ruleSetId, String reason) { }
    public record RuleResult(UUID publicId, String status) { }
    public record Route(Long reviewerId, String blockedReason) { }
    public record Preview(UUID ruleSetPublicId, UUID reviewerAccountPublicId, String blockedReason) { }
    private final AttendanceReviewRepository reviews;
    private final AccountAccessService access;
    private final EmployeeService employees;
    private final AdmissionGuard admission;
    private final Clock clock;

    public AttendanceReviewerService(AttendanceReviewRepository reviews, AccountAccessService access,
        EmployeeService employees, AdmissionGuard admission, Clock clock) {
        this.reviews = reviews;
        this.access = access;
        this.employees = employees;
        this.admission = admission;
        this.clock = clock;
    }

    @Transactional
    public RuleResult publish(PublishRules payload, Authentication caller) {
        admission.acquire(AdmissionGuard.Mode.EXCLUSIVE);
        access.requireHrOperation(caller, Instant.now(clock));
        if (payload.publicId() == null || payload.rules() == null || payload.rules().isEmpty()
            || payload.rules().size() > 100 || payload.rules().stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("One to 100 typed rules and a public ID are required");
        }
        HashSet<UUID> ids = new HashSet<>();
        for (Rule rule : payload.rules()) {
            access.requireHrScope(caller, rule.orgUnit(), Instant.now(clock));
            if (rule.publicId() == null || !ids.add(rule.publicId())
                || (rule.orgUnit() != null && (!StringUtils.hasText(rule.orgUnit()) || rule.orgUnit().length() > 128))) {
                throw new IllegalArgumentException("Rule IDs must be unique and coverage must be explicit");
            }
            if (rule.designatedHrId() != null) {
                UserAccount reviewer = access.findAccountByPublicId(rule.designatedHrId())
                    .orElseThrow(ResourceNotFoundException::new);
                AccountAccessService.requireActive(reviewer, Instant.now(clock));
                if (access.ownEmployeePublicId(reviewer).isEmpty()
                    || !access.coversUnit(reviewer.getId(), rule.orgUnit(), Instant.now(clock))) {
                    throw new IllegalArgumentException("Designated attendance HR must hold covered HR authority");
                }
            }
        }
        for (Rule rule : payload.rules()) {
            access.requireHrScope(caller, rule.orgUnit(), Instant.now(clock));
        }
        reviews.insertRuleSet(payload.publicId(), Instant.now(clock));
        for (Rule rule : payload.rules()) {
            Long designated = rule.designatedHrId() == null ? null : access.findAccountByPublicId(rule.designatedHrId())
                .orElseThrow(ResourceNotFoundException::new).getId();
            reviews.insertRule(rule.publicId(), payload.publicId(), rule.priority(), rule.orgUnit(), designated);
        }
        reviews.markPublished(payload.publicId());
        return new RuleResult(payload.publicId(), "PUBLISHED");
    }

    @Transactional
    public RuleResult activate(ActivateRules payload, Authentication caller) {
        admission.acquire(AdmissionGuard.Mode.EXCLUSIVE);
        requirePublisher(caller, Instant.now(clock));
        if (!StringUtils.hasText(payload.reason()) || payload.reason().length() > 2000) {
            throw new IllegalArgumentException("Activation reason is required");
        }
        if (reviews.countPublished(payload.ruleSetId()) == 0) {
            throw new ResourceNotFoundException();
        }
        reviews.insertActivation(UUID.randomUUID(), payload.ruleSetId(), payload.reason(), Instant.now(clock));
        return new RuleResult(payload.ruleSetId(), "ACTIVE");
    }

    @Transactional
    public Route resolve(Employee employee, Instant at) {
        String unit = employees.currentOrgUnit(employee.getId(), at).orElse(null);
        List<RuleMatch> matching = reviews.findMatchingRules(unit).stream()
            .map(record -> new RuleMatch(record.priority(), record.designatedHrId()))
            .toList();
        if (matching.isEmpty()) {
            return new Route(null, "NO_MATCHING_RULE");
        }
        int highest = matching.stream().map(RuleMatch::priority).max(Comparator.naturalOrder()).orElseThrow();
        if (matching.stream().filter(rule -> rule.priority() == highest).count() != 1) {
            return new Route(null, "AMBIGUOUS_RULE");
        }
        Optional<UUID> manager = employees.currentManagerPublicId(employee.getId(), at);
        Optional<UserAccount> account = manager.flatMap(access::accountBoundToEmployee);
        if (account.isEmpty() || !eligible(account.get(), employee, at, false)) {
            return new Route(null, "REVIEWER_UNAVAILABLE");
        }
        return new Route(account.get().getId(), null);
    }

    @Transactional
    public Preview preview(UUID employeePublicId, Authentication caller) {
        admission.acquire(AdmissionGuard.Mode.SHARED);
        Instant at = Instant.now(clock);
        Employee employee = employees.requireInternalByPublicId(employeePublicId);
        UserAccount actor = access.requireActive(caller, at);
        access.requireEmployeeRead(actor, employeePublicId,
            employees.currentOrgUnit(employee.getId(), at).orElse(null), at);
        Route route = resolve(employee, at);
        UUID reviewer = route.reviewerId() == null ? null
            : access.findAccountById(route.reviewerId()).map(UserAccount::getPublicId).orElse(null);
        return new Preview(activeRuleSetPublicId(), reviewer, route.blockedReason());
    }

    private UUID activeRuleSetPublicId() {
        return reviews.findActiveRuleSetPublicId().orElse(null);
    }

    public boolean eligible(UserAccount reviewer, Employee employee, Instant at, boolean fallback) {
        if (!reviewer.isActiveAt(at)) {
            return false;
        }
        Optional<UUID> own = access.ownEmployeePublicId(reviewer);
        if (own.isEmpty() || own.get().equals(employee.getPublicId())) {
            return false;
        }
        String unit = employees.currentOrgUnit(employee.getId(), at).orElse(null);
        if (fallback) {
            boolean designated = reviews.countDesignatedHr(reviewer.getId(), unit) > 0;
            return designated && access.coversUnit(reviewer.getId(), unit, at);
        }
        if (!access.hasRole(reviewer.getId(), HrisRole.MANAGER, at)) {
            return false;
        }
        return access.hasScopedRole(reviewer.getId(), HrisRole.MANAGER, unit, at);
    }

    public void verifyPublication(Authentication caller, JsonNode payload, Instant at) {
        access.requireHrOperation(caller, at);
        var rules = payload.get("rules");
        if (rules == null || !rules.isArray() || rules.isEmpty() || rules.size() > 100) {
            throw new IllegalArgumentException("One to 100 rules are required");
        }
        for (var rule : rules) {
            if (rule == null || rule.isNull() || !rule.isObject()) {
                throw new IllegalArgumentException("Rule must be an object");
            }
            var unit = rule.get("orgUnit");
            access.requireHrScope(caller, unit == null || unit.isNull() ? null : unit.asString(), at);
        }
    }

    public void requirePublisher(Authentication caller, Instant at) {
        UserAccount account = access.requireHrOperation(caller, at);
        if (!access.hrAuthority(account.getId(), at).organizationWide()) {
            throw new AccessDeniedException("Attendance configuration requires organization-wide HR");
        }
    }

    private record RuleMatch(int priority, Long designatedHrId) { }
}
