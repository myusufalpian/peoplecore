package id.mydev.peoplecore.identity.application.service;

import id.mydev.peoplecore.identity.domain.InvitationCipher;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.identity.domain.repository.NotificationIdentityRepository;
import id.mydev.peoplecore.notification.domain.NotificationModels.Destination;
import id.mydev.peoplecore.notification.domain.NotificationModels.Intent;
import id.mydev.peoplecore.organization.application.service.EmployeeService;
import id.mydev.peoplecore.organization.domain.model.Employee;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdentityNotificationAccessTest {
    private static final Instant NOW=Instant.parse("2026-11-13T10:00:00Z");
    private final NotificationIdentityRepository repository=mock(NotificationIdentityRepository.class);
    private final AccountAccessService access=mock(AccountAccessService.class);
    private final InvitationCipher cipher=mock(InvitationCipher.class);
    private final EmployeeService employees=mock(EmployeeService.class);
    private final IdentityNotificationAccess identity=new IdentityNotificationAccess(repository,access,cipher,Clock.fixed(NOW,ZoneOffset.UTC),employees);

    @Test void accountMustStillBeActiveWithRoleAndExactCurrentBinding() {
        UUID employee=UUID.randomUUID(); UUID accountId=UUID.randomUUID();
        Intent intent=intent(employee,accountId,"OWNER",null); UserAccount account=account(accountId);
        when(access.findAccountByPublicId(accountId)).thenReturn(Optional.of(account));
        when(access.activeRoles(7L,NOW)).thenReturn(List.of("EMPLOYEE"));
        when(access.ownEmployeePublicId(account)).thenReturn(Optional.of(employee));
        assertTrue(identity.accountEligible(intent,NOW));
        when(access.ownEmployeePublicId(account)).thenReturn(Optional.of(UUID.randomUUID()));
        assertFalse(identity.accountEligible(intent,NOW));
        when(access.ownEmployeePublicId(account)).thenReturn(Optional.empty());
        assertFalse(identity.accountEligible(intent,NOW));
        when(access.activeRoles(7L,NOW)).thenReturn(List.of());
        assertFalse(identity.accountEligible(intent,NOW));
        when(account.isActiveAt(NOW)).thenReturn(false);
        assertFalse(identity.accountEligible(intent,NOW));
        when(access.findAccountByPublicId(accountId)).thenReturn(Optional.empty());
        assertFalse(identity.accountEligible(intent,NOW));
        assertFalse(identity.accountEligible(intent(employee,null,"OWNER",null),NOW));
    }
    @Test void preActivationInvitationDoesNotRequireAnAlreadyBoundAccount() {
        assertTrue(identity.accountEligible(intent(UUID.randomUUID(),null,"INVITATION",null),NOW));
        verify(access,never()).findAccountByPublicId(any());
    }
    @Test void invitationMaterialMustMatchPinnedDestinationAndVersionBeforeDecryption() {
        UUID employee=UUID.randomUUID(); UUID invitation=UUID.randomUUID(); UUID destination=UUID.randomUUID();
        var stored=new Destination(destination,employee,"verified@example.test",3,"VERIFIED");
        when(repository.lockInvitation(invitation,NOW)).thenReturn(Optional.of(new NotificationIdentityRepository.InvitationMaterial(invitation,employee,stored,"ciphertext","key-v1")));
        assertTrue(identity.invitation(invitation,destination,2).isEmpty());
        assertFalse(identity.invitationEligible(invitation,destination,2));
        assertTrue(identity.invitation(invitation,UUID.randomUUID(),3).isEmpty());
        verify(cipher,never()).decrypt(any(),any(),anyString(),anyString());
        when(cipher.decrypt(invitation,destination,"ciphertext","key-v1")).thenReturn("activation-secret");
        var content=identity.invitation(invitation,destination,3).orElseThrow();
        assertEquals("activation-secret",content.secret());
        assertFalse(content.toString().contains("activation-secret"));
        assertTrue(identity.invitationEligible(invitation,destination,3));
        when(repository.lockInvitation(invitation,NOW)).thenReturn(Optional.empty());
        assertFalse(identity.invitationEligible(invitation,destination,3));
    }
    @Test void onlyVerifiedDestinationsAreAvailableToWorkers() {
        UUID employee=UUID.randomUUID();
        when(repository.currentDestination(employee)).thenReturn(Optional.of(new Destination(UUID.randomUUID(),employee,"verified@example.test",1,"SUPPRESSED")));
        assertTrue(identity.destination(employee).isEmpty());
        when(repository.currentDestination(employee)).thenReturn(Optional.of(new Destination(UUID.randomUUID(),employee,"verified@example.test",2,"VERIFIED")));
        assertTrue(identity.destination(employee).isPresent());
        when(repository.currentDestination(employee)).thenReturn(Optional.empty());
        assertTrue(identity.destination(employee).isEmpty());
    }
    @Test void configuredRecoveryOwnerMustHaveCurrentTargetScopeAndEmployeeBinding() {
        UUID employee=UUID.randomUUID(); UUID hrEmployee=UUID.randomUUID(); UUID accountId=UUID.randomUUID();
        Employee target=mock(Employee.class); when(target.getId()).thenReturn(11L);
        when(employees.requireInternalByPublicId(employee)).thenReturn(target);
        when(employees.currentOrgUnit(11L,NOW)).thenReturn(Optional.of("ENG"));
        UserAccount account=account(accountId); when(account.getPublicId()).thenReturn(accountId);
        when(access.findAccountByPublicId(accountId)).thenReturn(Optional.of(account));
        when(access.hasScopedRole(7L,"HR_ADMIN","ENG",NOW)).thenReturn(true);
        when(access.ownEmployeePublicId(account)).thenReturn(Optional.of(hrEmployee));
        var recipient=identity.recoveryRecipient(employee,accountId,NOW).orElseThrow();
        assertEquals(hrEmployee,recipient.employeeId()); assertEquals(employee,recipient.assignmentId());
        assertTrue(identity.accountEligible(intent(hrEmployee,accountId,"HR_RECOVERY",employee),NOW));
        when(access.hasScopedRole(7L,"HR_ADMIN","ENG",NOW)).thenReturn(false);
        assertTrue(identity.recoveryRecipient(employee,accountId,NOW).isEmpty());
        assertFalse(identity.accountEligible(intent(hrEmployee,accountId,"HR_RECOVERY",employee),NOW));
    }
    @Test void acceptanceSuppressionAndCleanupUseIdentityOwnedStateTransitions() {
        UUID invitation=UUID.randomUUID(); UUID destination=UUID.randomUUID();
        identity.accepted(invitation); identity.suppress(destination); identity.cleanup();
        verify(repository).markInvitationAccepted(invitation);
        verify(repository).suppressDestination(destination);
        verify(repository).cleanupInvalidInvitations(NOW);
    }
    private UserAccount account(UUID publicId) {
        UserAccount account=mock(UserAccount.class); when(account.getId()).thenReturn(7L); when(account.isActiveAt(NOW)).thenReturn(true); return account;
    }
    private Intent intent(UUID employee,UUID account,String type,UUID assignment) {
        return new Intent(UUID.randomUUID(),"PAYSLIP_PUBLISHED",UUID.randomUUID(),NOW,employee,account,"GENERIC",type,assignment);
    }
}
