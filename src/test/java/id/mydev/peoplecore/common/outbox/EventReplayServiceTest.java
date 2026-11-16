package id.mydev.peoplecore.common.outbox;

import id.mydev.peoplecore.common.audit.AuditEvent;
import id.mydev.peoplecore.common.audit.AuditEventWriter;
import id.mydev.peoplecore.common.command.AdmissionGuard;
import id.mydev.peoplecore.common.command.InvalidIdempotencyKeyException;
import id.mydev.peoplecore.common.security.OperationAccessService;
import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EventReplayServiceTest {
    private static final Instant NOW=Instant.parse("2026-11-13T00:00:00Z");
    private final TransportRepository repository=mock(TransportRepository.class);
    private final OperationAccessService access=mock(OperationAccessService.class);
    private final AuditEventWriter audit=mock(AuditEventWriter.class);
    private final EventIngestionService ingestion=mock(EventIngestionService.class);
    private final MockEnvironment environment=new MockEnvironment().withProperty("peoplecore.events.replay.enabled","true");
    private final TestingAuthenticationToken caller=new TestingAuthenticationToken("operator","unused");
    private final EventReplayService service=new EventReplayService(repository,access,audit,ingestion,
        Clock.fixed(NOW, ZoneOffset.UTC),environment,mock(AdmissionGuard.class));

    @Test
    void authorizedSelectionPreservesOriginalIdentitiesAndRecordsReason() {
        EventEnvelope event=event(NOW);
        var replay=new TransportRepository.Replay(UUID.randomUUID(),"notifications","PENDING",NOW);
        when(repository.findEvent(event.eventId())).thenReturn(Optional.of(event));
        when(ingestion.supports(event)).thenReturn(true);
        when(repository.requestReplay(eq("operator"),eq("key"),anyString(),eq("notifications"),eq("fixed consumer"),eq(List.of(event.eventId())),eq(NOW)))
            .thenReturn(replay);
        assertEquals(replay,service.request(caller,"key","notifications","fixed consumer",List.of(event.eventId())));
        var entry=ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).append(entry.capture());
        assertEquals(replay.id(),entry.getValue().getAggregatePublicId());
        assertEquals("fixed consumer",entry.getValue().getReason());
        assertEquals("EVENT_REPLAY_REQUESTED",entry.getValue().getAction());
    }

    @Test
    void disabledRecoveryAndUnauthorizedReplayNeverWriteWork() {
        EventEnvelope event=event(NOW);
        environment.withProperty("peoplecore.recovery.isolated","true");
        assertThrows(IllegalStateException.class,()->service.request(caller,"key","notifications","fix",List.of(event.eventId())));
        verifyNoInteractions(repository,audit);
        environment.withProperty("peoplecore.recovery.isolated","false").withProperty("peoplecore.events.replay.enabled","false");
        assertThrows(IllegalStateException.class,()->service.request(caller,"key","notifications","fix",List.of(event.eventId())));
        environment.withProperty("peoplecore.events.replay.enabled","true");
        doThrow(new AccessDeniedException("not an operator")).when(access).requireOperator(caller,NOW);
        assertThrows(AccessDeniedException.class,()->service.request(caller,"key","notifications","fix",List.of(event.eventId())));
        verifyNoInteractions(repository,audit);
    }

    @Test
    void inputBoundsRejectArbitraryConsumersDuplicatesAndOversizedSelections() {
        UUID id=UUID.randomUUID();
        assertThrows(InvalidIdempotencyKeyException.class,()->service.request(caller," ","notifications","fix",List.of(id)));
        assertThrows(InvalidIdempotencyKeyException.class,()->service.request(caller,"k".repeat(129),"notifications","fix",List.of(id)));
        assertThrows(IllegalArgumentException.class,()->service.request(caller,"key","https://arbitrary-queue","fix",List.of(id)));
        assertThrows(IllegalArgumentException.class,()->service.request(caller,"key","notifications"," ",List.of(id)));
        assertThrows(IllegalArgumentException.class,()->service.request(caller,"key","notifications","x".repeat(1001),List.of(id)));
        assertThrows(IllegalArgumentException.class,()->service.request(caller,"key","notifications","fix",List.of()));
        assertThrows(IllegalArgumentException.class,()->service.request(caller,"key","notifications","fix",List.of(id,id)));
        assertThrows(IllegalArgumentException.class,()->service.request(caller,"key","notifications","fix",Collections.nCopies(101,id)));
        verifyNoInteractions(repository,audit);
    }

    @Test
    void missingOldWrongConsumerAndUnsupportedEventsAreNotReproduced() {
        UUID missing=UUID.randomUUID();
        when(repository.findEvent(missing)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class,()->service.request(caller,"key","notifications","fix",List.of(missing)));
        EventEnvelope old=event(NOW.minusSeconds(15*86400));
        when(repository.findEvent(old.eventId())).thenReturn(Optional.of(old));
        assertThrows(IllegalArgumentException.class,()->service.request(caller,"key","notifications","fix",List.of(old.eventId())));
        EventEnvelope current=event(NOW);
        when(repository.findEvent(current.eventId())).thenReturn(Optional.of(current));
        assertThrows(IllegalArgumentException.class,()->service.request(caller,"key","exports","fix",List.of(current.eventId())));
        when(ingestion.supports(current)).thenReturn(false);
        assertThrows(IllegalArgumentException.class,()->service.request(caller,"key","notifications","fix",List.of(current.eventId())));
        verifyNoInteractions(audit);
    }

    @Test
    void operatorCanInspectAndStopReplayDuringRecovery() {
        UUID id=UUID.randomUUID();
        var cancelled=new TransportRepository.Replay(id,"notifications","CANCELLED",NOW);
        when(repository.findReplay(id)).thenReturn(Optional.of(cancelled));
        when(repository.cancel(id,NOW)).thenReturn(cancelled);
        environment.withProperty("peoplecore.recovery.isolated","true");
        assertEquals(cancelled,service.cancel(caller,id,"stop during incident"));
        assertEquals(cancelled,service.get(caller,id));
        assertThrows(IllegalArgumentException.class,()->service.cancel(caller,id," "));
        when(repository.findReplay(id)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class,()->service.get(caller,id));
    }

    private static EventEnvelope event(Instant at) { return EventIngestionServiceTest.event(at); }
}
