package id.mydev.peoplecore.notification.domain;

import java.time.Instant;
import java.util.UUID;

public final class NotificationModels {
    private NotificationModels() { }
    public record Recipient(UUID employeeId, UUID accountId, String template, String eligibilityType,
                            UUID assignmentId) { }
    public record Intent(UUID eventId, String eventType, UUID aggregateId, Instant occurredAt,
                         UUID employeeId, UUID accountId, String template, String eligibilityType,
                         UUID assignmentId) { }
    public record Delivery(UUID id, UUID eventId, String eventType, UUID aggregateId, Instant occurredAt,
                           UUID employeeId, UUID accountId, String template, String eligibilityType,
                           UUID assignmentId, UUID destinationId, long destinationVersion,
                           String state, long version, long generation, int attempts,
                           boolean manualAttempt, Instant leaseEndsAt, Instant updatedAt) { }
    public record Destination(UUID id, UUID employeeId, String address, long version, String status) {
        @Override public String toString() { return "Destination[id=" + id + ", version=" + version + "]"; }
    }
    public record Message(UUID deliveryId, UUID attemptId, String address, String subject, String body) {
        @Override public String toString() { return "Message[deliveryId=" + deliveryId + ", attemptId=" + attemptId + "]"; }
    }
    public record Attempt(UUID id, UUID deliveryId, long generation, Instant startedAt, String outcome,
                          String providerMessageId, String errorCode) { }
    public record Summary(UUID id, UUID eventId, String eventType, UUID aggregateId, String template,
                          String state, long version, int attempts, Instant updatedAt) { }
}
