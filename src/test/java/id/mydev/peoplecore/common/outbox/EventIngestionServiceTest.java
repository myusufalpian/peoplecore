package id.mydev.peoplecore.common.outbox;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventIngestionServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-13T00:00:00Z");

    @Test
    void duplicateInputCreatesOneDurableSideEffect() {
        var repository = mock(TransportRepository.class);
        EventEnvelope event = event(NOW);
        when(repository.admit("notifications", event, NOW)).thenReturn(true, false);
        List<UUID> effects = new ArrayList<>();
        var handler = handler(effects);
        var service = new EventIngestionService(repository, List.of(handler), Clock.fixed(NOW, ZoneOffset.UTC));
        service.ingest("notifications", event);
        service.ingest("notifications", event);
        assertEquals(List.of(event.eventId()), effects);
    }

    @Test
    void replayBoundaryAndFutureToleranceAreInclusive() {
        assertNull(EventIngestionService.rejection("notifications", event(NOW.minusSeconds(14 * 86400)), NOW));
        assertEquals("REPLAY_HORIZON_EXCEEDED", EventIngestionService.rejection("notifications",
            event(NOW.minusSeconds(14 * 86400).minusNanos(1000)), NOW));
        assertNull(EventIngestionService.rejection("notifications", event(NOW.plusSeconds(60)), NOW));
        assertEquals("FUTURE_EVENT", EventIngestionService.rejection("notifications", event(NOW.plusSeconds(61)), NOW));
        assertEquals("WRONG_CONSUMER", EventIngestionService.rejection("exports", event(NOW), NOW));
    }

    @Test
    void oldInputIsQuarantinedAndUnavailableHandlerRemainsRetryable() {
        var repository = mock(TransportRepository.class);
        var service = new EventIngestionService(repository, List.of(), Clock.fixed(NOW, ZoneOffset.UTC));
        EventEnvelope old = event(NOW.minusSeconds(15 * 86400));
        service.ingest("notifications", old);
        verify(repository).quarantine("notifications", old.eventId(), "REPLAY_HORIZON_EXCEEDED", NOW);
        assertFalse(service.supports(event(NOW)));
        assertThrows(IllegalStateException.class, () -> service.ingest("notifications", event(NOW)));
    }

    @Test
    void envelopeRejectsSensitiveFreeformPayloadsAndUnknownSchema() {
        assertThrows(IllegalArgumentException.class, () -> new EventEnvelope(UUID.randomUUID(), "PAYSLIP_PUBLISHED", 2,
            "PAYSLIP_PUBLICATION", UUID.randomUUID(), NOW, null, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new EventEnvelope(UUID.randomUUID(), "PAYSLIP_PUBLISHED", 1,
            "PAYSLIP_PUBLICATION", UUID.randomUUID(), NOW, null, Map.of("email", UUID.randomUUID())));
    }

    @Test
    void envelopeValidatesIdentityNamesAndSafeCorrelationReferences() {
        UUID event=UUID.randomUUID();
        UUID aggregate=UUID.randomUUID();
        assertEquals("request_123",new EventEnvelope(event,"PAYSLIP_PUBLISHED",1,"PAYSLIP_PUBLICATION",aggregate,NOW,
            "request_123",Map.of("publicationId",aggregate)).correlationId());
        assertThrows(IllegalArgumentException.class,()->new EventEnvelope(event,null,1,"PAYSLIP_PUBLICATION",aggregate,NOW,null,Map.of()));
        assertThrows(IllegalArgumentException.class,()->new EventEnvelope(event,"invalid-event",1,"PAYSLIP_PUBLICATION",aggregate,NOW,null,Map.of()));
        assertThrows(IllegalArgumentException.class,()->new EventEnvelope(event,"PAYSLIP_PUBLISHED",1,null,aggregate,NOW,null,Map.of()));
        assertThrows(IllegalArgumentException.class,()->new EventEnvelope(event,"PAYSLIP_PUBLISHED",1,"invalid aggregate",aggregate,NOW,null,Map.of()));
        assertThrows(IllegalArgumentException.class,()->new EventEnvelope(event,"PAYSLIP_PUBLISHED",1,"PAYSLIP_PUBLICATION",aggregate,NOW,"secret@example.test",Map.of()));
        assertThrows(NullPointerException.class,()->new EventEnvelope(null,"PAYSLIP_PUBLISHED",1,"PAYSLIP_PUBLICATION",aggregate,NOW,null,Map.of()));
        assertThrows(NullPointerException.class,()->new EventEnvelope(event,"PAYSLIP_PUBLISHED",1,"PAYSLIP_PUBLICATION",null,NOW,null,Map.of()));
    }

    static EventEnvelope event(Instant at) {
        return new EventEnvelope(UUID.randomUUID(), "PAYSLIP_PUBLISHED", 1, "PAYSLIP_PUBLICATION", UUID.randomUUID(), at, null, Map.of());
    }

    static EventIngestionHandler handler(List<UUID> effects) {
        return new EventIngestionHandler() {
            @Override
            public boolean supports(String type) {
                return "PAYSLIP_PUBLISHED".equals(type);
            }

            @Override
            public void ingest(EventEnvelope envelope) {
                effects.add(envelope.eventId());
            }
        };
    }
}
