package id.mydev.peoplecore.common.audit;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuditEventTest {

    @Test
    void createsAuditEventWithValidFields() {
        UUID eventId = UUID.randomUUID();
        UUID pubId = UUID.randomUUID();
        Instant now = Instant.now();

        AuditEvent event = new AuditEvent(
            eventId,
            "actor-1",
            "CREATE",
            "EMPLOYEE",
            100L,
            pubId,
            "corr-1",
            "New hire",
            "{\"name\":\"John\"}",
            now
        );

        assertNull(event.getId());
        assertEquals(eventId, event.getEventId());
        assertEquals("actor-1", event.getActorId());
        assertEquals("CREATE", event.getAction());
        assertEquals("EMPLOYEE", event.getAggregateType());
        assertEquals(100L, event.getAggregateId());
        assertEquals(pubId, event.getAggregatePublicId());
        assertEquals("corr-1", event.getCorrelationId());
        assertEquals("New hire", event.getReason());
        assertEquals("{\"name\":\"John\"}", event.getDetails());
        assertEquals(now, event.getCreatedAt());
    }

    @Test
    void rejectsNullRequiredFields() {
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.now();

        assertThrows(NullPointerException.class, () -> new AuditEvent(
            null, "ACTOR", "ACTION", "AGG", null, null, null, null, null, now
        ));
        assertThrows(NullPointerException.class, () -> new AuditEvent(
            eventId, null, "ACTION", "AGG", null, null, null, null, null, now
        ));
        assertThrows(NullPointerException.class, () -> new AuditEvent(
            eventId, "ACTOR", null, "AGG", null, null, null, null, null, now
        ));
        assertThrows(NullPointerException.class, () -> new AuditEvent(
            eventId, "ACTOR", "ACTION", null, null, null, null, null, null, now
        ));
        assertThrows(NullPointerException.class, () -> new AuditEvent(
            eventId, "ACTOR", "ACTION", "AGG", null, null, null, null, null, null
        ));
    }
}
