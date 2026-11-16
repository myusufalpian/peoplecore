package id.mydev.peoplecore.identity.application.service;

import id.mydev.peoplecore.common.audit.AuditEvent;
import id.mydev.peoplecore.common.audit.AuditEventWriter;
import id.mydev.peoplecore.common.command.AdmissionGuard;
import id.mydev.peoplecore.common.command.CommandConflictException;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.identity.domain.repository.NotificationIdentityRepository;
import id.mydev.peoplecore.notification.domain.NotificationModels.Destination;
import id.mydev.peoplecore.organization.application.service.EmployeeService;
import id.mydev.peoplecore.organization.domain.model.Employee;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationDestinationServiceTest {
    private static final Instant NOW=Instant.parse("2026-11-13T10:00:00Z");
    private final NotificationIdentityRepository repository=mock(NotificationIdentityRepository.class);
    private final AccountAccessService access=mock(AccountAccessService.class);
    private final EmployeeService employees=mock(EmployeeService.class);
    private final AuditEventWriter audit=mock(AuditEventWriter.class);
    private final AdmissionGuard admission=mock(AdmissionGuard.class);
    private final Authentication caller=mock(Authentication.class);
    private final UUID employeeId=UUID.randomUUID();
    private final NotificationDestinationService service=new NotificationDestinationService(repository,access,employees,audit,
        Clock.fixed(NOW,ZoneOffset.UTC),admission,new ObjectMapper());

    @Test void independentlyVerifiedDestinationIsAuditedWithoutStoringAddressInReceiptOrAudit() {
        ready();
        Destination destination=new Destination(UUID.randomUUID(),employeeId,"verified@example.test",0,"VERIFIED");
        when(repository.verifyDestination(employeeId,"verified@example.test","idp-record","identity-operator",NOW)).thenReturn(destination);
        assertEquals(destination,service.verify(employeeId,"verified@example.test","idp-record","Verified corporate record","key",caller));
        var event=ArgumentCaptor.forClass(AuditEvent.class); verify(audit).append(event.capture());
        assertFalse(event.getValue().getDetails().contains("verified@example.test"));
        assertEquals("NOTIFICATION_DESTINATION_VERIFIED",event.getValue().getAction());
        var hash=ArgumentCaptor.forClass(String.class);
        verify(repository).recordVerification(eq("identity-operator"),eq("key"),hash.capture(),eq(destination),eq(NOW));
        when(repository.verificationOperation("identity-operator","key")).thenReturn(Optional.of(new NotificationIdentityRepository.VerificationOperation(hash.getValue(),destination.id(),destination.version())));
        var replay=service.verify(employeeId,"verified@example.test","idp-record","Verified corporate record","key",caller);
        assertEquals(destination.id(),replay.id()); assertEquals("",replay.address());
        verify(repository,times(1)).verifyDestination(any(),anyString(),anyString(),anyString(),any());
        assertThrows(CommandConflictException.class,()->service.verify(employeeId,"changed@example.test","idp-record","Verified corporate record","key",caller));
    }
    @Test void malformedUnverifiedAndHeaderInjectionAddressesFailAtBoundary() {
        for(String address:new String[]{null,"","missing-domain","a@example.test\r\nBcc: attacker@example.test","a@space example.test","a".repeat(255)+"@example.test"})
            assertThrows(IllegalArgumentException.class,()->service.verify(employeeId,address,"idp-record","Reviewed","key",caller));
        assertThrows(IllegalArgumentException.class,()->service.verify(employeeId,"a@example.test"," ","Reviewed","key",caller));
        assertThrows(IllegalArgumentException.class,()->service.verify(employeeId,"a@example.test","idp-record"," ","key",caller));
        verify(repository,never()).verifyDestination(any(),anyString(),anyString(),anyString(),any());
    }
    @Test void scopedHrAloneCannotVerifyDestination() {
        ready(); doThrow(new AccessDeniedException("global identity grant absent")).when(access).requireIdentityAuthority(7L,NOW);
        assertThrows(AccessDeniedException.class,()->service.verify(employeeId,"a@example.test","idp-record","Reviewed","key",caller));
        verify(admission).acquire(AdmissionGuard.Mode.EXCLUSIVE);
        verify(employees).lockEmployee(12L);
        verify(repository,never()).verifyDestination(any(),anyString(),anyString(),anyString(),any());
    }
    private void ready() {
        when(caller.getName()).thenReturn("identity-operator");
        Employee employee=mock(Employee.class); when(employee.getId()).thenReturn(12L);
        when(employees.requireInternalByPublicId(employeeId)).thenReturn(employee);
        when(employees.lockEmployee(12L)).thenReturn(Optional.of(employee));
        when(employees.currentOrgUnit(12L,NOW)).thenReturn(Optional.of("ENG"));
        UserAccount operator=mock(UserAccount.class); when(operator.getId()).thenReturn(7L);
        when(access.requireHrScope(caller,"ENG",NOW)).thenReturn(operator);
    }
}
