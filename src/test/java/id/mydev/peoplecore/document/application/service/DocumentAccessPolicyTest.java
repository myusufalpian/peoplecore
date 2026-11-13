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
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentAccessPolicyTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private final AccountAccessService access = mock(AccountAccessService.class);
    private final EmployeeService employees = mock(EmployeeService.class);
    private final Authentication caller = mock(Authentication.class);
    private final UserAccount account = mock(UserAccount.class);
    private final Employee employee = mock(Employee.class);
    private final UUID employeeId = UUID.randomUUID();
    private final DocumentAccessPolicy policy = new DocumentAccessPolicy(access, employees, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        when(access.requireActive(caller, NOW)).thenReturn(account);
        when(employees.requireInternalByPublicId(employeeId)).thenReturn(employee);
    }

    @Test
    void evidenceUploadRequiresTheBoundOwnerAndEmployeeRole() {
        when(account.getId()).thenReturn(1L);
        when(access.ownEmployeePublicId(account)).thenReturn(Optional.of(employeeId));
        when(access.hasRole(1L, HrisRole.EMPLOYEE, NOW)).thenReturn(true);
        assertSame(employee, policy.requireUpload(caller, employeeId, Purpose.CLAIM_EVIDENCE));

        when(access.ownEmployeePublicId(account)).thenReturn(Optional.of(UUID.randomUUID()));
        assertThrows(ResourceNotFoundException.class, () -> policy.requireUpload(caller, employeeId, Purpose.CLAIM_EVIDENCE));
    }

    @Test
    void payslipUploadRequiresAnEffectiveRoleCoveringTheEmployeesUnit() {
        when(account.getId()).thenReturn(1L);
        when(employee.getId()).thenReturn(2L);
        when(employees.currentOrgUnit(2L, NOW)).thenReturn(Optional.of("UNIT-A"));
        assertThrows(ResourceNotFoundException.class, () -> policy.requireUpload(caller, employeeId, Purpose.PAYSLIP));

        when(access.hasScopedRole(1L, HrisRole.FINANCE_PAYROLL, "UNIT-A", NOW)).thenReturn(true);
        assertSame(employee, policy.requireUpload(caller, employeeId, Purpose.PAYSLIP));
    }
}
