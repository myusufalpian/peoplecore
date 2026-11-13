package id.mydev.peoplecore.reimbursement.application.service;

import id.mydev.peoplecore.common.command.AdmissionGuard;
import id.mydev.peoplecore.identity.application.service.AccountAccessService;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.organization.application.service.EmployeeService;
import id.mydev.peoplecore.organization.domain.model.Employee;
import id.mydev.peoplecore.reimbursement.application.command.ReimbursementModels.Action;
import id.mydev.peoplecore.reimbursement.application.command.ReimbursementModels.Draft;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import id.mydev.peoplecore.reimbursement.domain.repository.ReimbursementWorkflowRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.AccessDeniedException;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import java.util.Optional;

class ReimbursementWorkflowValidationTest {
    @Test
    void commandAuthorizationValidatesIdentityAndCurrentOwnerGrant() throws Exception {
        EmployeeService employees = mock(EmployeeService.class);
        AccountAccessService access = mock(AccountAccessService.class);
        var service = new ReimbursementWorkflowService(mock(ReimbursementWorkflowRepository.class), employees, access,
            mock(ReimbursementSubmissionService.class), mock(ReimbursementCategoryService.class), mock(EntityManager.class),
            mock(AdmissionGuard.class), Clock.systemUTC());
        Authentication caller = mock(Authentication.class);
        UUID employeeId = UUID.randomUUID();
        Employee employee = mock(Employee.class);
        when(employee.getPublicId()).thenReturn(employeeId);
        when(employees.requireInternalByPublicId(employeeId)).thenReturn(employee);
        UserAccount account = mock(UserAccount.class);
        when(account.getId()).thenReturn(1L);
        when(access.requireActive(eq(caller), any())).thenReturn(account);
        when(access.ownEmployeePublicId(account)).thenReturn(Optional.of(employeeId));
        when(access.activeRoles(eq(1L), any())).thenReturn(List.of("MANAGER"));
        ObjectMapper mapper = new ObjectMapper();
        var payload = mapper.readTree("{\"employeePublicId\":\"" + employeeId + "\"}");
        assertThrows(IllegalArgumentException.class, () -> service.verifyCommand("REIMBURSEMENT_CREATE", null, caller));
        assertThrows(IllegalArgumentException.class, () -> service.verifyCommand("REIMBURSEMENT_CREATE", mapper.readTree("{}"), caller));
        assertThrows(IllegalArgumentException.class, () -> service.verifyCommand("REIMBURSEMENT_CREATE", mapper.readTree("{\"employeePublicId\":42}"), caller));
        assertThrows(AccessDeniedException.class, () -> service.verifyCommand("UNKNOWN", payload, caller));
        assertThrows(ResourceNotFoundException.class, () -> service.verifyCommand("REIMBURSEMENT_CREATE", payload, caller));
        assertDoesNotThrow(() -> service.verifyCommand("REIMBURSEMENT_WITHDRAW", payload, caller));
        when(access.activeRoles(eq(1L), any())).thenReturn(List.of());
        assertThrows(ResourceNotFoundException.class, () -> service.verifyCommand("REIMBURSEMENT_WITHDRAW", payload, caller));
        when(access.activeRoles(eq(1L), any())).thenReturn(List.of("EMPLOYEE"));
        assertDoesNotThrow(() -> service.verifyReplay("REIMBURSEMENT_CREATE", payload, null, caller));
    }

    @Test
    void rejectsMalformedDraftsAndMissingEditVersionBeforeDatabaseAccess() {
        ReimbursementWorkflowRepository jdbc = mock(ReimbursementWorkflowRepository.class);
        var service = new ReimbursementWorkflowService(jdbc, mock(EmployeeService.class), mock(AccountAccessService.class),
            mock(ReimbursementSubmissionService.class), mock(ReimbursementCategoryService.class), mock(EntityManager.class),
            mock(AdmissionGuard.class), Clock.systemUTC());
        Authentication caller = mock(Authentication.class);
        UUID employee = UUID.randomUUID();
        UUID claim = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> service.act(claim, "WITHDRAW", null, caller));
        assertThrows(IllegalArgumentException.class, () -> service.act(claim, "WITHDRAW",
            new Action(null, 0L, null, null, null, null, null), caller));
        LocalDate date = LocalDate.of(2026, 11, 1);
        UUID evidence = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> service.create(claim, null, caller));
        assertThrows(IllegalArgumentException.class, () -> service.create(claim,
            new Draft(null, "TRAVEL", date, 1, "Trip", List.of(), null), caller));
        assertThrows(IllegalArgumentException.class, () -> service.create(claim,
            new Draft(employee, " ", date, 1, "Trip", List.of(), null), caller));
        assertThrows(IllegalArgumentException.class, () -> service.create(claim,
            new Draft(employee, "TRAVEL", null, 1, "Trip", List.of(), null), caller));
        assertThrows(IllegalArgumentException.class, () -> service.create(claim,
            new Draft(employee, "TRAVEL", date, 0, "Trip", List.of(), null), caller));
        assertThrows(IllegalArgumentException.class, () -> service.create(claim,
            new Draft(employee, "TRAVEL", date, 1, " ", List.of(), null), caller));
        assertThrows(IllegalArgumentException.class, () -> service.create(claim,
            new Draft(employee, "TRAVEL", date, 1, "Trip", null, null), caller));
        assertThrows(IllegalArgumentException.class, () -> service.create(claim,
            new Draft(employee, "TRAVEL", date, 1, "Trip", Arrays.asList(evidence, null), null), caller));
        assertThrows(IllegalArgumentException.class, () -> service.create(claim,
            new Draft(employee, "TRAVEL", date, 1, "Trip", List.of(evidence, evidence), null), caller));
        assertThrows(IllegalArgumentException.class, () -> service.create(claim,
            new Draft(employee, "TRAVEL", date, 1, "Trip", List.of(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()), null), caller));
        assertThrows(IllegalArgumentException.class, () -> service.edit(claim,
            new Draft(employee, "TRAVEL", date, 1, "Trip", List.of(), null), caller));
        verifyNoInteractions(jdbc);
    }

    @Test
    void listAndHistoryRejectUnboundedPageSizesBeforeDatabaseAccess() {
        ReimbursementWorkflowRepository jdbc = mock(ReimbursementWorkflowRepository.class);
        var service = new ReimbursementWorkflowService(jdbc, mock(EmployeeService.class), mock(AccountAccessService.class),
            mock(ReimbursementSubmissionService.class), mock(ReimbursementCategoryService.class), mock(EntityManager.class),
            mock(AdmissionGuard.class), Clock.systemUTC());
        Authentication caller = mock(Authentication.class);
        UUID claim = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> service.list(0, null, caller));
        assertThrows(IllegalArgumentException.class, () -> service.list(101, null, caller));
        assertThrows(IllegalArgumentException.class, () -> service.history(claim, 0, null, caller));
        assertThrows(IllegalArgumentException.class, () -> service.history(claim, 101, null, caller));
        verifyNoInteractions(jdbc);
    }

    @Test
    void actionRequiresExplicitNonnegativeExpectedVersion() {
        ReimbursementWorkflowRepository jdbc = mock(ReimbursementWorkflowRepository.class);
        var service = new ReimbursementWorkflowService(jdbc, mock(EmployeeService.class), mock(AccountAccessService.class),
            mock(ReimbursementSubmissionService.class), mock(ReimbursementCategoryService.class), mock(EntityManager.class),
            mock(AdmissionGuard.class), Clock.systemUTC());
        Authentication caller = mock(Authentication.class);
        UUID employee = UUID.randomUUID();
        UUID claim = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> service.act(claim, "WITHDRAW",
            new Action(employee, null, null, null, null, null, null), caller));
        assertThrows(IllegalArgumentException.class, () -> service.act(claim, "WITHDRAW",
            new Action(employee, -1L, null, null, null, null, null), caller));
        verifyNoInteractions(jdbc);
    }
}
