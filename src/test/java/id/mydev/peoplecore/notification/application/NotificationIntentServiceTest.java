package id.mydev.peoplecore.notification.application;

import id.mydev.peoplecore.common.outbox.EventEnvelope;
import id.mydev.peoplecore.common.outbox.OutboxEvent;
import id.mydev.peoplecore.identity.application.service.IdentityNotificationAccess;
import id.mydev.peoplecore.notification.domain.NotificationModels.Intent;
import id.mydev.peoplecore.notification.domain.NotificationModels.Recipient;
import id.mydev.peoplecore.notification.domain.NotificationResolver;
import id.mydev.peoplecore.notification.domain.repository.NotificationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationIntentServiceTest {
    private static final Instant NOW=Instant.parse("2026-11-13T10:00:00Z");
    private final NotificationRepository repository=mock(NotificationRepository.class);
    private final IdentityNotificationAccess identity=mock(IdentityNotificationAccess.class);
    private final NotificationResolver resolver=mock(NotificationResolver.class);
    private final Clock clock=Clock.fixed(NOW,ZoneOffset.UTC);
    @Test void eventTimeRecipientIsFrozenBeforeDelayedIngestionAndNeverResolvedAgain() {
        var service=service(""); OutboxEvent event=event();
        UUID employee=UUID.randomUUID(); UUID original=UUID.randomUUID(); UUID assignment=UUID.randomUUID();
        when(resolver.supports("PROFILE_SUBMIT")).thenReturn(true);
        when(resolver.resolve("PROFILE_SUBMIT",event.getAggregatePublicId(),NOW)).thenReturn(List.of(new Recipient(employee,original,"REVIEW_REQUIRED","ORGANIZATION_REVIEW",assignment)));
        service.created(event);
        var pinned=ArgumentCaptor.forClass(Intent.class);
        verify(repository).storeIntent(pinned.capture(),eq(Optional.empty()),eq(NOW));
        assertEquals(original,pinned.getValue().accountId()); assertEquals(assignment,pinned.getValue().assignmentId());
        when(resolver.resolve("PROFILE_SUBMIT",event.getAggregatePublicId(),NOW)).thenReturn(List.of(new Recipient(employee,UUID.randomUUID(),"REVIEW_REQUIRED","ORGANIZATION_REVIEW",UUID.randomUUID())));
        when(repository.materialize(event.getEventId(),NOW)).thenReturn(1);
        service.ingest(envelope(event));
        verify(resolver,times(1)).resolve("PROFILE_SUBMIT",event.getAggregatePublicId(),NOW);
        verify(repository).materialize(event.getEventId(),NOW);
        assertEquals(original,pinned.getValue().accountId());
        assertTrue(service.supports("PROFILE_SUBMIT")); assertFalse(service.supports("UNSUPPORTED"));
    }
    @Test void duplicateRecipientsUseOneDeterministicHighestPriorityTemplate() {
        var service=service(""); OutboxEvent event=event(); UUID employee=UUID.randomUUID(); UUID account=UUID.randomUUID();
        when(resolver.supports("PROFILE_SUBMIT")).thenReturn(true);
        when(resolver.resolve("PROFILE_SUBMIT",event.getAggregatePublicId(),NOW)).thenReturn(List.of(
            new Recipient(employee,account,"REQUEST_STATUS","OWNER",null),
            new Recipient(employee,account,"REQUEST_CLOSED","OWNER",null),
            new Recipient(employee,account,"REVIEW_REQUIRED","OWNER",null),
            new Recipient(employee,account,"REVIEW_REASSIGNED","OWNER",null),
            new Recipient(employee,account,"REVIEW_BLOCKED","OWNER",null)));
        service.created(event);
        var pinned=ArgumentCaptor.forClass(Intent.class);
        verify(repository,times(1)).storeIntent(pinned.capture(),eq(Optional.empty()),eq(NOW));
        assertEquals("REVIEW_BLOCKED",pinned.getValue().template());
        verify(repository).attention(event.getEventId(),null,"HR_RECOVERY_UNAVAILABLE",NOW);
    }
    @Test void configuredRecoveryUsesOnlyExplicitEligibleHrAndDoesNotBroadcast() {
        UUID configured=UUID.randomUUID(); var service=service(configured.toString()); OutboxEvent event=event();
        UUID employee=UUID.randomUUID(); UUID hrEmployee=UUID.randomUUID();
        when(resolver.supports("PROFILE_SUBMIT")).thenReturn(true);
        when(resolver.resolve("PROFILE_SUBMIT",event.getAggregatePublicId(),NOW)).thenReturn(List.of(new Recipient(employee,UUID.randomUUID(),"REVIEW_BLOCKED","OWNER",null)));
        when(identity.recoveryRecipient(employee,configured,NOW)).thenReturn(Optional.of(new Recipient(hrEmployee,configured,"REVIEW_BLOCKED","HR_RECOVERY",employee)));
        service.created(event);
        var pinned=ArgumentCaptor.forClass(Intent.class);
        verify(repository,times(2)).storeIntent(pinned.capture(),eq(Optional.empty()),eq(NOW));
        assertTrue(pinned.getAllValues().stream().anyMatch(i->"HR_RECOVERY".equals(i.eligibilityType())&&configured.equals(i.accountId())));
    }
    @Test void missingRecipientAndHistoricalSourceRemainVisibleRatherThanInventingDelivery() {
        var service=service(""); OutboxEvent event=event();
        when(resolver.supports("PROFILE_SUBMIT")).thenReturn(true);
        when(resolver.resolve("PROFILE_SUBMIT",event.getAggregatePublicId(),NOW)).thenReturn(List.of());
        service.created(event);
        verify(repository).attention(event.getEventId(),null,"RECIPIENT_UNAVAILABLE",NOW);
        service.ingest(envelope(event));
        verify(repository).attention(event.getEventId(),null,"NO_NEW_DELIVERY_OR_LEGACY_EVENT",NOW);
    }
    private NotificationIntentService service(String recovery) { return new NotificationIntentService(List.of(resolver),repository,identity,clock,recovery); }
    private OutboxEvent event() { return new OutboxEvent(UUID.randomUUID(),"PROFILE_SUBMIT",1,"PROFILE_PROPOSAL",null,UUID.randomUUID(),"{}",NOW); }
    private EventEnvelope envelope(OutboxEvent event) { return new EventEnvelope(event.getEventId(),event.getEventType(),1,event.getAggregateType(),event.getAggregatePublicId(),NOW,null,Map.of()); }
}
