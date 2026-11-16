package id.mydev.peoplecore.common.outbox;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EventDispatchServiceTest {
    private static final Instant NOW=Instant.parse("2026-11-13T00:00:00Z");
    private final TransportRepository repository=mock(TransportRepository.class);
    private final EventIngestionService ingestion=mock(EventIngestionService.class);
    private final EventTransport transport=mock(EventTransport.class);
    private final EventDispatchService service;

    EventDispatchServiceTest() {
        var manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any(TransactionDefinition.class))).thenAnswer(call->new SimpleTransactionStatus());
        service=new EventDispatchService(repository,ingestion,manager,Clock.fixed(NOW,ZoneOffset.UTC));
    }

    @Test
    void emptyOutboxDoesNotContactBroker() {
        when(repository.claim(NOW,1)).thenReturn(List.of());
        assertEquals(0,service.publish(transport));
        verifyNoInteractions(transport);
    }

    @Test
    void successfulPublicationMarksOnlyItsCurrentLease() {
        var publication=publication(NOW,1);
        when(repository.claim(NOW,1)).thenReturn(List.of(publication));
        when(ingestion.supports(publication.envelope())).thenReturn(true);
        when(repository.complete(publication.envelope().eventId(),7,NOW)).thenReturn(true);
        assertEquals(1,service.publish(transport));
        verify(transport).send("notifications",publication.envelope());
        verify(repository).complete(publication.envelope().eventId(),7,NOW);
    }

    @Test
    void stalePublisherCannotClaimSuccessAfterExternalAcceptance() {
        var publication=publication(NOW,1);
        when(repository.claim(NOW,1)).thenReturn(List.of(publication));
        when(ingestion.supports(publication.envelope())).thenReturn(true);
        when(repository.complete(publication.envelope().eventId(),7,NOW)).thenReturn(false);
        assertEquals(0,service.publish(transport));
        verify(repository,never()).retry(any(),anyLong(),any(),any());
    }

    @Test
    void unsupportedAndExpiredWorkIsVisibleAndNeverSent() {
        var unsupported=typed("NEW_UNSUPPORTED_EVENT");
        var expired=publication(NOW.minusSeconds(15*86400),1);
        when(repository.claim(NOW,1)).thenReturn(List.of(unsupported),List.of(expired));
        assertEquals(0,service.publish(transport));
        assertEquals(0,service.publish(transport));
        verify(repository).abandon(unsupported.envelope().eventId(),7,"UNSUPPORTED_EVENT");
        verify(repository).quarantine("notifications",expired.envelope().eventId(),"REPLAY_HORIZON_EXCEEDED",NOW);
        verifyNoInteractions(transport);
    }

    @Test
    void queueFailureKeepsRetryDueWithBoundedJitter() {
        var publication=publication(NOW,100);
        when(repository.claim(NOW,1)).thenReturn(List.of(publication));
        when(ingestion.supports(publication.envelope())).thenReturn(true);
        doThrow(new IllegalStateException("simulated queue outage")).when(transport).send("notifications",publication.envelope());
        assertEquals(0,service.publish(transport));
        var next=ArgumentCaptor.forClass(Instant.class);
        verify(repository).retry(eq(publication.envelope().eventId()),eq(7L),eq(NOW),next.capture());
        assertFalse(next.getValue().isBefore(NOW));
        assertFalse(next.getValue().isAfter(NOW.plusSeconds(300)));
    }

    @Test
    void replayPausesForAnUnhealthyBacklogAndResumesFromDurableItems() {
        when(repository.oldestPendingSeconds(NOW)).thenReturn(301L,0L);
        assertEquals(0,service.replay(transport));
        verify(repository,never()).claimReplay(any());
        when(repository.claimReplay(NOW)).thenReturn(Optional.empty());
        assertEquals(0,service.replay(transport));
        verifyNoInteractions(transport);
    }

    @Test
    void replayPreservesIdentityAndRecordsPublishedOutcome() {
        var item=new TransportRepository.ReplayItem(UUID.randomUUID(),"notifications",publication(NOW,1));
        when(repository.claimReplay(NOW)).thenReturn(Optional.of(item));
        when(ingestion.supports(item.publication().envelope())).thenReturn(true);
        assertEquals(1,service.replay(transport));
        verify(transport).send("notifications",item.publication().envelope());
        verify(repository).completeReplay(item,"PUBLISHED",NOW);
    }

    @Test
    void expiredOrUnsupportedReplayIsQuarantinedAndNotRecreated() {
        var old=new TransportRepository.ReplayItem(UUID.randomUUID(),"notifications",publication(NOW.minusSeconds(15*86400),1));
        var unsupported=new TransportRepository.ReplayItem(UUID.randomUUID(),"notifications",typed("NEW_UNSUPPORTED_EVENT"));
        when(repository.claimReplay(NOW)).thenReturn(Optional.of(old),Optional.of(unsupported));
        assertEquals(0,service.replay(transport));
        assertEquals(0,service.replay(transport));
        verify(repository).completeReplay(old,"QUARANTINED",NOW);
        verify(repository).quarantine("notifications",unsupported.publication().envelope().eventId(),"UNSUPPORTED_EVENT",NOW);
        verifyNoInteractions(transport);
    }

    @Test
    void replaySendFailureDoesNotAcknowledgeTheDurableItem() {
        var item=new TransportRepository.ReplayItem(UUID.randomUUID(),"notifications",publication(NOW,1));
        when(repository.claimReplay(NOW)).thenReturn(Optional.of(item));
        when(ingestion.supports(item.publication().envelope())).thenReturn(true);
        doThrow(new IllegalStateException("queue outage")).when(transport).send("notifications",item.publication().envelope());
        assertEquals(0,service.replay(transport));
        verify(repository,never()).completeReplay(any(),any(),any());
    }

    @Test
    void draftMetadataDoesNotCreateFalseQuarantineOrEmail() {
        var draft=typed("PROFILE_EDIT");
        when(repository.claim(NOW,1)).thenReturn(List.of(draft));
        assertEquals(0,service.publish(transport));
        verify(repository).ignore(draft.envelope().eventId(),7,NOW);
        verify(repository,never()).quarantine(any(),any(),any(),any());
        verifyNoInteractions(transport);
    }

    @Test
    void disabledNotificationHandlerRetainsPublicationAndReplayWork() {
        var publication=publication(NOW,1);
        when(repository.claim(NOW,1)).thenReturn(List.of(publication));
        assertEquals(0,service.publish(transport));
        verify(repository).retry(publication.envelope().eventId(),7,NOW,NOW.plusSeconds(30));
        var replay=new TransportRepository.ReplayItem(UUID.randomUUID(),"notifications",publication);
        when(repository.claimReplay(NOW)).thenReturn(Optional.of(replay));
        assertEquals(0,service.replay(transport));
        verify(repository,never()).completeReplay(any(),any(),any());
        verify(repository,never()).quarantine(any(),any(),any(),any());
        verifyNoInteractions(transport);
    }

    private static TransportRepository.Publication typed(String type) {
        return new TransportRepository.Publication(new EventEnvelope(UUID.randomUUID(),type,1,"PROFILE_PROPOSAL",
            UUID.randomUUID(),NOW,null,Map.of()),7,1);
    }

    private static TransportRepository.Publication publication(Instant at,int attempts) {
        return new TransportRepository.Publication(EventIngestionServiceTest.event(at),7,attempts);
    }
}
