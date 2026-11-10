package id.mydev.peoplecore.reimbursement.application.service;

import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.document.application.service.VerifiedDocumentAccess;
import id.mydev.peoplecore.document.application.service.VerifiedDocumentAccess.Purpose;
import id.mydev.peoplecore.document.application.service.VerifiedDocumentAccess.Request;
import id.mydev.peoplecore.identity.application.service.AccountAccessService;
import id.mydev.peoplecore.identity.domain.model.HrisRole;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.organization.application.service.EmployeeService;
import id.mydev.peoplecore.organization.domain.model.Employee;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReimbursementSubmissionService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Jakarta");
    private final JdbcTemplate jdbc;
    private final AccountAccessService access;
    private final EmployeeService employees;
    private final VerifiedDocumentAccess documents;
    private final Clock clock;
    private final EntityManager entities;
    private final ReimbursementSubmissionPolicy policy = new ReimbursementSubmissionPolicy();

    public ReimbursementSubmissionService(JdbcTemplate jdbc, AccountAccessService access, EmployeeService employees,
                                          VerifiedDocumentAccess documents, Clock clock, EntityManager entities) {
        this.jdbc = jdbc;
        this.access = access;
        this.employees = employees;
        this.documents = documents;
        this.clock = clock;
        this.entities = entities;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void submit(UUID claimPublicId, UUID employeePublicId, long expectedVersion, Authentication caller) {
        Employee employee = employees.requireInternalByPublicId(employeePublicId);
        requireOwner(employeePublicId, caller);
        jdbc.update("INSERT INTO reimbursement_account(employee_id) VALUES (?) ON CONFLICT DO NOTHING", employee.getId());
        jdbc.queryForObject("SELECT employee_id FROM reimbursement_account WHERE employee_id=? FOR UPDATE", Long.class, employee.getId());
        entities.flush();
        entities.clear();
        employee = employees.requireInternalByPublicId(employeePublicId);
        requireOwner(employeePublicId, caller);
        Claim claim = jdbc.query("SELECT id,status,revision,version,category_code,transaction_date,amount,description "
                + "FROM reimbursement_claim WHERE public_id=? AND employee_id=? FOR UPDATE",
            (rs, row) -> new Claim(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getLong(4), rs.getString(5),
                rs.getObject(6, LocalDate.class), rs.getLong(7), rs.getString(8)), claimPublicId, employee.getId())
            .stream().findFirst().orElseThrow(ResourceNotFoundException::new);
        if (claim.version() != expectedVersion || !("DRAFT".equals(claim.status()) || "RETURNED".equals(claim.status()))) {
            throw new ReimbursementConflictException("CLAIM_CHANGED");
        }
        List<UUID> evidence = jdbc.query("SELECT document_public_id FROM reimbursement_draft_evidence WHERE claim_id=? ORDER BY document_public_id",
            (rs, row) -> rs.getObject(1, UUID.class), claim.id());
        Category category = jdbc.query("SELECT id,claim_limit FROM reimbursement_category_revision "
                + "WHERE category_code=? AND NOT superseded AND effective_from<=? AND (effective_to IS NULL OR effective_to>?) FOR SHARE",
            (rs, row) -> new Category(rs.getLong(1), rs.getLong(2)), claim.category(), claim.date(), claim.date())
            .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("No applicable reimbursement category"));
        policy.validate(new ReimbursementSubmissionPolicy.Submission(claim.date(), employee.getEmploymentStartDate(),
            employee.getEmploymentEndDate(), claim.amount(), category.limit(), claim.description(), evidence),
            LocalDate.now(clock.withZone(BUSINESS_ZONE)));
        List<VerifiedDocumentAccess.VerifiedVersion> versions = documents.requireAllForUpdate(evidence.stream()
            .map(id -> new Request(id, employeePublicId, Purpose.CLAIM_EVIDENCE)).toList());
        entities.clear();
        employee = employees.requireInternalByPublicId(employeePublicId);
        requireOwner(employeePublicId, caller);
        policy.validate(new ReimbursementSubmissionPolicy.Submission(claim.date(), employee.getEmploymentStartDate(),
            employee.getEmploymentEndDate(), claim.amount(), category.limit(), claim.description(), evidence),
            LocalDate.now(clock.withZone(BUSINESS_ZONE)));
        String unit = employees.currentOrgUnit(employee.getId(), clock.instant())
            .orElseThrow(() -> new IllegalArgumentException("Organization assignment is required"));
        UUID manager = employees.currentManagerPublicId(employee.getId(), clock.instant()).orElse(null);
        UserAccount managerAccount = manager == null ? null : access.accountBoundToEmployee(manager).orElse(null);
        if (managerAccount != null && (manager.equals(employeePublicId) || !managerAccount.isActiveAt(clock.instant())
            || !access.hasScopedRole(managerAccount.getId(), HrisRole.MANAGER, unit, clock.instant()))) {
            managerAccount = null;
        }
        int revision = claim.revision() + 1;
        jdbc.update("INSERT INTO reimbursement_revision(claim_id,revision,category_revision_id,transaction_date,amount,description,org_unit,manager_employee_public_id,submitted_at) "
                + "VALUES (?,?,?,?,?,?,?,?,?)", claim.id(), revision, category.id(), claim.date(), claim.amount(),
            claim.description(), unit, manager, Timestamp.from(clock.instant()));
        for (var version : versions) {
            jdbc.update("INSERT INTO reimbursement_evidence(claim_id,revision,document_version_id) VALUES (?,?,?)",
                claim.id(), revision, version.versionId());
        }
        jdbc.update("UPDATE reimbursement_revision SET sealed=TRUE WHERE claim_id=? AND revision=?", claim.id(), revision);
        jdbc.update("INSERT INTO reimbursement_review_assignment(claim_id,revision,step,reviewer_account_public_id,reviewer_employee_public_id,created_at) VALUES (?,?,1,?,?,?),(?,?,2,NULL,NULL,?)",
            claim.id(), revision, managerAccount == null ? null : managerAccount.getPublicId(),
            managerAccount == null ? null : manager, Timestamp.from(clock.instant()), claim.id(), revision, Timestamp.from(clock.instant()));
        jdbc.update("UPDATE reimbursement_claim SET status='PENDING',revision=?,version=version+1 WHERE id=?",
            revision, claim.id());
    }

    private void requireOwner(UUID employeePublicId, Authentication caller) {
        UserAccount account = access.requireActive(caller, clock.instant());
        if (!access.activeRoles(account.getId(), clock.instant()).contains(HrisRole.EMPLOYEE)
            || access.ownEmployeePublicId(account).filter(employeePublicId::equals).isEmpty()) {
            throw new ResourceNotFoundException();
        }
    }

    private record Claim(long id, String status, int revision, long version, String category, LocalDate date,
                         long amount, String description) { }
    private record Category(long id, long limit) { }
}
