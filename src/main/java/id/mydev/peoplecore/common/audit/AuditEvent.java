package id.mydev.peoplecore.common.audit;

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
@Table(name = "audit_events")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;

    @Column(name = "actor_id", nullable = false, length = 128)
    private String actorId;

    @Column(name = "action", nullable = false, length = 128)
    private String action;

    @Column(name = "aggregate_type", nullable = false, length = 128)
    private String aggregateType;

    @Column(name = "aggregate_id")
    private Long aggregateId;

    @Column(name = "aggregate_public_id")
    private UUID aggregatePublicId;

    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @Column(name = "details", columnDefinition = "TEXT")
    private String details;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AuditEvent() {
    }

    public AuditEvent(
        UUID eventId,
        String actorId,
        String action,
        String aggregateType,
        Long aggregateId,
        UUID aggregatePublicId,
        String correlationId,
        String reason,
        String details,
        Instant createdAt
    ) {
        this.eventId = Objects.requireNonNull(eventId, "eventId must not be null");
        this.actorId = Objects.requireNonNull(actorId, "actorId must not be null");
        this.action = Objects.requireNonNull(action, "action must not be null");
        this.aggregateType = Objects.requireNonNull(aggregateType, "aggregateType must not be null");
        this.aggregateId = aggregateId;
        this.aggregatePublicId = aggregatePublicId;
        this.correlationId = correlationId;
        this.reason = reason;
        this.details = details;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public Long getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getActorId() {
        return actorId;
    }

    public String getAction() {
        return action;
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

    public String getCorrelationId() {
        return correlationId;
    }

    public String getReason() {
        return reason;
    }

    public String getDetails() {
        return details;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
