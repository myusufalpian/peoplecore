package id.mydev.peoplecore.common.outbox;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.util.Assert;

public record EventEnvelope(UUID eventId, String eventType, int schemaVersion, String aggregateType,
                            UUID aggregatePublicId, Instant occurredAt, String correlationId,
                            Map<String, UUID> payload) {
    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId is required");
        Objects.requireNonNull(aggregatePublicId, "aggregatePublicId is required");
        Objects.requireNonNull(occurredAt, "occurredAt is required");
        Assert.isTrue(eventType != null && eventType.matches("[A-Z][A-Z0-9_]{0,127}"), "Invalid event type");
        Assert.isTrue(aggregateType != null && aggregateType.matches("[A-Z][A-Z0-9_]{0,127}"), "Invalid aggregate type");
        Assert.isTrue(schemaVersion == 1, "Unsupported event schema");
        Assert.isTrue(correlationId == null || correlationId.matches("[A-Za-z0-9_-]{1,128}"), "Invalid correlation ID");
        payload = Map.copyOf(Objects.requireNonNull(payload, "payload is required"));
        Assert.isTrue(payload.keySet().stream().allMatch(key -> key.matches("[a-zA-Z]{1,40}Id")), "Only public references are permitted");
    }
}
