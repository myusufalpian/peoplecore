package id.mydev.peoplecore.document.application.service;

import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.document.domain.model.Purpose;
import id.mydev.peoplecore.identity.application.service.AccountAccessService;
import id.mydev.peoplecore.identity.domain.model.HrisRole;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.organization.application.service.EmployeeService;
import id.mydev.peoplecore.organization.domain.model.Employee;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
public class DocumentAccessPolicy {
    private final AccountAccessService access;
    private final EmployeeService employees;
    private final Clock clock;

    public DocumentAccessPolicy(AccountAccessService access, EmployeeService employees, Clock clock) {
        this.access = access;
        this.employees = employees;
        this.clock = clock;
    }

    public Employee requireUpload(Authentication caller, UUID employeePublicId, Purpose purpose) {
        Objects.requireNonNull(employeePublicId, "employeePublicId");
        Objects.requireNonNull(purpose, "purpose");
        Instant now = Instant.now(clock);
        UserAccount account = access.requireActive(caller, now);
        Employee employee = employees.requireInternalByPublicId(employeePublicId);
        if (purpose == Purpose.CLAIM_EVIDENCE) {
            boolean owner = access.ownEmployeePublicId(account).filter(employeePublicId::equals).isPresent();
            if (owner && access.hasRole(account.getId(), HrisRole.EMPLOYEE, now)) {
                return employee;
            }
        } else {
            String unit = employees.currentOrgUnit(employee.getId(), now).orElse(null);
            if (access.hasScopedRole(account.getId(), HrisRole.HR_ADMIN, unit, now)
                || access.hasScopedRole(account.getId(), HrisRole.FINANCE_PAYROLL, unit, now)) {
                return employee;
            }
        }
        throw new ResourceNotFoundException();
    }
}
