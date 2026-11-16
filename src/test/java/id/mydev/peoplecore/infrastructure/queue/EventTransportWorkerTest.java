package id.mydev.peoplecore.infrastructure.queue;

import id.mydev.peoplecore.common.outbox.EventDispatchService;
import id.mydev.peoplecore.common.outbox.EventEnvelope;
import id.mydev.peoplecore.common.outbox.EventIngestionService;
import id.mydev.peoplecore.common.outbox.EventTransport;
import id.mydev.peoplecore.common.outbox.TransportRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

class EventTransportWorkerTest {
    private static final Instant NOW=Instant.parse("2026-11-13T00:00:00Z");
    private final EventTransport transport=mock(EventTransport.class);
    private final EventIngestionService ingestion=mock(EventIngestionService.class);
    private final TransportRepository repository=mock(TransportRepository.class);
    private final MockEnvironment environment=new MockEnvironment();
    private final ObjectMapper mapper=new ObjectMapper();

    @Test
    void boundedWorkerLoopsRecoverAfterAQueueFailureAndShutDown() throws Exception {
        var dispatcher=mock(EventDispatchService.class);
        var entered=new CountDownLatch(7);
        var first=new AtomicBoolean(true);
        when(dispatcher.publish(transport)).thenAnswer(call->{
            if(first.compareAndSet(true,false)) throw new IllegalStateException("transient outage");
            entered.countDown();
            return 0;
        });
        when(transport.receive(any())).thenAnswer(call->{ entered.countDown(); return List.of(); });
        when(dispatcher.replay(transport)).thenAnswer(call->{ entered.countDown(); return 0; });
        when(repository.oldestPendingSeconds(NOW)).thenReturn(301L);
        when(repository.quarantineCount()).thenReturn(1L);
        when(transport.deadLetterCount(any())).thenReturn(1L);
        environment.withProperty("peoplecore.events.replay.enabled","true");
        var manager=mock(PlatformTransactionManager.class);
        try(var worker=new EventTransportWorker(transport,dispatcher,ingestion,repository,mapper,environment,
            Clock.fixed(NOW,ZoneOffset.UTC),manager)) {
            worker.start();
            assertTrue(entered.await(5,TimeUnit.SECONDS),"Bounded loops did not resume after the transient failure");
        }
        verifyNoInteractions(ingestion);
    }

    @Test
    void queueAcknowledgmentFollowsCommittedIngestion() {
        var event=event();
        when(transport.receive("notifications")).thenReturn(List.of(new EventTransport.Message("receipt",mapper.writeValueAsString(event))));
        try(var worker=worker()) { worker.poll("notifications"); }
        var order=inOrder(ingestion,transport);
        order.verify(ingestion).ingest("notifications",event);
        order.verify(transport).acknowledge("notifications","receipt");
    }

    @Test
    void failedDatabaseIngestionLeavesMessageUnacknowledged() {
        var event=event();
        when(transport.receive("notifications")).thenReturn(List.of(new EventTransport.Message("receipt",mapper.writeValueAsString(event))));
        doThrow(new IllegalStateException("database unavailable")).when(ingestion).ingest("notifications",event);
        try(var worker=worker()) {
            assertThrows(IllegalStateException.class,()->worker.poll("notifications"));
        }
        verify(transport,never()).acknowledge(any(),any());
    }

    @Test
    void invalidAndOversizedBodiesAreQuarantinedWithoutPersistingTheirContents() {
        when(transport.receive("notifications")).thenReturn(List.of(
            new EventTransport.Message("invalid","{\"secret\":\"do-not-log-this\"}"),
            new EventTransport.Message("oversized","x".repeat(SqsEventTransport.MAX_BYTES+1))));
        try(var worker=worker()) { worker.poll("notifications"); }
        verify(repository,times(2)).quarantine(eq("notifications"),any(UUID.class),eq("INVALID_ENVELOPE"),eq(NOW));
        verify(transport).acknowledge("notifications","invalid");
        verify(transport).acknowledge("notifications","oversized");
        verifyNoInteractions(ingestion);
    }

    @Test
    void recoveryFencesPollingAndLeavesAlreadyReceivedWorkAvailable() {
        environment.withProperty("peoplecore.recovery.isolated","true");
        try(var worker=worker()) { worker.poll("notifications"); }
        verifyNoInteractions(transport,ingestion);
        environment.withProperty("peoplecore.recovery.isolated","false");
        when(transport.receive("notifications")).thenAnswer(call->{
            environment.withProperty("peoplecore.recovery.isolated","true");
            return List.of(new EventTransport.Message("receipt",mapper.writeValueAsString(event())));
        });
        try(var worker=worker()) { worker.poll("notifications"); }
        verify(transport,never()).acknowledge(any(),any());
        verifyNoInteractions(ingestion);
    }

    private EventTransportWorker worker() {
        var manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any(TransactionDefinition.class))).thenAnswer(call->new SimpleTransactionStatus());
        return new EventTransportWorker(transport,mock(EventDispatchService.class),ingestion,repository,mapper,
            environment,Clock.fixed(NOW,ZoneOffset.UTC),manager);
    }

    private static EventEnvelope event() {
        return new EventEnvelope(UUID.randomUUID(),"PAYSLIP_PUBLISHED",1,"PAYSLIP_PUBLICATION",UUID.randomUUID(),NOW,null,Map.of());
    }
}
