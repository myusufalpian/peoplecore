package id.mydev.peoplecore.reimbursement.application.service;

import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.document.application.service.VerifiedDocumentAccess;
import id.mydev.peoplecore.document.domain.model.Purpose;
import id.mydev.peoplecore.document.application.service.VerifiedDocumentAccess.Request;
import id.mydev.peoplecore.identity.application.service.AccountAccessService;
import id.mydev.peoplecore.identity.domain.model.HrisRole;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.organization.application.service.EmployeeService;
import id.mydev.peoplecore.organization.domain.model.Employee;
import id.mydev.peoplecore.reimbursement.domain.repository.ReimbursementSubmissionRepository;
import id.mydev.peoplecore.reimbursement.domain.repository.ReimbursementSubmissionRepository.CategoryLimitRecord;
import id.mydev.peoplecore.reimbursement.domain.repository.ReimbursementSubmissionRepository.ClaimRecord;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReimbursementSubmissionService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Jakarta");
    private final ReimbursementSubmissionRepository submissions;
    private final AccountAccessService access;
    private final EmployeeService employees;
    private final VerifiedDocumentAccess documents;
    private final Clock clock;
    private final EntityManager entities;
    private final ReimbursementSubmissionPolicy policy = new ReimbursementSubmissionPolicy();

    public ReimbursementSubmissionService(ReimbursementSubmissionRepository submissions, AccountAccessService access,
                                          EmployeeService employees, VerifiedDocumentAccess documents, Clock clock,
                                          EntityManager entities) {
        this.submissions = submissions;
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
        submissions.ensureAccount(employee.getId());
        submissions.lockAccount(employee.getId());
        entities.flush();
        entities.clear();
        employee = employees.requireInternalByPublicId(employeePublicId);
        requireOwner(employeePublicId, caller);
        ClaimRecord claim = submissions.lockClaim(claimPublicId, employee.getId())
            .orElseThrow(ResourceNotFoundException::new);
        if (claim.version() != expectedVersion || !("DRAFT".equals(claim.status()) || "RETURNED".equals(claim.status()))) {
            throw new ReimbursementConflictException("CLAIM_CHANGED");
        }
        List<UUID> evidence = submissions.findDraftEvidence(claim.id());
        CategoryLimitRecord category = submissions.lockApplicableCategory(claim.category(), claim.date())
            .orElseThrow(() -> new IllegalArgumentException("No applicable reimbursement category"));
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
        Instant now = clock.instant();
        submissions.insertRevision(claim.id(), revision, category.id(), claim.date(), claim.amount(),
            claim.description(), unit, manager, now);
        for (var version : versions) {
            submissions.insertEvidence(claim.id(), revision, version.versionId());
        }
        submissions.sealRevision(claim.id(), revision);
        submissions.insertReviewAssignments(claim.id(), revision,
            managerAccount == null ? null : managerAccount.getPublicId(),
            managerAccount == null ? null : manager, now);
        submissions.markSubmitted(claim.id(), revision);
    }

    private void requireOwner(UUID employeePublicId, Authentication caller) {
        UserAccount account = access.requireActive(caller, clock.instant());
        if (!access.activeRoles(account.getId(), clock.instant()).contains(HrisRole.EMPLOYEE)
            || access.ownEmployeePublicId(account).filter(employeePublicId::equals).isEmpty()) {
            throw new ResourceNotFoundException();
        }
    }
}
