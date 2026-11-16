package id.mydev.peoplecore.common.outbox;

import id.mydev.peoplecore.infrastructure.persistence.JdbcTransportRepository;
import id.mydev.peoplecore.support.PostgresFixture;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.util.Objects;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("postgres")
class EventTransportPostgresTest {
    private static final Instant NOW = Instant.parse("2026-11-13T00:00:00Z");

    @Test
    void invalidStoredEnvelopeRemainsVisibleWithoutPoisoningThePublicationBatch() {
        try(var fixture=new PostgresFixture()) {
            fixture.flyway().migrate();
            var repository=new JdbcTransportRepository(fixture.jdbc);
            EventEnvelope bad=insert(fixture,NOW);
            fixture.jdbc.update("UPDATE outbox_events SET schema_version=999 WHERE event_id=?",bad.eventId());
            assertTrue(transactions(fixture).execute(status->repository.claim(NOW,1)).isEmpty());
            assertEquals(1L,repository.quarantineCount());
            assertEquals("INVALID_PUBLIC_ENVELOPE",fixture.jdbc.queryForObject(
                "SELECT transport_reason FROM outbox_events WHERE event_id=?",String.class,bad.eventId()));
            assertTrue(repository.findEvent(bad.eventId()).isEmpty());
        }
    }

    @Test
    void metadataIsIgnoredWhileDisabledHandlersRetainKnownNotificationWork() {
        try(var fixture=new PostgresFixture()) {
            fixture.flyway().migrate();
            var repository=new JdbcTransportRepository(fixture.jdbc);
            var tx=transactions(fixture);
            var unavailable=new EventIngestionService(repository,List.of(),Clock.fixed(NOW,ZoneOffset.UTC));
            var dispatcher=new EventDispatchService(repository,unavailable,tx.getTransactionManager(),Clock.fixed(NOW,ZoneOffset.UTC));
            EventEnvelope metadata=insert(fixture,NOW);
            fixture.jdbc.update("UPDATE outbox_events SET event_type='PROFILE_EDIT' WHERE event_id=?",metadata.eventId());
            List<UUID> sent=new ArrayList<>();
            assertEquals(0,dispatcher.publish(transport(false,sent)));
            assertEquals("IGNORED",fixture.jdbc.queryForObject("SELECT status FROM outbox_events WHERE event_id=?",String.class,metadata.eventId()));
            assertFalse(repository.ignore(metadata.eventId(),1,NOW));
            EventEnvelope notification=insert(fixture,NOW);
            assertEquals(0,dispatcher.publish(transport(false,sent)));
            assertEquals("FAILED",fixture.jdbc.queryForObject("SELECT status FROM outbox_events WHERE event_id=?",String.class,notification.eventId()));
            assertEquals(0L,repository.quarantineCount());
            assertTrue(sent.isEmpty());
            fixture.jdbc.update("UPDATE outbox_events SET next_attempt_at=? WHERE event_id=?",Timestamp.from(NOW),notification.eventId());
            var available=new EventIngestionService(repository,List.of(EventIngestionServiceTest.handler(new ArrayList<>())),Clock.fixed(NOW,ZoneOffset.UTC));
            var resumed=new EventDispatchService(repository,available,tx.getTransactionManager(),Clock.fixed(NOW,ZoneOffset.UTC));
            assertEquals(1,resumed.publish(transport(false,sent)));
            assertEquals(List.of(notification.eventId()),sent);
        }
    }

    @Test
    void sendFailurePreservesDomainCommitAndCrashReclaimFencesOldPublisher() {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            var repository = new JdbcTransportRepository(fixture.jdbc);
            var transactions = transactions(fixture);
            EventEnvelope event = insert(fixture, NOW);
            fixture.jdbc.update("UPDATE outbox_events SET correlation_id='request-321' WHERE event_id=?",event.eventId());
            var first = transactions.execute(status -> repository.claim(NOW, 1)).getFirst();
            assertEquals(event.eventId(), first.envelope().eventId());
            assertEquals("request-321",first.envelope().correlationId());
            assertTrue(transactions.execute(status -> repository.claim(NOW.plusSeconds(59), 1)).isEmpty());
            var second = transactions.execute(status -> repository.claim(NOW.plusSeconds(60), 1)).getFirst();
            assertNotEquals(first.generation(), second.generation());
            assertFalse(Boolean.TRUE.equals(transactions.execute(status -> repository.complete(event.eventId(), first.generation(), NOW.plusSeconds(61)))));
            assertTrue(Boolean.TRUE.equals(transactions.execute(status -> repository.complete(event.eventId(), second.generation(), NOW.plusSeconds(61)))));
            assertEquals("PUBLISHED", fixture.jdbc.queryForObject("SELECT status FROM outbox_events WHERE event_id=?", String.class, event.eventId()));
        }
    }

    @Test
    void queueOutageLeavesCommittedOutboxDueAndDoesNotRepeatDomainAction() {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            var repository = new JdbcTransportRepository(fixture.jdbc);
            var tx = transactions(fixture);
            EventEnvelope event = insert(fixture, NOW);
            var ingestion = new EventIngestionService(repository, List.of(EventIngestionServiceTest.handler(new ArrayList<>())), Clock.fixed(NOW, ZoneOffset.UTC));
            var dispatcher = new EventDispatchService(repository, ingestion, tx.getTransactionManager(), Clock.fixed(NOW, ZoneOffset.UTC));
            EventTransport failed = transport(true, new ArrayList<>());
            assertEquals(0, dispatcher.publish(failed));
            assertEquals("FAILED", fixture.jdbc.queryForObject("SELECT status FROM outbox_events WHERE event_id=?", String.class, event.eventId()));
            assertEquals(1, fixture.jdbc.queryForObject("SELECT attempt_count FROM outbox_events WHERE event_id=?", Integer.class, event.eventId()));
            fixture.jdbc.update("UPDATE outbox_events SET next_attempt_at=? WHERE event_id=?", Timestamp.from(NOW), event.eventId());
            List<UUID> sent = new ArrayList<>();
            assertEquals(1, dispatcher.publish(transport(false, sent)));
            assertEquals(List.of(event.eventId()), sent);
            assertEquals(0, dispatcher.publish(transport(false, sent)));
        }
    }

    @Test
    void inboxAndSideEffectRollbackTogetherAndConcurrentDuplicatesCommitOnce() throws Exception {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            fixture.jdbc.execute("CREATE TABLE test_delivery(event_id UUID PRIMARY KEY)");
            var repository = new JdbcTransportRepository(fixture.jdbc);
            var tx = transactions(fixture);
            EventEnvelope event = insert(fixture, NOW);
            assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
                assertTrue(repository.admit("notifications", event, NOW));
                fixture.jdbc.update("INSERT INTO test_delivery(event_id) VALUES(?)", event.eventId());
                throw new IllegalStateException("simulated crash");
            }));
            assertEquals(0, fixture.jdbc.queryForObject("SELECT count(event_id) FROM inbox_events", Integer.class));
            CountDownLatch start = new CountDownLatch(1);
            try (var executor = Executors.newFixedThreadPool(2)) {
                var one = executor.submit(() -> materialize(start, tx, repository, fixture, event));
                var two = executor.submit(() -> materialize(start, tx, repository, fixture, event));
                start.countDown();
                one.get(10, TimeUnit.SECONDS);
                two.get(10, TimeUnit.SECONDS);
            }
            assertEquals(1, fixture.jdbc.queryForObject("SELECT count(event_id) FROM inbox_events", Integer.class));
            assertEquals(1, fixture.jdbc.queryForObject("SELECT count(event_id) FROM test_delivery", Integer.class));
        }
    }

    @Test
    void replayRateLimitIsSharedAndCancellationPreservesOriginalIdentity() {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            var repository = new JdbcTransportRepository(fixture.jdbc);
            var tx = transactions(fixture);
            List<UUID> events = new ArrayList<>();
            for (int index = 0; index < 12; index++) {
                events.add(insert(fixture, NOW).eventId());
            }
            var replay = tx.execute(status -> repository.requestReplay("operator", "key", "hash", "notifications", "verified repair", events, NOW));
            var retry = tx.execute(status -> repository.requestReplay("operator", "key", "hash", "notifications", "verified repair", events, NOW));
            assertEquals(replay.id(), retry.id());
            for (int index = 0; index < 10; index++) {
                var item = tx.execute(status -> repository.claimReplay(NOW)).orElseThrow();
                assertTrue(events.contains(item.publication().envelope().eventId()));
                tx.executeWithoutResult(status -> repository.completeReplay(item, "PUBLISHED", NOW));
            }
            assertTrue(tx.execute(status -> repository.claimReplay(NOW)).isEmpty());
            assertTrue(tx.execute(status -> repository.claimReplay(NOW.plusSeconds(1))).isPresent());
            tx.executeWithoutResult(status -> repository.cancel(replay.id(), NOW.plusSeconds(62)));
            assertEquals("CANCELLED", repository.findReplay(replay.id()).orElseThrow().status());
            assertTrue(tx.execute(status -> repository.claimReplay(NOW.plusSeconds(63))).isEmpty());
        }
    }

    private static void materialize(CountDownLatch start, TransactionTemplate tx, TransportRepository repository,
                                    PostgresFixture fixture, EventEnvelope event) {
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("start timed out");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
        tx.executeWithoutResult(status -> {
            if (repository.admit("notifications", event, NOW)) {
                fixture.jdbc.update("INSERT INTO test_delivery(event_id) VALUES(?)", event.eventId());
            }
        });
    }

    private static EventEnvelope insert(PostgresFixture fixture, Instant occurredAt) {
        EventEnvelope event = EventIngestionServiceTest.event(occurredAt);
        fixture.jdbc.update("INSERT INTO outbox_events(event_id,event_type,schema_version,aggregate_type,aggregate_public_id,payload,status,created_at) "
            + "VALUES(?,?,1,?,?,'{}','PENDING',?)", event.eventId(), event.eventType(), event.aggregateType(), event.aggregatePublicId(), Timestamp.from(occurredAt));
        return event;
    }

    private static TransactionTemplate transactions(PostgresFixture fixture) {
        return new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(fixture.jdbc.getDataSource())));
    }

    private static EventTransport transport(boolean fail, List<UUID> sent) {
        return new EventTransport() {
            public void send(String consumer, EventEnvelope envelope) {
                if (fail) {
                    throw new IllegalStateException("queue unavailable");
                }
                sent.add(envelope.eventId());
            }
            public List<Message> receive(String consumer) { return List.of(); }
            public void acknowledge(String consumer, String receipt) { }
            public void extend(String consumer, String receipt) { }
            public long deadLetterCount(String consumer) { return 0; }
        };
    }
}
