package id.mydev.peoplecore.common.outbox;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OutboxEventTest {

    @Test
    void requestCorrelationSurvivesTheTransactionWithoutCapturingArbitraryDiagnosticContent() {
        UUID eventId=UUID.randomUUID();
        try {
            MDC.put("requestId","request-123");
            var correlated=new OutboxEvent(eventId,"PAYSLIP_PUBLISHED",1,"PAYSLIP_PUBLICATION",null,UUID.randomUUID(),"{}",Instant.now());
            assertEquals("request-123",correlated.getCorrelationId());
            MDC.put("requestId","unsafe@example.test");
            var fallback=new OutboxEvent(eventId,"PAYSLIP_PUBLISHED",1,"PAYSLIP_PUBLICATION",null,UUID.randomUUID(),"{}",Instant.now());
            assertEquals(eventId.toString(),fallback.getCorrelationId());
        } finally {
            MDC.remove("requestId");
        }
    }

    @Test
    void createsOutboxEventWithValidFields() {
        UUID eventId = UUID.randomUUID();
        UUID pubId = UUID.randomUUID();
        Instant now = Instant.now();

        OutboxEvent event = new OutboxEvent(
            eventId,
            "EMPLOYEE_CREATED",
            1,
            "EMPLOYEE",
            100L,
            pubId,
            "{\"id\":\"pub-1\"}",
            now
        );

        assertNull(event.getId());
        assertEquals(eventId, event.getEventId());
        assertEquals("EMPLOYEE_CREATED", event.getEventType());
        assertEquals(1, event.getSchemaVersion());
        assertEquals("EMPLOYEE", event.getAggregateType());
        assertEquals(100L, event.getAggregateId());
        assertEquals(pubId, event.getAggregatePublicId());
        assertEquals("{\"id\":\"pub-1\"}", event.getPayload());
        assertEquals("PENDING", event.getStatus());
        assertEquals(now, event.getCreatedAt());
        assertNull(event.getPublishedAt());
        assertEquals(0, event.getAttemptCount());
        assertNull(event.getLastAttemptAt());

        // Test marking published
        Instant publishedAt = now.plusSeconds(5);
        event.markPublished(publishedAt);
        assertEquals("PUBLISHED", event.getStatus());
        assertEquals(publishedAt, event.getPublishedAt());

        // Test recording attempt
        Instant attemptAt = now.plusSeconds(10);
        event.recordAttempt(attemptAt, false);
        assertEquals(1, event.getAttemptCount());
        assertEquals(attemptAt, event.getLastAttemptAt());
        assertEquals("PUBLISHED", event.getStatus());

        event.recordAttempt(attemptAt.plusSeconds(5), true);
        assertEquals(2, event.getAttemptCount());
        assertEquals("FAILED", event.getStatus());
    }

    @Test
    void rejectsNullRequiredFields() {
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.now();

        assertThrows(NullPointerException.class, () -> new OutboxEvent(
            null, "TYPE", 1, "AGG", null, null, "PAYLOAD", now
        ));
        assertThrows(NullPointerException.class, () -> new OutboxEvent(
            eventId, null, 1, "AGG", null, null, "PAYLOAD", now
        ));
        assertThrows(NullPointerException.class, () -> new OutboxEvent(
            eventId, "TYPE", 1, null, null, null, "PAYLOAD", now
        ));
        assertThrows(NullPointerException.class, () -> new OutboxEvent(
            eventId, "TYPE", 1, "AGG", null, null, null, now
        ));
        assertThrows(NullPointerException.class, () -> new OutboxEvent(
            eventId, "TYPE", 1, "AGG", null, null, "PAYLOAD", null
        ));
    }
}
