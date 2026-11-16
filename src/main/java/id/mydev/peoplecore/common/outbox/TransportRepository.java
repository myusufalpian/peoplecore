package id.mydev.peoplecore.common.outbox;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransportRepository {
    record Publication(EventEnvelope envelope, long generation, int attempts) { }
    record Replay(UUID id, String consumer, String status, Instant createdAt) { }
    record ReplayItem(UUID replayId, String consumer, Publication publication) { }
    List<Publication> claim(Instant now, int limit);
    boolean complete(UUID eventId, long generation, Instant now);
    boolean ignore(UUID eventId, long generation, Instant now);
    void retry(UUID eventId, long generation, Instant now, Instant nextAttempt);
    void abandon(UUID eventId, long generation, String reason);
    boolean admit(String consumer, EventEnvelope event, Instant now);
    void quarantine(String consumer, UUID eventId, String reason, Instant now);
    Optional<EventEnvelope> findEvent(UUID eventId);
    Replay requestReplay(String actor, String key, String hash, String consumer, String reason, List<UUID> events, Instant now);
    Replay cancel(UUID replayId, Instant now);
    Optional<Replay> findReplay(UUID replayId);
    Optional<ReplayItem> claimReplay(Instant now);
    void completeReplay(ReplayItem item, String status, Instant now);
    long oldestPendingSeconds(Instant now);
    long quarantineCount();
}
