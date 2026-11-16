package id.mydev.peoplecore.infrastructure.persistence;

import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.common.command.CommandConflictException;
import id.mydev.peoplecore.common.outbox.EventEnvelope;
import id.mydev.peoplecore.common.outbox.TransportRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTransportRepository implements TransportRepository {
    private static final String EVENT_COLUMNS = "o.event_id,o.event_type,o.schema_version,o.aggregate_type,"
        + "o.aggregate_public_id,o.created_at,o.lease_generation,o.attempt_count,o.correlation_id";
    private final JdbcTemplate jdbc;

    public JdbcTransportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Publication> claim(Instant now, int limit) {
        jdbc.update("UPDATE outbox_events SET status='QUARANTINED',transport_reason='INVALID_PUBLIC_ENVELOPE' "
            + "WHERE id IN (SELECT id FROM outbox_events WHERE status IN ('PENDING','FAILED') "
            + "AND (aggregate_public_id IS NULL OR schema_version<>1 OR event_type !~ '^[A-Z][A-Z0-9_]{0,127}$' "
            + "OR aggregate_type !~ '^[A-Z][A-Z0-9_]{0,127}$') ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED)");
        return jdbc.query("WITH due AS (SELECT id FROM outbox_events WHERE status IN ('PENDING','FAILED') "
            + "AND aggregate_public_id IS NOT NULL AND schema_version=1 AND event_type ~ '^[A-Z][A-Z0-9_]{0,127}$' "
            + "AND aggregate_type ~ '^[A-Z][A-Z0-9_]{0,127}$' "
            + "AND (lease_until IS NULL OR lease_until<=?) AND (next_attempt_at IS NULL OR next_attempt_at<=?) "
            + "ORDER BY created_at,id LIMIT ? FOR UPDATE SKIP LOCKED) UPDATE outbox_events o "
            + "SET lease_generation=o.lease_generation+1,lease_until=?,attempt_count=o.attempt_count+1,last_attempt_at=? "
            + "FROM due WHERE o.id=due.id RETURNING " + EVENT_COLUMNS,
            (rs, row) -> publication(rs), timestamp(now), timestamp(now), limit,
            timestamp(now.plusSeconds(60)), timestamp(now));
    }

    @Override
    public boolean complete(UUID id, long generation, Instant now) {
        return jdbc.update("UPDATE outbox_events SET status='PUBLISHED',published_at=?,lease_until=NULL,transport_reason=NULL "
            + "WHERE event_id=? AND lease_generation=? AND lease_until>?", timestamp(now), id, generation, timestamp(now)) == 1;
    }

    @Override
    public boolean ignore(UUID id,long generation,Instant now) {
        return jdbc.update("UPDATE outbox_events SET status='IGNORED',lease_until=NULL,transport_reason='NO_ASYNC_SUBSCRIBER' "
            + "WHERE event_id=? AND lease_generation=? AND lease_until>?",id,generation,timestamp(now))==1;
    }

    @Override
    public void retry(UUID id, long generation, Instant now, Instant next) {
        jdbc.update("UPDATE outbox_events SET status='FAILED',next_attempt_at=?,lease_until=NULL,transport_reason='PUBLICATION_DEFERRED' "
            + "WHERE event_id=? AND lease_generation=? AND lease_until>?", timestamp(next), id, generation, timestamp(now));
    }

    @Override
    public void abandon(UUID id, long generation, String reason) {
        jdbc.update("UPDATE outbox_events SET status='QUARANTINED',lease_until=NULL,transport_reason=? "
            + "WHERE event_id=? AND lease_generation=?", reason, id, generation);
    }

    @Override
    public boolean admit(String consumer, EventEnvelope event, Instant now) {
        return jdbc.update("INSERT INTO inbox_events(consumer,event_id,occurred_at,received_at) VALUES(?,?,?,?) "
            + "ON CONFLICT(consumer,event_id) DO NOTHING", consumer, event.eventId(), timestamp(event.occurredAt()), timestamp(now)) == 1;
    }

    @Override
    public void quarantine(String consumer, UUID eventId, String reason, Instant now) {
        jdbc.update("INSERT INTO event_quarantine(id,event_id,consumer,reason,created_at) VALUES(?,?,?,?,?) "
            + "ON CONFLICT(consumer,event_id) DO NOTHING", UUID.randomUUID(), eventId, consumer, reason, timestamp(now));
    }

    @Override
    public Optional<EventEnvelope> findEvent(UUID id) {
        return jdbc.query("SELECT " + EVENT_COLUMNS + " FROM outbox_events o WHERE event_id=? AND aggregate_public_id IS NOT NULL AND schema_version=1",
            (rs, row) -> envelope(rs), id).stream().findFirst();
    }

    @Override
    public Replay requestReplay(String actor, String key, String hash, String consumer, String reason,
                                List<UUID> events, Instant now) {
        UUID id = UUID.randomUUID();
        int created = jdbc.update("INSERT INTO event_replays(id,actor_id,idempotency_key,request_hash,consumer,reason,status,created_at) "
            + "VALUES(?,?,?,?,?,?,'PENDING',?) ON CONFLICT(actor_id,idempotency_key) DO NOTHING",
            id, actor, key, hash, consumer, reason, timestamp(now));
        if (created == 0) {
            return jdbc.query("SELECT id,consumer,status,created_at,request_hash FROM event_replays WHERE actor_id=? AND idempotency_key=?",
                (rs, row) -> {
                    if (!hash.equals(rs.getString("request_hash"))) {
                        throw new CommandConflictException("Replay key already used");
                    }
                    return replay(rs);
                }, actor, key).getFirst();
        }
        events.forEach(event -> jdbc.update("INSERT INTO event_replay_items(replay_id,event_id,status) VALUES(?,?,'PENDING')", id, event));
        return new Replay(id, consumer, "PENDING", now);
    }

    @Override
    public Replay cancel(UUID id, Instant now) {
        jdbc.update("UPDATE event_replays SET status='CANCELLED' WHERE id=? AND status='PENDING'", id);
        jdbc.update("UPDATE event_replay_items SET status='CANCELLED',completed_at=? WHERE replay_id=? AND status='PENDING' "
            + "AND (lease_until IS NULL OR lease_until<=?)", timestamp(now), id, timestamp(now));
        return findReplay(id).orElseThrow(ResourceNotFoundException::new);
    }

    @Override
    public Optional<Replay> findReplay(UUID id) {
        return jdbc.query("SELECT id,consumer,status,created_at FROM event_replays WHERE id=?", (rs, row) -> replay(rs), id)
            .stream().findFirst();
    }

    @Override
    public Optional<ReplayItem> claimReplay(Instant now) {
        jdbc.queryForObject("SELECT singleton FROM event_transport_budget WHERE singleton=1 FOR UPDATE", Integer.class);
        jdbc.update("UPDATE event_transport_budget SET window_start=?,used=0 WHERE singleton=1 AND window_start<=?",
            timestamp(now), timestamp(now.minusSeconds(1)));
        Integer used = jdbc.queryForObject("SELECT used FROM event_transport_budget WHERE singleton=1", Integer.class);
        if (used == null || used >= 10) {
            return Optional.empty();
        }
        List<ReplayItem> items = jdbc.query("SELECT r.id replay_id,r.consumer," + EVENT_COLUMNS
            + ",i.lease_generation item_generation FROM event_replay_items i JOIN event_replays r ON r.id=i.replay_id "
            + "JOIN outbox_events o ON o.event_id=i.event_id WHERE r.status='PENDING' AND i.status='PENDING' "
            + "AND (i.lease_until IS NULL OR i.lease_until<=?) ORDER BY r.created_at,o.created_at LIMIT 1 FOR UPDATE OF i SKIP LOCKED",
            (rs, row) -> new ReplayItem(rs.getObject("replay_id", UUID.class), rs.getString("consumer"),
                new Publication(envelope(rs), rs.getLong("item_generation") + 1, rs.getInt("attempt_count"))), timestamp(now));
        if (items.isEmpty()) {
            return Optional.empty();
        }
        ReplayItem item = items.getFirst();
        jdbc.update("UPDATE event_replay_items SET lease_generation=?,lease_until=? WHERE replay_id=? AND event_id=?",
            item.publication().generation(), timestamp(now.plusSeconds(60)), item.replayId(), item.publication().envelope().eventId());
        jdbc.update("UPDATE event_transport_budget SET used=used+1 WHERE singleton=1");
        return Optional.of(item);
    }

    @Override
    public void completeReplay(ReplayItem item, String status, Instant now) {
        jdbc.update("UPDATE event_replay_items SET status=?,completed_at=?,lease_until=NULL WHERE replay_id=? AND event_id=? "
            + "AND lease_generation=? AND lease_until>?", status, timestamp(now), item.replayId(),
            item.publication().envelope().eventId(), item.publication().generation(), timestamp(now));
        jdbc.update("UPDATE event_replays SET status='COMPLETE' WHERE id=? AND status='PENDING' "
            + "AND NOT EXISTS (SELECT 1 FROM event_replay_items WHERE replay_id=? AND status='PENDING')", item.replayId(), item.replayId());
    }

    @Override
    public long oldestPendingSeconds(Instant now) {
        Timestamp oldest = jdbc.queryForObject("SELECT min(created_at) FROM outbox_events WHERE status IN ('PENDING','FAILED')", Timestamp.class);
        return oldest == null ? 0 : Math.max(0, Duration.between(oldest.toInstant(), now).toSeconds());
    }

    @Override
    public long quarantineCount() {
        Long count = jdbc.queryForObject("SELECT (SELECT count(id) FROM event_quarantine) + "
            + "(SELECT count(o.id) FROM outbox_events o WHERE o.status='QUARANTINED' "
            + "AND NOT EXISTS (SELECT 1 FROM event_quarantine q WHERE q.event_id=o.event_id))", Long.class);
        return count == null ? 0 : count;
    }

    private static Publication publication(ResultSet rs) throws SQLException {
        return new Publication(envelope(rs), rs.getLong("lease_generation"), rs.getInt("attempt_count"));
    }

    private static EventEnvelope envelope(ResultSet rs) throws SQLException {
        return new EventEnvelope(rs.getObject("event_id", UUID.class), rs.getString("event_type"), rs.getInt("schema_version"),
            rs.getString("aggregate_type"), rs.getObject("aggregate_public_id", UUID.class),
            rs.getTimestamp("created_at").toInstant(), rs.getString("correlation_id"), Map.of());
    }

    private static Replay replay(ResultSet rs) throws SQLException {
        return new Replay(rs.getObject("id", UUID.class), rs.getString("consumer"), rs.getString("status"), rs.getTimestamp("created_at").toInstant());
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }
}
