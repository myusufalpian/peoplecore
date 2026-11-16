package id.mydev.peoplecore.notification.application;

import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.common.command.AdmissionGuard;
import id.mydev.peoplecore.identity.application.service.IdentityNotificationAccess;
import id.mydev.peoplecore.notification.domain.NotificationModels.Delivery;
import id.mydev.peoplecore.notification.domain.repository.NotificationRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationAcceptanceTest {
    private static final Instant NOW=Instant.parse("2026-11-13T10:00:00Z");
    private final NotificationRepository repository=mock(NotificationRepository.class);
    private final IdentityNotificationAccess identity=mock(IdentityNotificationAccess.class);
    private final AdmissionGuard admission=mock(AdmissionGuard.class);
    private final NotificationAcceptance acceptance=new NotificationAcceptance(repository,identity,admission);

    @Test void invitationLockOrderIsAdmissionThenImmutableIdentityContextThenDelivery() {
        Delivery delivery=delivery("INVITATION");
        when(repository.find(delivery.id(),false)).thenReturn(Optional.of(delivery));
        when(repository.find(delivery.id(),true)).thenReturn(Optional.of(delivery));
        when(identity.lockAcceptance(delivery.aggregateId(),delivery.employeeId(),delivery.destinationId(),delivery.destinationVersion())).thenReturn(true);
        assertEquals(delivery,acceptance.lock(delivery.id()));
        var locks=inOrder(admission,repository,identity);
        locks.verify(admission).acquire(AdmissionGuard.Mode.SHARED);
        locks.verify(repository).find(delivery.id(),false);
        locks.verify(identity).lockAcceptance(delivery.aggregateId(),delivery.employeeId(),delivery.destinationId(),delivery.destinationVersion());
        locks.verify(repository).find(delivery.id(),true);
    }
    @Test void mismatchedInvitationContextNeverLocksDeliveryOrMarksAnyInvitationSent() {
        Delivery delivery=delivery("INVITATION");
        when(repository.find(delivery.id(),false)).thenReturn(Optional.of(delivery));
        assertThrows(ResourceNotFoundException.class,()->acceptance.lock(delivery.id()));
        verify(repository,never()).find(delivery.id(),true);
        verify(identity,never()).accepted(any());
        assertThrows(ResourceNotFoundException.class,()->acceptance.lock(UUID.randomUUID()));
    }
    @Test void onlyCurrentKnownAcceptanceOrDeliveryMapsToSent() {
        Delivery delivery=delivery("INVITATION"); UUID attempt=UUID.randomUUID();
        when(repository.attemptId(delivery.id(),delivery.generation())).thenReturn(Optional.of(attempt));
        for(String state:List.of("SENDING","PENDING","UNKNOWN","FAILED","SUPPRESSED","ACCEPTED","DELIVERED")) {
            when(repository.find(delivery.id(),false)).thenReturn(Optional.of(copy(delivery,state,delivery.generation())));
            acceptance.accepted(delivery,attempt);
        }
        verify(identity,times(2)).accepted(delivery.aggregateId());
    }
    @Test void staleGenerationAndWrongOrMissingAttemptCannotMarkAnInvitationSent() {
        Delivery delivery=delivery("INVITATION"); UUID attempt=UUID.randomUUID();
        when(repository.find(delivery.id(),false)).thenReturn(Optional.of(copy(delivery,"ACCEPTED",delivery.generation()+1)));
        acceptance.accepted(delivery,attempt);
        when(repository.find(delivery.id(),false)).thenReturn(Optional.of(copy(delivery,"ACCEPTED",delivery.generation())));
        when(repository.attemptId(delivery.id(),delivery.generation())).thenReturn(Optional.of(UUID.randomUUID()));
        acceptance.accepted(delivery,attempt);
        when(repository.attemptId(delivery.id(),delivery.generation())).thenReturn(Optional.empty());
        acceptance.accepted(delivery,attempt);
        verify(identity,never()).accepted(any());
    }
    @Test void nonInvitationDeliveryNeverTouchesIdentitySummary() {
        Delivery delivery=delivery("PAYSLIP_AVAILABLE");
        when(repository.find(delivery.id(),false)).thenReturn(Optional.of(delivery));
        when(repository.find(delivery.id(),true)).thenReturn(Optional.of(delivery));
        assertEquals(delivery,acceptance.lock(delivery.id()));
        acceptance.accepted(delivery,UUID.randomUUID());
        verify(identity,never()).accepted(any());
        verify(identity,never()).lockAcceptance(any(),any(),any(),anyLong());
    }
    private static Delivery delivery(String template) {
        return new Delivery(UUID.randomUUID(),UUID.randomUUID(),"INVITATION_ISSUE",UUID.randomUUID(),NOW,UUID.randomUUID(),null,template,"INVITATION",null,UUID.randomUUID(),3,"SENDING",1,1,1,false,NOW.plusSeconds(60),NOW);
    }
    private static Delivery copy(Delivery original,String state,long generation) {
        return new Delivery(original.id(),original.eventId(),original.eventType(),original.aggregateId(),original.occurredAt(),original.employeeId(),original.accountId(),original.template(),original.eligibilityType(),original.assignmentId(),original.destinationId(),original.destinationVersion(),state,original.version(),generation,original.attempts(),original.manualAttempt(),"SENDING".equals(state)?NOW.plusSeconds(60):null,NOW);
    }
}
