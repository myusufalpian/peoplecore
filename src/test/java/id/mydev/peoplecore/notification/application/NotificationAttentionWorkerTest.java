package id.mydev.peoplecore.notification.application;

import id.mydev.peoplecore.notification.domain.NotificationAttentionSink;
import id.mydev.peoplecore.notification.domain.NotificationAttentionSink.Attention;
import id.mydev.peoplecore.notification.domain.repository.NotificationAttentionRepository;
import id.mydev.peoplecore.notification.domain.repository.NotificationAttentionRepository.Claim;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class NotificationAttentionWorkerTest {
    private static final Instant NOW=Instant.parse("2026-11-14T00:00:00Z");
    private final NotificationAttentionRepository repository=mock(NotificationAttentionRepository.class);
    private final NotificationAttentionSink sink=mock(NotificationAttentionSink.class);
    private final NotificationAttentionWorker worker;
    NotificationAttentionWorkerTest() {
        var manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any(TransactionDefinition.class))).thenAnswer(call->new SimpleTransactionStatus());
        worker=new NotificationAttentionWorker(repository,sink,Clock.fixed(NOW,ZoneOffset.UTC),manager);
    }
    @Test void emptyQueueNeverCallsExternalSink() {
        when(repository.claim(NOW)).thenReturn(Optional.empty());
        assertFalse(worker.poll());
        verifyNoInteractions(sink);
    }
    @Test void onlyAcknowledgedExternalAlertCompletesItsLease() {
        Claim claim=claim(); when(repository.claim(NOW)).thenReturn(Optional.of(claim));
        assertTrue(worker.poll());
        verify(sink).send(claim.attention()); verify(repository).complete(claim,NOW);
        verify(repository,never()).defer(any(),any());
    }
    @Test void channelOutagePreservesDurableWorkForRetry() {
        Claim claim=claim(); when(repository.claim(NOW)).thenReturn(Optional.of(claim));
        doThrow(new IllegalStateException("not acknowledged")).when(sink).send(claim.attention());
        assertTrue(worker.poll());
        verify(repository).defer(claim,NOW); verify(repository,never()).complete(any(),any());
    }
    private static Claim claim() {
        return new Claim(new Attention(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"FAILED",NOW),1);
    }
}
