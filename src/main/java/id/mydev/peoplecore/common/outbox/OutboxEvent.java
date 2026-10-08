package id.mydev.peoplecore.common.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    public enum Status {
        PENDING,
        PUBLISHED,
        FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;

    @Column(name = "event_type", nullable = false, length = 128)
    private String eventType;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;

    @Column(name = "aggregate_type", nullable = false, length = 128)
    private String aggregateType;

    @Column(name = "aggregate_id")
    private Long aggregateId;

    @Column(name = "aggregate_public_id")
    private UUID aggregatePublicId;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    protected OutboxEvent() {
    }

    public OutboxEvent(
        UUID eventId,
        String eventType,
        int schemaVersion,
        String aggregateType,
        Long aggregateId,
        UUID aggregatePublicId,
        String payload,
        Instant createdAt
    ) {
        this.eventId = Objects.requireNonNull(eventId, "eventId must not be null");
        this.eventType = Objects.requireNonNull(eventType, "eventType must not be null");
        this.schemaVersion = schemaVersion;
        this.aggregateType = Objects.requireNonNull(aggregateType, "aggregateType must not be null");
        this.aggregateId = aggregateId;
        this.aggregatePublicId = aggregatePublicId;
        this.payload = Objects.requireNonNull(payload, "payload must not be null");
        this.status = Status.PENDING.name();
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.attemptCount = 0;
    }

    public void markPublished(Instant publishedAt) {
        this.status = Status.PUBLISHED.name();
        this.publishedAt = Objects.requireNonNull(publishedAt, "publishedAt must not be null");
    }

    public void recordAttempt(Instant attemptAt, boolean failed) {
        this.attemptCount++;
        this.lastAttemptAt = Objects.requireNonNull(attemptAt, "attemptAt must not be null");
        if (failed) {
            this.status = Status.FAILED.name();
        }
    }

    public Long getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public Long getAggregateId() {
        return aggregateId;
    }

    public UUID getAggregatePublicId() {
        return aggregatePublicId;
    }

    public String getPayload() {
        return payload;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }
}
