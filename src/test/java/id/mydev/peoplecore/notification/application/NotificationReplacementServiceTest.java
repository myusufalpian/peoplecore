package id.mydev.peoplecore.notification.application;

import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.common.audit.AuditEventWriter;
import id.mydev.peoplecore.common.command.AdmissionGuard;
import id.mydev.peoplecore.common.command.CommandBusyException;
import id.mydev.peoplecore.common.command.CommandConflictException;
import id.mydev.peoplecore.common.command.CommandIds;
import id.mydev.peoplecore.common.security.OperationAccessService;
import id.mydev.peoplecore.identity.application.service.IdentityNotificationAccess;
import id.mydev.peoplecore.notification.domain.NotificationModels.Delivery;
import id.mydev.peoplecore.notification.domain.NotificationModels.Destination;
import id.mydev.peoplecore.notification.domain.NotificationResolver;
import id.mydev.peoplecore.notification.domain.repository.NotificationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationReplacementServiceTest {
    private static final Instant NOW=Instant.parse("2026-11-13T10:00:00Z");
    private final NotificationRepository repository=mock(NotificationRepository.class);
    private final OperationAccessService access=mock(OperationAccessService.class);
    private final IdentityNotificationAccess identity=mock(IdentityNotificationAccess.class);
    private final NotificationResolver resolver=mock(NotificationResolver.class);
    private final Authentication caller=mock(Authentication.class);
    private final MockEnvironment environment=new MockEnvironment();
    private final NotificationSupportService service=new NotificationSupportService(repository,access,mock(AuditEventWriter.class),mock(AdmissionGuard.class),Clock.fixed(NOW,ZoneOffset.UTC),environment,identity,List.of(resolver),mock(NotificationAcceptance.class));

    @Test void unknownReplacementRequiresExplicitDuplicateAcknowledgementAndCreatesOneFreshPinnedIdentity() {
        Delivery original=ready("UNKNOWN","REVIEW_REQUIRED");
        assertThrows(IllegalArgumentException.class,()->service.replacement(caller,original.id(),original.version(),"repair","Verified repair",false));
        UUID id=CommandIds.derive("NOTIFICATION_DELIVERY_REPLACE","operator","repair");
        Delivery replacement=new Delivery(id,UUID.randomUUID(),original.eventType(),original.aggregateId(),NOW,original.employeeId(),original.accountId(),original.template(),original.eligibilityType(),original.assignmentId(),original.destinationId(),1,"PENDING",0,0,0,true,null,NOW);
        when(repository.find(id,false)).thenReturn(Optional.of(replacement));
        var repaired=service.replacement(caller,original.id(),original.version(),"repair","Verified repair",true);
        assertEquals(original.id(),repaired.originalDeliveryId()); assertEquals(id,repaired.delivery().id());
        verify(repository).replacement(id,CommandIds.derive("NOTIFICATION_REPLACEMENT_EVENT","operator","repair"),original,
            new Destination(original.destinationId(),original.employeeId(),"verified-new@example.test",1,"VERIFIED"),NOW);
    }
    @Test void staleVersionLiveAttemptsAndInvitationRetargetingAreDenied() {
        Delivery original=ready("SENDING","REVIEW_REQUIRED");
        assertThrows(CommandConflictException.class,()->service.replacement(caller,original.id(),original.version()+1,"repair","Verified repair",true));
        assertThrows(CommandConflictException.class,()->service.replacement(caller,original.id(),original.version(),"repair","Verified repair",true));
        Delivery invitation=ready("FAILED","INVITATION");
        assertThrows(IllegalArgumentException.class,()->service.replacement(caller,invitation.id(),invitation.version(),"repair","Verified repair",true));
        verify(repository,never()).replacement(any(),any(),any(),any(),any());
    }
    @Test void missingCurrentAuthorityAndRecoveryIsolationDoNotCreateRepairWork() {
        Delivery original=ready("FAILED","REVIEW_REQUIRED");
        when(identity.accountEligible(NotificationTransactions.intent(original),NOW)).thenReturn(false);
        assertThrows(ResourceNotFoundException.class,()->service.replacement(caller,original.id(),original.version(),"repair","Verified repair",false));
        when(identity.accountEligible(NotificationTransactions.intent(original),NOW)).thenReturn(true);
        when(resolver.eligible(NotificationTransactions.intent(original),NOW)).thenReturn(false);
        assertThrows(ResourceNotFoundException.class,()->service.replacement(caller,original.id(),original.version(),"repair","Verified repair",false));
        environment.setProperty("peoplecore.recovery.isolated","true");
        assertThrows(CommandBusyException.class,()->service.replacement(caller,original.id(),original.version(),"repair","Verified repair",false));
        environment.setProperty("peoplecore.recovery.isolated","false");
        when(access.requireOperator(caller,NOW)).thenThrow(new AccessDeniedException("operator grant revoked"));
        assertThrows(AccessDeniedException.class,()->service.replacement(caller,original.id(),original.version(),"repair","Verified repair",false));
        verify(repository,never()).replacement(any(),any(),any(),any(),any());
    }
    @Test void verifiedChangedDestinationAndDistributedSupportBudgetAreRequired() {
        Delivery original=ready("FAILED","REVIEW_REQUIRED");
        when(identity.destination(original.employeeId())).thenReturn(Optional.of(new Destination(original.destinationId(),original.employeeId(),"unchanged@example.test",0,"VERIFIED")));
        assertThrows(CommandConflictException.class,()->service.replacement(caller,original.id(),original.version(),"repair","Verified repair",false));
        when(identity.destination(original.employeeId())).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,()->service.replacement(caller,original.id(),original.version(),"repair","Verified repair",false));
        when(identity.destination(original.employeeId())).thenReturn(Optional.of(new Destination(original.destinationId(),original.employeeId(),"verified-new@example.test",1,"VERIFIED")));
        when(repository.reserve("support:operator",NOW,10)).thenReturn(false);
        assertThrows(CommandBusyException.class,()->service.replacement(caller,original.id(),original.version(),"repair","Verified repair",false));
        verify(repository,never()).replacement(any(),any(),any(),any(),any());
    }
    private Delivery ready(String state,String template) {
        when(caller.getName()).thenReturn("operator");
        Delivery original=new Delivery(UUID.randomUUID(),UUID.randomUUID(),"INVITATION".equals(template)?"INVITATION_ISSUE":"PROFILE_SUBMIT",UUID.randomUUID(),NOW,
            UUID.randomUUID(),UUID.randomUUID(),template,"INVITATION".equals(template)?"INVITATION":"ORGANIZATION_REVIEW",UUID.randomUUID(),UUID.randomUUID(),0,state,1,1,1,false,"SENDING".equals(state)?NOW.plusSeconds(60):null,NOW);
        when(repository.find(original.id(),true)).thenReturn(Optional.of(original));
        when(identity.accountEligible(NotificationTransactions.intent(original),NOW)).thenReturn(true);
        when(resolver.supports(original.eventType())).thenReturn(true);
        when(resolver.eligible(NotificationTransactions.intent(original),NOW)).thenReturn(true);
        when(identity.destination(original.employeeId())).thenReturn(Optional.of(new Destination(original.destinationId(),original.employeeId(),"verified-new@example.test",1,"VERIFIED")));
        when(repository.reserve("support:operator",NOW,10)).thenReturn(true);
        return original;
    }
}
