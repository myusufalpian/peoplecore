package id.mydev.peoplecore.reimbursement.application.service;

import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.common.command.AdmissionGuard;
import id.mydev.peoplecore.identity.application.service.AccountAccessService;
import id.mydev.peoplecore.identity.domain.model.HrisRole;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.organization.application.service.EmployeeService;
import id.mydev.peoplecore.organization.domain.model.Employee;
import id.mydev.peoplecore.reimbursement.application.command.ReimbursementModels.Action;
import id.mydev.peoplecore.reimbursement.application.command.ReimbursementModels.Draft;
import id.mydev.peoplecore.reimbursement.application.command.ReimbursementModels.View;
import id.mydev.peoplecore.reimbursement.application.command.ReimbursementModels.History;
import id.mydev.peoplecore.reimbursement.application.command.ReimbursementModels.HistoryPage;
import id.mydev.peoplecore.reimbursement.application.command.ReimbursementModels.Summary;
import id.mydev.peoplecore.reimbursement.application.command.ReimbursementModels.ClaimPage;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;

@Service
public class ReimbursementWorkflowService {
    private final JdbcTemplate jdbc;
    private final EmployeeService employees;
    private final AccountAccessService access;
    private final ReimbursementSubmissionService submissions;
    private final ReimbursementCategoryService categories;
    private final EntityManager entities;
    private final AdmissionGuard admission;
    private final Clock clock;

    public ReimbursementWorkflowService(JdbcTemplate jdbc, EmployeeService employees, AccountAccessService access,
                                        ReimbursementSubmissionService submissions, ReimbursementCategoryService categories,
                                        EntityManager entities, AdmissionGuard admission, Clock clock) {
        this.jdbc = jdbc;
        this.employees = employees;
        this.access = access;
        this.submissions = submissions;
        this.categories = categories;
        this.entities = entities;
        this.admission = admission;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public View get(UUID claimPublicId, Authentication caller) {
        admission.acquire(AdmissionGuard.Mode.SHARED);
        entities.clear();
        UUID employeePublicId = jdbc.query("SELECT e.public_id FROM reimbursement_claim c JOIN employees e ON e.id=c.employee_id WHERE c.public_id=?",
            (rs, row) -> rs.getObject(1, UUID.class), claimPublicId).stream().findFirst().orElseThrow(ResourceNotFoundException::new);
        Employee employee = employees.requireInternalByPublicId(employeePublicId);
        Claim claim = claim(claimPublicId, employee, false);
        requireRead(claim, employee, caller);
        View result = view(claim, employee);
        entities.clear();
        Employee freshEmployee = employees.requireInternalByPublicId(employeePublicId);
        requireRead(claim(claimPublicId, freshEmployee, false), freshEmployee, caller);
        return result;
    }

    @Transactional(readOnly = true)
    public HistoryPage history(UUID claimPublicId, int limit, UUID cursor, Authentication caller) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Limit must be between 1 and 100");
        admission.acquire(AdmissionGuard.Mode.SHARED);
        entities.clear();
        UUID employeePublicId = jdbc.query("SELECT e.public_id FROM reimbursement_claim c JOIN employees e ON e.id=c.employee_id WHERE c.public_id=?",
            (rs, row) -> rs.getObject(1, UUID.class), claimPublicId).stream().findFirst().orElseThrow(ResourceNotFoundException::new);
        Employee employee = employees.requireInternalByPublicId(employeePublicId);
        Claim claim = claim(claimPublicId, employee, false);
        requireRead(claim, employee, caller);
        Long beforeId = null;
        if (cursor != null) {
            beforeId = jdbc.query("SELECT id FROM reimbursement_history WHERE claim_id=? AND public_id=?",
                (rs, row) -> rs.getLong(1), claim.id(), cursor).stream().findFirst().orElseThrow(ResourceNotFoundException::new);
        }
        List<History> rows = jdbc.query("SELECT public_id,revision,action,actor_public_id,reason,reviewer_public_id,review_step,created_at "
                + "FROM reimbursement_history WHERE claim_id=? AND (?::bigint IS NULL OR id<?) ORDER BY id DESC LIMIT ?",
            (rs, row) -> new History(rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3), rs.getObject(4, UUID.class),
                rs.getString(5), rs.getObject(6, UUID.class), rs.getObject(7, Integer.class), rs.getTimestamp(8).toInstant()),
            claim.id(), beforeId, beforeId, limit + 1);
        entities.clear();
        Employee freshEmployee = employees.requireInternalByPublicId(employeePublicId);
        requireRead(claim(claimPublicId, freshEmployee, false), freshEmployee, caller);
        boolean hasMore = rows.size() > limit;
        List<History> page = List.copyOf(hasMore ? rows.subList(0, limit) : rows);
        return new HistoryPage(page, hasMore ? page.getLast().publicId() : null);
    }

    @Transactional(readOnly = true)
    public ClaimPage list(int limit, UUID cursor, Authentication caller) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Limit must be between 1 and 100");
        admission.acquire(AdmissionGuard.Mode.SHARED);
        entities.clear();
        UserAccount actor = activeReader(caller);
        UUID ownEmployee = access.ownEmployeePublicId(actor).orElse(null);
        Timestamp at = Timestamp.from(clock.instant());
        List<Summary> rows = jdbc.query("""
            WITH actor AS (SELECT ?::bigint AS id, ?::uuid AS public_id, ?::uuid AS employee_id, ?::timestamptz AS at_time)
            SELECT c.public_id,e.public_id,c.status,c.revision,c.version,c.category_code,c.transaction_date,c.amount
            FROM reimbursement_claim c JOIN employees e ON e.id=c.employee_id CROSS JOIN actor a
            LEFT JOIN LATERAL (
                SELECT ea.org_unit FROM employee_assignments ea
                WHERE ea.employee_id=e.id AND ea.superseded_at IS NULL AND ea.valid_from<=a.at_time
                  AND (ea.valid_to IS NULL OR ea.valid_to>a.at_time)
                ORDER BY ea.valid_from DESC LIMIT 1
            ) org ON TRUE
            WHERE (?::uuid IS NULL OR c.public_id<?::uuid) AND (
                e.public_id=a.employee_id
                OR EXISTS (SELECT 1 FROM role_assignments role WHERE role.account_id=a.id
                    AND role.role IN ('HR_ADMIN','FINANCE_PAYROLL') AND role.effective_from<=a.at_time
                    AND (role.effective_to IS NULL OR role.effective_to>a.at_time)
                    AND (role.scope IS NULL OR btrim(role.scope)='' OR role.scope=org.org_unit))
                OR (c.status='PENDING' AND a.employee_id IS NOT NULL AND a.employee_id<>e.public_id
                    AND EXISTS (SELECT 1 FROM reimbursement_review_assignment ra
                        WHERE ra.claim_id=c.id AND ra.revision=c.revision AND ra.active
                          AND ra.reviewer_account_public_id=a.public_id AND ra.reviewer_employee_public_id=a.employee_id
                          AND ra.step=CASE WHEN EXISTS (SELECT 1 FROM reimbursement_decision d
                              WHERE d.claim_id=c.id AND d.revision=c.revision AND d.step=1 AND d.outcome='APPROVE')
                              THEN 2 ELSE 1 END
                          AND EXISTS (SELECT 1 FROM role_assignments role WHERE role.account_id=a.id
                              AND role.role=CASE WHEN ra.step=1 THEN 'MANAGER' ELSE 'FINANCE_PAYROLL' END
                              AND role.effective_from<=a.at_time AND (role.effective_to IS NULL OR role.effective_to>a.at_time)
                              AND (role.scope IS NULL OR btrim(role.scope)='' OR role.scope=org.org_unit))))
            ) ORDER BY c.public_id DESC LIMIT ?
            """, (rs, row) -> new Summary(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
            rs.getInt(4), rs.getLong(5), rs.getString(6), rs.getObject(7, LocalDate.class), rs.getLong(8)),
            actor.getId(), actor.getPublicId(), ownEmployee, at, cursor, cursor, limit + 1);
        entities.clear();
        activeReader(caller);
        for (Summary item : rows) {
            Employee employee = employees.requireInternalByPublicId(item.employeePublicId());
            requireRead(claim(item.publicId(), employee, false), employee, caller);
        }
        boolean hasMore = rows.size() > limit;
        List<Summary> page = List.copyOf(hasMore ? rows.subList(0, limit) : rows);
        return new ClaimPage(page, hasMore ? page.getLast().publicId() : null);
    }

    private UserAccount activeReader(Authentication caller) {
        UserAccount actor = access.requireActive(caller, clock.instant());
        if (access.activeRoles(actor.getId(), clock.instant()).isEmpty()) throw new ResourceNotFoundException();
        return actor;
    }

    private void requireRead(Claim claim, Employee employee, Authentication caller) {
        UserAccount actor = access.requireActive(caller, clock.instant());
        if (access.hasScopedRole(actor.getId(), HrisRole.FINANCE_PAYROLL, currentUnit(employee), clock.instant())) return;
        if ("PENDING".equals(claim.status())) {
            int step = activeStep(claim);
            Reviewer assigned = reviewer(claim, step);
            if (Objects.equals(assigned.account(), actor.getPublicId())) {
                Reviewer current = eligible(actor.getPublicId(), employee, step);
                if (Objects.equals(assigned.employee(), current.employee())) return;
            }
        }
        access.requireEmployeeRead(actor, employee.getPublicId(), currentUnit(employee), clock.instant());
    }

    @Transactional(readOnly = true)
    public void verifyCommand(String type, JsonNode payload, Authentication caller) {
        if ("REIMBURSEMENT_CATEGORY_PUBLISH".equals(type)) {
            categories.verifyAuthority(caller);
            return;
        }
        Employee employee = employees.requireInternalByPublicId(payloadUuid(payload, "employeePublicId"));
        switch (type) {
            case "REIMBURSEMENT_CREATE", "REIMBURSEMENT_EDIT", "REIMBURSEMENT_SUBMIT", "REIMBURSEMENT_RESUBMIT" ->
                requireEmployeeOwner(employee, caller);
            case "REIMBURSEMENT_WITHDRAW" -> requireOwner(employee, caller);
            case "REIMBURSEMENT_REASSIGN", "REIMBURSEMENT_RETRY_ROUTE" -> requireHr(employee, caller);
            case "REIMBURSEMENT_RECORD_PAYMENT", "REIMBURSEMENT_CORRECT_PAYMENT" -> requireFinance(employee, caller);
            case "REIMBURSEMENT_APPROVE", "REIMBURSEMENT_REJECT", "REIMBURSEMENT_RETURN" -> {
                Claim claim = claim(payloadUuid(payload, "claimPublicId"), employee, false);
                requireRead(claim, employee, caller);
                UserAccount actor = access.requireActive(caller, clock.instant());
                int step = activeStep(claim);
                Reviewer assigned = reviewer(claim, step);
                if (!Objects.equals(assigned.account(), actor.getPublicId())) throw new ResourceNotFoundException();
                Reviewer current = eligible(actor.getPublicId(), employee, step);
                if (!Objects.equals(assigned.employee(), current.employee())) throw new ResourceNotFoundException();
            }
            default -> throw new AccessDeniedException("Unknown reimbursement command");
        }
    }

    @Transactional(readOnly = true)
    public void verifyReplay(String type, JsonNode payload, JsonNode recordedResult, Authentication caller) {
        verifyCommand(type, payload, caller);
    }

    private static UUID payloadUuid(JsonNode payload, String field) {
        JsonNode node = payload == null ? null : payload.get(field);
        if (node == null || !node.isTextual()) throw new IllegalArgumentException("Resource identity is required");
        return UUID.fromString(node.asString());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public View create(UUID publicId, Draft draft, Authentication caller) {
        validateDraft(draft);
        Employee employee = lockEmployee(draft.employeePublicId());
        requireEmployeeOwner(employee, caller);
        jdbc.update("INSERT INTO reimbursement_claim(public_id,employee_id,status,category_code,transaction_date,amount,description,created_at) "
                + "VALUES (?,?,'DRAFT',?,?,?,?,?)", publicId, employee.getId(), draft.categoryCode(),
            draft.transactionDate(), draft.amount(), draft.description(), now());
        Claim claim = claim(publicId, employee, true);
        replaceEvidence(claim.id(), draft.evidence());
        recordHistory(claim, "CREATE", null, caller);
        return view(claim, employee);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public View edit(UUID publicId, Draft draft, Authentication caller) {
        validateDraft(draft);
        if (draft.expectedVersion() == null) throw new IllegalArgumentException("Expected version is required");
        Employee employee = lockEmployee(draft.employeePublicId());
        requireEmployeeOwner(employee, caller);
        Claim claim = claim(publicId, employee, true);
        requireVersion(claim, draft.expectedVersion());
        requireStatus(claim, "DRAFT", "RETURNED");
        jdbc.update("UPDATE reimbursement_claim SET category_code=?,transaction_date=?,amount=?,description=?,version=version+1 WHERE id=?",
            draft.categoryCode(), draft.transactionDate(), draft.amount(), draft.description(), claim.id());
        replaceEvidence(claim.id(), draft.evidence());
        Claim updated = claim(publicId, employee, false);
        recordHistory(updated, "EDIT", null, caller);
        return view(updated, employee);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public View act(UUID publicId, String action, Action payload, Authentication caller) {
        if (payload == null || payload.employeePublicId() == null || payload.expectedVersion() == null
            || payload.expectedVersion() < 0) {
            throw new IllegalArgumentException("Employee and expected version are required");
        }
        Employee employee = lockEmployee(payload.employeePublicId());
        Claim claim = claim(publicId, employee, true);
        requireVersion(claim, payload.expectedVersion());
        switch (action) {
            case "SUBMIT" -> {
                requireStatus(claim, "DRAFT");
                submissions.submit(publicId, employee.getPublicId(), payload.expectedVersion(), caller);
            }
            case "RESUBMIT" -> {
                requireStatus(claim, "RETURNED");
                submissions.submit(publicId, employee.getPublicId(), payload.expectedVersion(), caller);
            }
            case "WITHDRAW" -> {
                requireOwner(employee, caller);
                requireStatus(claim, "DRAFT", "RETURNED", "PENDING");
                transition(claim, "WITHDRAWN");
            }
            case "REASSIGN" -> reassign(claim, employee, payload, caller);
            case "RETRY_ROUTE" -> retryRoute(claim, employee, payload.reason(), caller);
            case "APPROVE", "REJECT", "RETURN" -> decide(claim, employee, action, payload.reason(), caller);
            case "RECORD_PAYMENT" -> recordPayment(claim, employee, payload, caller);
            case "CORRECT_PAYMENT" -> correctPayment(claim, employee, payload, caller);
            default -> throw new IllegalArgumentException("Unsupported reimbursement action");
        }
        Claim updated = claim(publicId, employee, false);
        if (!"REASSIGN".equals(action) && !"RETRY_ROUTE".equals(action)) {
            recordHistory(updated, action, payload.reason(), caller);
        }
        return view(updated, employee);
    }

    private void recordHistory(Claim claim, String action, String reason, Authentication caller) {
        UserAccount actor = access.requireActive(caller, clock.instant());
        jdbc.update("INSERT INTO reimbursement_history(public_id,claim_id,revision,action,actor_public_id,reason,created_at) "
                + "VALUES (?,?,?,?,?,?,?)", UUID.randomUUID(), claim.id(), claim.revision(), action, actor.getPublicId(), reason, now());
    }

    private void retryRoute(Claim claim, Employee employee, String reason, Authentication caller) {
        requireStatus(claim, "PENDING");
        requireHr(employee, caller);
        if (!StringUtils.hasText(reason)) throw new IllegalArgumentException("Reason is required");
        if (activeStep(claim) == 1) {
            UUID manager = employees.currentManagerPublicId(employee.getId(), clock.instant()).orElse(null);
            UUID accountId = manager == null ? null : access.accountBoundToEmployee(manager).map(UserAccount::getPublicId).orElse(null);
            Reviewer candidate = null;
            try {
                if (accountId != null) candidate = eligible(accountId, employee, 1);
            } catch (ResourceNotFoundException | ReimbursementConflictException ignored) {
                candidate = null;
            }
            Reviewer finance = reviewer(claim, 2);
            if (candidate != null && Objects.equals(candidate.employee(), finance.employee())) candidate = null;
            requireHr(employee, caller);
            jdbc.update("UPDATE reimbursement_review_assignment SET active=FALSE WHERE claim_id=? AND revision=? AND step=1 AND active",
                claim.id(), claim.revision());
            jdbc.update("INSERT INTO reimbursement_review_assignment(claim_id,revision,step,reviewer_account_public_id,reviewer_employee_public_id,created_at) VALUES (?,?,1,?,?,?)",
                claim.id(), claim.revision(), candidate == null ? null : candidate.account(), candidate == null ? null : candidate.employee(), now());
        }
        UserAccount actor = access.requireHrOperation(caller, clock.instant());
        access.requireTargetScope(actor.getId(), currentUnit(employee), clock.instant());
        jdbc.update("INSERT INTO reimbursement_history(public_id,claim_id,revision,action,actor_public_id,reason,created_at) VALUES (?,?,?,'RETRY_ROUTE',?,?,?)",
            UUID.randomUUID(), claim.id(), claim.revision(), actor.getPublicId(), reason, now());
        jdbc.update("UPDATE reimbursement_claim SET version=version+1 WHERE id=?", claim.id());
    }

    private void reassign(Claim claim, Employee employee, Action action, Authentication caller) {
        requireStatus(claim, "PENDING");
        UserAccount operator = access.requireHrOperation(caller, clock.instant());
        access.requireTargetScope(operator.getId(), currentUnit(employee), clock.instant());
        if (action.step() == null || action.step() < 1 || action.step() > 2 || action.reviewerAccountPublicId() == null
            || !StringUtils.hasText(action.reason())) throw new IllegalArgumentException("Reviewer, step and reason are required");
        Reviewer candidate = eligible(action.reviewerAccountPublicId(), employee, action.step());
        Reviewer other = reviewer(claim, action.step() == 1 ? 2 : 1);
        if (other.employee() != null && other.employee().equals(candidate.employee())) {
            throw new ReimbursementConflictException("DISTINCT_REVIEWERS_REQUIRED");
        }
        Integer decided = jdbc.queryForObject("SELECT count(id) FROM reimbursement_decision WHERE claim_id=? AND revision=? AND step=?",
            Integer.class, claim.id(), claim.revision(), action.step());
        if (decided != null && decided > 0) throw new ReimbursementConflictException("REVIEW_ALREADY_DECIDED");
        requireHr(employee, caller);
        jdbc.update("UPDATE reimbursement_review_assignment SET active=FALSE WHERE claim_id=? AND revision=? AND step=? AND active",
            claim.id(), claim.revision(), action.step());
        jdbc.update("INSERT INTO reimbursement_review_assignment(claim_id,revision,step,reviewer_account_public_id,reviewer_employee_public_id,created_at) VALUES (?,?,?,?,?,?)",
            claim.id(), claim.revision(), action.step(), candidate.account(), candidate.employee(), now());
        jdbc.update("INSERT INTO reimbursement_history(public_id,claim_id,revision,action,actor_public_id,reason,reviewer_public_id,review_step,created_at) VALUES (?,?,?,'REASSIGN',?,?,?,?,?)",
            UUID.randomUUID(), claim.id(), claim.revision(), operator.getPublicId(), action.reason(), candidate.account(), action.step(), now());
        jdbc.update("UPDATE reimbursement_claim SET version=version+1 WHERE id=?", claim.id());
    }

    private void decide(Claim claim, Employee employee, String outcome, String reason, Authentication caller) {
        requireStatus(claim, "PENDING");
        if (!"APPROVE".equals(outcome) && !StringUtils.hasText(reason)) throw new IllegalArgumentException("Reason is required");
        Reviewer manager = eligible(reviewer(claim, 1).account(), employee, 1);
        Reviewer finance = eligible(reviewer(claim, 2).account(), employee, 2);
        if (manager.employee().equals(finance.employee())) throw new ReimbursementConflictException("DISTINCT_REVIEWERS_REQUIRED");
        int step = activeStep(claim);
        Reviewer assigned = reviewer(claim, step);
        UserAccount actor = access.requireActive(caller, clock.instant());
        if (!Objects.equals(assigned.account(), actor.getPublicId())) throw new ResourceNotFoundException();
        Reviewer current = eligible(actor.getPublicId(), employee, step);
        if (!Objects.equals(assigned.employee(), current.employee())) throw new ResourceNotFoundException();
        if (step == 2) {
            UUID previous = jdbc.queryForObject("SELECT reviewer_employee_public_id FROM reimbursement_decision "
                + "WHERE claim_id=? AND revision=? AND step=1 AND outcome='APPROVE'", UUID.class, claim.id(), claim.revision());
            if (Objects.equals(previous, current.employee())) throw new ReimbursementConflictException("DISTINCT_REVIEWERS_REQUIRED");
        }
        eligible(actor.getPublicId(), employee, step);
        jdbc.update("INSERT INTO reimbursement_decision(claim_id,revision,step,reviewer_account_public_id,reviewer_employee_public_id,outcome,reason,created_at) VALUES (?,?,?,?,?,?,?,?)",
            claim.id(), claim.revision(), step, actor.getPublicId(), current.employee(), outcome, reason, now());
        if ("APPROVE".equals(outcome) && step == 1) {
            jdbc.update("UPDATE reimbursement_claim SET version=version+1 WHERE id=?", claim.id());
        } else {
            transition(claim, "APPROVE".equals(outcome) ? "APPROVED" : "RETURN".equals(outcome) ? "RETURNED" : "REJECTED");
        }
    }

    private void recordPayment(Claim claim, Employee employee, Action action, Authentication caller) {
        requireStatus(claim, "APPROVED");
        requireFinance(employee, caller);
        if (action.paidDate() == null || !StringUtils.hasText(action.paymentReference())) {
            throw new IllegalArgumentException("Payment date and reference are required");
        }
        UserAccount actor = requireFinance(employee, caller);
        jdbc.update("INSERT INTO reimbursement_payment(public_id,claim_id,revision,amount,paid_date,payment_reference,created_by,created_at) VALUES (?,?,?,?,?,?,?,?)",
            UUID.randomUUID(), claim.id(), claim.revision(), claim.amount(), action.paidDate(), action.paymentReference(), actor.getPublicId(), now());
        transition(claim, "PAID");
    }

    private void correctPayment(Claim claim, Employee employee, Action action, Authentication caller) {
        requireStatus(claim, "PAID");
        requireFinance(employee, caller);
        if (action.paidDate() == null || !StringUtils.hasText(action.paymentReference()) || !StringUtils.hasText(action.reason())) {
            throw new IllegalArgumentException("Payment date, reference and reason are required");
        }
        Payment payment = jdbc.query("SELECT id,paid_date,payment_reference FROM reimbursement_payment WHERE claim_id=? FOR UPDATE",
            (rs, row) -> new Payment(rs.getLong(1), rs.getObject(2, LocalDate.class), rs.getString(3)), claim.id())
            .stream().findFirst().orElseThrow(ResourceNotFoundException::new);
        List<Payment> corrections = jdbc.query("SELECT payment_id,corrected_paid_date,corrected_reference FROM reimbursement_payment_correction "
                + "WHERE payment_id=? ORDER BY id DESC LIMIT 1",
            (rs, row) -> new Payment(rs.getLong(1), rs.getObject(2, LocalDate.class), rs.getString(3)), payment.id());
        Payment previous = corrections.isEmpty() ? payment : corrections.getFirst();
        UserAccount actor = requireFinance(employee, caller);
        jdbc.update("INSERT INTO reimbursement_payment_correction(public_id,payment_id,prior_paid_date,prior_reference,corrected_paid_date,corrected_reference,reason,created_by,created_at) VALUES (?,?,?,?,?,?,?,?,?)",
            UUID.randomUUID(), payment.id(), previous.date(), previous.reference(), action.paidDate(), action.paymentReference(), action.reason(), actor.getPublicId(), now());
        jdbc.update("UPDATE reimbursement_claim SET version=version+1 WHERE id=?", claim.id());
    }

    private Employee lockEmployee(UUID id) {
        entities.flush();
        entities.clear();
        Employee employee = employees.requireInternalByPublicId(id);
        jdbc.update("INSERT INTO reimbursement_account(employee_id) VALUES (?) ON CONFLICT DO NOTHING", employee.getId());
        jdbc.queryForObject("SELECT employee_id FROM reimbursement_account WHERE employee_id=? FOR UPDATE", Long.class, employee.getId());
        entities.clear();
        return employees.requireInternalByPublicId(id);
    }

    private void requireOwner(Employee employee, Authentication caller) {
        UserAccount account = access.requireActive(caller, clock.instant());
        if (access.activeRoles(account.getId(), clock.instant()).isEmpty()
            || access.ownEmployeePublicId(account).filter(employee.getPublicId()::equals).isEmpty()) throw new ResourceNotFoundException();
    }

    private void requireEmployeeOwner(Employee employee, Authentication caller) {
        UserAccount account = access.requireActive(caller, clock.instant());
        if (!access.activeRoles(account.getId(), clock.instant()).contains(HrisRole.EMPLOYEE)
            || access.ownEmployeePublicId(account).filter(employee.getPublicId()::equals).isEmpty()) throw new ResourceNotFoundException();
    }

    private void requireHr(Employee employee, Authentication caller) {
        UserAccount actor = access.requireHrOperation(caller, clock.instant());
        access.requireTargetScope(actor.getId(), currentUnit(employee), clock.instant());
    }

    private UserAccount requireFinance(Employee employee, Authentication caller) {
        UserAccount actor = access.requireActive(caller, clock.instant());
        if (!access.hasScopedRole(actor.getId(), HrisRole.FINANCE_PAYROLL, currentUnit(employee), clock.instant())) {
            throw new AccessDeniedException("Scoped Finance authority required");
        }
        return actor;
    }

    private Reviewer eligible(UUID id, Employee employee, int step) {
        if (id == null) throw new ReimbursementConflictException("ROUTE_BLOCKED");
        UserAccount account = access.findAccountByPublicId(id).orElseThrow(ResourceNotFoundException::new);
        UUID bound = access.ownEmployeePublicId(account).orElseThrow(ResourceNotFoundException::new);
        String role = step == 1 ? HrisRole.MANAGER : HrisRole.FINANCE_PAYROLL;
        if (!account.isActiveAt(clock.instant()) || bound.equals(employee.getPublicId())
            || !access.hasScopedRole(account.getId(), role, currentUnit(employee), clock.instant())) {
            throw new ReimbursementConflictException("REVIEWER_INELIGIBLE");
        }
        return new Reviewer(id, bound);
    }

    private String currentUnit(Employee employee) {
        return employees.currentOrgUnit(employee.getId(), clock.instant()).orElse(null);
    }

    private Claim claim(UUID id, Employee employee, boolean lock) {
        return jdbc.query("SELECT id,public_id,status,revision,version,category_code,transaction_date,amount,description FROM reimbursement_claim "
                + "WHERE public_id=? AND employee_id=?" + (lock ? " FOR UPDATE" : ""),
            (rs, row) -> new Claim(rs.getLong(1), rs.getObject(2, UUID.class), rs.getString(3), rs.getInt(4),
                rs.getLong(5), rs.getString(6), rs.getObject(7, LocalDate.class), rs.getLong(8), rs.getString(9)), id, employee.getId())
            .stream().findFirst().orElseThrow(ResourceNotFoundException::new);
    }

    private Reviewer reviewer(Claim claim, int step) {
        return jdbc.query("SELECT reviewer_account_public_id,reviewer_employee_public_id FROM reimbursement_review_assignment "
                + "WHERE claim_id=? AND revision=? AND step=? AND active",
            (rs, row) -> new Reviewer(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)), claim.id(), claim.revision(), step)
            .stream().findFirst().orElse(new Reviewer(null, null));
    }

    private int activeStep(Claim claim) {
        Integer count = jdbc.queryForObject("SELECT count(id) FROM reimbursement_decision WHERE claim_id=? AND revision=? AND step=1 AND outcome='APPROVE'",
            Integer.class, claim.id(), claim.revision());
        return count != null && count > 0 ? 2 : 1;
    }

    private void transition(Claim claim, String status) {
        jdbc.update("UPDATE reimbursement_claim SET status=?,version=version+1 WHERE id=?", status, claim.id());
    }

    private void replaceEvidence(long claimId, List<UUID> evidence) {
        jdbc.update("DELETE FROM reimbursement_draft_evidence WHERE claim_id=?", claimId);
        for (UUID document : evidence) jdbc.update("INSERT INTO reimbursement_draft_evidence(claim_id,document_public_id) VALUES (?,?)", claimId, document);
    }

    private View view(Claim claim, Employee employee) {
        List<UUID> evidence = jdbc.query("SELECT document_public_id FROM reimbursement_draft_evidence WHERE claim_id=? ORDER BY document_public_id",
            (rs, row) -> rs.getObject(1, UUID.class), claim.id());
        String blocked = routeBlocked(claim, employee) ? "ROUTE_BLOCKED" : null;
        return new View(claim.publicId(), employee.getPublicId(), claim.status(), claim.revision(), claim.version(),
            claim.category(), claim.date(), claim.amount(), claim.description(), evidence, blocked);
    }

    private boolean routeBlocked(Claim claim, Employee employee) {
        if (!"PENDING".equals(claim.status())) return false;
        try {
            Reviewer assignedManager = reviewer(claim, 1);
            Reviewer assignedFinance = reviewer(claim, 2);
            Reviewer manager = eligible(assignedManager.account(), employee, 1);
            Reviewer finance = eligible(assignedFinance.account(), employee, 2);
            return !Objects.equals(assignedManager.employee(), manager.employee())
                || !Objects.equals(assignedFinance.employee(), finance.employee()) || manager.employee().equals(finance.employee());
        } catch (ResourceNotFoundException | ReimbursementConflictException ignored) {
            return true;
        }
    }

    private static void validateDraft(Draft draft) {
        if (draft == null || draft.employeePublicId() == null || !StringUtils.hasText(draft.categoryCode())
            || draft.transactionDate() == null || draft.amount() <= 0 || !StringUtils.hasText(draft.description())
            || draft.evidence() == null || draft.evidence().size() > 5 || draft.evidence().stream().anyMatch(Objects::isNull)
            || draft.evidence().stream().distinct().count() != draft.evidence().size()) {
            throw new IllegalArgumentException("Invalid reimbursement draft");
        }
    }

    private static void requireStatus(Claim claim, String... allowed) {
        for (String status : allowed) if (status.equals(claim.status())) return;
        throw new ReimbursementConflictException("CLAIM_STATE_CHANGED");
    }

    private static void requireVersion(Claim claim, long version) {
        if (claim.version() != version) throw new ReimbursementConflictException("CLAIM_VERSION_CHANGED");
    }

    private Timestamp now() { return Timestamp.from(clock.instant()); }
    private record Claim(long id, UUID publicId, String status, int revision, long version, String category,
                         LocalDate date, long amount, String description) { }
    private record Reviewer(UUID account, UUID employee) { }
    private record Payment(long id, LocalDate date, String reference) { }
}
