package id.mydev.peoplecore.organization.application.service;

import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.identity.application.service.AccountAccessService;
import id.mydev.peoplecore.identity.application.service.AuthGenerationService;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.organization.application.command.EmployeeResults.AssignmentResult;
import id.mydev.peoplecore.organization.application.command.EmployeeResults.EmployeeResult;
import id.mydev.peoplecore.organization.application.command.EmployeeResults.EmployeeState;
import id.mydev.peoplecore.organization.application.command.EmployeeResults.AssignmentState;
import id.mydev.peoplecore.organization.application.mapper.EmployeeResultMapper;
import id.mydev.peoplecore.organization.application.policy.OrgUnitPolicy;
import id.mydev.peoplecore.organization.domain.exception.AssignmentOverlapException;
import id.mydev.peoplecore.organization.domain.exception.AssignmentValidationException;
import id.mydev.peoplecore.organization.domain.exception.EmployeeNumberConflictException;
import id.mydev.peoplecore.organization.domain.model.Employee;
import id.mydev.peoplecore.organization.domain.model.EmployeeAssignment;
import id.mydev.peoplecore.organization.domain.repository.EmployeeAssignmentRepository;
import id.mydev.peoplecore.organization.domain.repository.AssignmentHistoryRow;
import id.mydev.peoplecore.organization.domain.repository.EmployeeRepository;
import jakarta.persistence.EntityManager;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class EmployeeService {

    private final EmployeeRepository employees;
    private final EmployeeAssignmentRepository assignments;
    private final AccountAccessService access;
    private final AuthGenerationService generations;
    private final OrgUnitPolicy orgUnits;
    private final EntityManager entities;
    private final Clock clock;

    public EmployeeService(
        EmployeeRepository employees,
        EmployeeAssignmentRepository assignments,
        AccountAccessService access,
        AuthGenerationService generations,
        OrgUnitPolicy orgUnits,
        EntityManager entities,
        Clock clock
    ) {
        this.employees = employees;
        this.assignments = assignments;
        this.access = access;
        this.generations = generations;
        this.orgUnits = orgUnits;
        this.entities = entities;
        this.clock = clock;
    }

    @Transactional
    public EmployeeResult create(UUID publicId, String employeeNumber, Authentication caller) {
        Objects.requireNonNull(publicId, "publicId must not be null");
        Instant now = Instant.now(clock);
        access.requireHrScope(caller, null, now);
        if (!StringUtils.hasText(employeeNumber)) {
            throw new IllegalArgumentException("employeeNumber must not be blank");
        }
        if (employees.findByEmployeeNumber(employeeNumber).isPresent()) {
            throw new EmployeeNumberConflictException("Employee number is already used");
        }
        Employee employee = new Employee(publicId, employeeNumber, now);
        employees.save(employee);
        generations.increment();
        return EmployeeResultMapper.result(employee, null);
    }

    @Transactional
    public EmployeeResult updateNumber(UUID publicId, String employeeNumber, Authentication caller) {
        Objects.requireNonNull(publicId, "publicId must not be null");
        Instant now = Instant.now(clock);
        UserAccount hr = access.requireHrOperation(caller, now);
        if (!StringUtils.hasText(employeeNumber)) {
            throw new IllegalArgumentException("employeeNumber must not be blank");
        }
        Employee employee = employees.findByPublicId(publicId)
            .orElseThrow(ResourceNotFoundException::new);
        access.requireTargetScope(hr.getId(), currentOrgUnit(employee.getId(), now).orElse(null), now);
        employees.findByEmployeeNumber(employeeNumber).ifPresent(other -> {
            if (!other.getId().equals(employee.getId())) {
                throw new EmployeeNumberConflictException("Employee number is already used");
            }
        });
        Employee locked = employees.lockById(employee.getId())
            .orElseThrow(ResourceNotFoundException::new);
        recheckTarget(caller, currentOrgUnit(locked.getId(), Instant.now(clock)).orElse(null));
        locked = employees.findById(employee.getId()).orElseThrow(ResourceNotFoundException::new);
        EmployeeState before = EmployeeResultMapper.state(locked);
        locked.changeEmployeeNumber(employeeNumber);
        employees.save(locked);
        generations.increment();
        return EmployeeResultMapper.result(locked, before);
    }

    @Transactional
    public EmployeeResult updateEmploymentDates(UUID publicId, LocalDate startDate, LocalDate endDate,
                                                Authentication caller) {
        Instant now = Instant.now(clock);
        UserAccount hr = access.requireHrOperation(caller, now);
        Employee preview = requireInternalByPublicId(publicId);
        access.requireTargetScope(hr.getId(), currentOrgUnit(preview.getId(), now).orElse(null), now);
        employees.lockById(preview.getId()).orElseThrow(ResourceNotFoundException::new);
        recheckTarget(caller, currentOrgUnit(preview.getId(), Instant.now(clock)).orElse(null));
        Employee employee = employees.findById(preview.getId()).orElseThrow(ResourceNotFoundException::new);
        EmployeeState before = EmployeeResultMapper.state(employee);
        employee.changeEmploymentDates(startDate, endDate);
        employees.save(employee);
        generations.increment();
        return EmployeeResultMapper.result(employee, before);
    }

    @Transactional(readOnly = true)
    public Employee get(UUID publicId, Authentication caller) {
        Objects.requireNonNull(publicId, "publicId must not be null");
        Instant now = Instant.now(clock);
        UserAccount account = access.requireActive(caller, now);
        Employee employee = employees.findByPublicId(publicId)
            .orElseThrow(ResourceNotFoundException::new);
        requireReadScope(employee, account, currentOrgUnit(employee.getId(), now).orElse(null), now);
        return employee;
    }

    private void requireReadScope(Employee employee, UserAccount account, String orgUnit, Instant now) {
        if (access.coversUnit(account.getId(), orgUnit, now)) {
            return;
        }
        if (access.activeRoles(account.getId(), now).isEmpty()) {
            throw new ResourceNotFoundException();
        }
        UUID own = access.ownEmployeePublicId(account).orElse(null);
        if (!employee.getPublicId().equals(own)) {
            throw new ResourceNotFoundException();
        }
    }

    @Transactional
    public AssignmentResult addAssignment(UUID employeePublicId, UUID assignmentPublicId, String orgUnit,
                                          UUID managerPublicId, String jobLevel,
                                          Instant validFrom, Instant validTo, Authentication caller) {
        Objects.requireNonNull(employeePublicId, "employeePublicId must not be null");
        Objects.requireNonNull(assignmentPublicId, "assignmentPublicId must not be null");
        Instant now = Instant.now(clock);
        UserAccount hr = access.requireHrOperation(caller, now);
        if (!StringUtils.hasText(orgUnit) || !StringUtils.hasText(jobLevel)) {
            throw new IllegalArgumentException("orgUnit and jobLevel must not be blank");
        }
        orgUnits.requireAllowed(orgUnit);
        validFrom = storedBoundary(validFrom);
        validTo = storedBoundary(validTo);
        if (validFrom == null) {
            throw new AssignmentValidationException("Assignment start must not be null");
        }
        if (validTo != null && !validFrom.isBefore(validTo)) {
            throw new AssignmentValidationException("Assignment end must be after its start");
        }
        Employee employee = employees.findByPublicId(employeePublicId)
            .orElseThrow(ResourceNotFoundException::new);
        requireAssignmentScope(hr.getId(), currentOrgUnit(employee.getId(), now).orElse(null), orgUnit, now);
        employees.lockById(employee.getId()).orElseThrow(ResourceNotFoundException::new);
        refreshTargetScope(caller, currentOrgUnit(employee.getId(), Instant.now(clock)).orElse(null), orgUnit);
        Employee locked = employees.findById(employee.getId()).orElseThrow(ResourceNotFoundException::new);
        Long managerId = resolveManager(managerPublicId, locked.getId());
        List<EmployeeAssignment> existing = assignments.findByEmployeeIdAndSupersededAtIsNullOrderByValidFromAsc(locked.getId());
        for (EmployeeAssignment assignment : existing) {
            if (assignment.overlaps(validFrom, validTo)) {
                throw new AssignmentOverlapException("Assignment period overlaps an existing assignment");
            }
        }
        EmployeeAssignment created = new EmployeeAssignment(assignmentPublicId, locked.getId(),
            orgUnit, managerId, jobLevel, validFrom, validTo, Instant.now(clock));
        assignments.save(created);
        generations.increment();
        return EmployeeResultMapper.result(created, locked.getPublicId(), managerPublicId, null, null);
    }

    @Transactional
    public AssignmentResult reviseAssignment(UUID employeeId, UUID assignmentId, UUID replacementId,
                                              String orgUnit, UUID managerId, String jobLevel,
                                              Instant validFrom, Instant validTo, Authentication caller) {
        Instant now = Instant.now(clock);
        UserAccount hr = access.requireHrOperation(caller, now);
        Employee employee = requireInternalByPublicId(employeeId);
        requireAssignmentScope(hr.getId(), currentOrgUnit(employee.getId(), now).orElse(null), orgUnit, now);
        orgUnits.requireAllowed(orgUnit);
        validFrom = storedBoundary(validFrom);
        validTo = storedBoundary(validTo);
        if (!StringUtils.hasText(jobLevel) || validFrom == null
            || (validTo != null && !validFrom.isBefore(validTo))) {
            throw new AssignmentValidationException("Job level and a valid assignment interval are required");
        }
        employees.lockById(employee.getId()).orElseThrow(ResourceNotFoundException::new);
        refreshTargetScope(caller, currentOrgUnit(employee.getId(), Instant.now(clock)).orElse(null), orgUnit);
        EmployeeAssignment original = assignments.findByPublicId(assignmentId)
            .filter(row -> row.getEmployeeId().equals(employee.getId()))
            .orElseThrow(ResourceNotFoundException::new);
        UserAccount currentHr = access.requireHrOperation(caller, Instant.now(clock));
        access.requireTargetScope(currentHr.getId(), original.getOrgUnit(), Instant.now(clock));
        if (original.getSupersededAt() != null) {
            throw new AssignmentOverlapException("Assignment was already revised; reload its current revision");
        }
        if (validFrom.isBefore(original.getValidFrom())
            || (original.getValidTo() != null && !validFrom.isBefore(original.getValidTo()))) {
            throw new AssignmentValidationException("Revision must start within the original interval");
        }
        Long internalManager = resolveManager(managerId, employee.getId());
        for (EmployeeAssignment row : assignments.findByEmployeeIdAndSupersededAtIsNullOrderByValidFromAsc(employee.getId())) {
            if (!row.getPublicId().equals(assignmentId) && row.overlaps(validFrom, validTo)) {
                throw new AssignmentOverlapException("Revision overlaps another assignment");
            }
        }
        AssignmentState before = EmployeeResultMapper.state(original, managerPublicId(original));
        Instant revisedAt = Instant.now(clock);
        original.supersede(revisedAt);
        assignments.save(original);
        UUID retainedId = retainEarlierInterval(original, validFrom, revisedAt);
        EmployeeAssignment replacement = new EmployeeAssignment(replacementId, employee.getId(), orgUnit,
            internalManager, jobLevel, validFrom, validTo, revisedAt);
        replacement.recordPredecessor(assignmentId);
        assignments.save(replacement);
        generations.increment();
        return EmployeeResultMapper.result(replacement, employeeId, managerId, before, retainedId);
    }

    private static Instant storedBoundary(Instant value) {
        return value == null ? null : value.truncatedTo(ChronoUnit.MICROS);
    }

    private UUID retainEarlierInterval(EmployeeAssignment original, Instant boundary, Instant revisedAt) {
        if (!original.getValidFrom().isBefore(boundary)) {
            return null;
        }
        EmployeeAssignment retained = new EmployeeAssignment(UUID.randomUUID(), original.getEmployeeId(),
            original.getOrgUnit(), original.getManagerEmployeeId(), original.getJobLevel(),
            original.getValidFrom(), boundary, revisedAt);
        retained.recordPredecessor(original.getPublicId());
        assignments.save(retained);
        return retained.getPublicId();
    }

    @Transactional(readOnly = true)
    public List<AssignmentResult> assignmentHistory(UUID employeeId, Authentication caller) {
        Objects.requireNonNull(employeeId, "employeeId must not be null");
        Instant now = Instant.now(clock);
        UserAccount account = access.requireActive(caller, now);
        Employee employee = employees.findByPublicId(employeeId).orElseThrow(ResourceNotFoundException::new);
        List<AssignmentHistoryRow> history = assignments.findHistory(employee.getId());
        String currentUnit = history.stream()
            .filter(row -> row.supersededAt() == null && !row.validFrom().isAfter(now)
                && (row.validTo() == null || now.isBefore(row.validTo())))
            .max(Comparator.comparing(AssignmentHistoryRow::validFrom))
            .map(AssignmentHistoryRow::orgUnit).orElse(null);
        requireReadScope(employee, account, currentUnit, now);
        return history.stream()
            .map(row -> EmployeeResultMapper.result(row, employeeId))
            .toList();
    }

    private UUID managerPublicId(EmployeeAssignment assignment) {
        if (assignment.getManagerEmployeeId() == null) {
            return null;
        }
        return employees.findById(assignment.getManagerEmployeeId()).map(Employee::getPublicId)
            .orElseThrow(() -> new IllegalStateException("Assignment manager is missing"));
    }

    @Transactional(readOnly = true)
    public List<EmployeeAssignment> listAssignments(UUID employeePublicId, Authentication caller) {
        Employee employee = get(employeePublicId, caller);
        return assignments.findByEmployeeIdOrderByValidFromAsc(employee.getId());
    }

    @Transactional(readOnly = true)
    public Optional<String> currentOrgUnit(Long employeeId, Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        List<EmployeeAssignment> history = assignments.findByEmployeeIdAndSupersededAtIsNullOrderByValidFromAsc(employeeId);
        String current = null;
        Instant currentFrom = null;
        for (EmployeeAssignment assignment : history) {
            if (assignment.getValidFrom().isAfter(now)) {
                continue;
            }
            if (assignment.getValidTo() != null && !now.isBefore(assignment.getValidTo())) {
                continue;
            }
            if (currentFrom == null || assignment.getValidFrom().isAfter(currentFrom)) {
                current = assignment.getOrgUnit();
                currentFrom = assignment.getValidFrom();
            }
        }
        return Optional.ofNullable(current);
    }

    @Transactional
    public Employee markOffboarded(Long employeeId) {
        Employee employee = employees.lockById(employeeId)
            .orElseThrow(ResourceNotFoundException::new);
        employee.deactivate();
        employees.save(employee);
        return employee;
    }

    @Transactional
    public Employee markRehired(Long employeeId) {
        Employee employee = employees.lockById(employeeId)
            .orElseThrow(ResourceNotFoundException::new);
        employee.reactivate();
        employees.save(employee);
        return employee;
    }

    @Transactional
    public Optional<Employee> lockEmployee(Long employeeId) {
        return employees.lockById(employeeId);
    }

    @Transactional
    public Employee saveEmployee(Employee employee) {
        return employees.save(Objects.requireNonNull(employee, "employee must not be null"));
    }

    @Transactional(readOnly = true)
    public Employee requireInternalByPublicId(UUID publicId) {
        return employees.findByPublicId(publicId).orElseThrow(ResourceNotFoundException::new);
    }

    @Transactional(readOnly = true)
    public Optional<Employee> findByPublicId(UUID publicId) {
        return employees.findByPublicId(publicId);
    }

    @Transactional(readOnly = true)
    public Optional<Employee> findInternalById(Long id) {
        return employees.findById(id);
    }

    private Long resolveManager(UUID managerPublicId, Long employeeId) {
        if (managerPublicId == null) {
            return null;
        }
        Employee manager = employees.findByPublicId(managerPublicId)
            .orElseThrow(ResourceNotFoundException::new);
        if (manager.getId().equals(employeeId)) {
            throw new AssignmentValidationException("Employee cannot manage themselves");
        }
        return manager.getId();
    }

    private void requireAssignmentScope(Long accountId, String currentUnit, String requestedUnit, Instant now) {
        if (currentUnit != null) {
            access.requireTargetScope(accountId, currentUnit, now);
        }
        access.requireTargetScope(accountId, requestedUnit, now);
    }

    private void recheckTarget(Authentication caller, String targetOrgUnit) {
        refreshEntities();
        Instant now = Instant.now(clock);
        UserAccount hr = access.requireHrOperation(caller, now);
        access.requireTargetScope(hr.getId(), targetOrgUnit, now);
    }

    private void refreshTargetScope(Authentication caller, String currentUnit, String requestedUnit) {
        refreshEntities();
        Instant now = Instant.now(clock);
        UserAccount hr = access.requireHrOperation(caller, now);
        requireAssignmentScope(hr.getId(), currentUnit, requestedUnit, now);
    }

    private void refreshEntities() {
        entities.flush();
        entities.clear();
    }
}
