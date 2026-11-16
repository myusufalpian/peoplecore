package id.mydev.peoplecore.notification.domain.repository;

import id.mydev.peoplecore.notification.domain.NotificationModels.Delivery;
import id.mydev.peoplecore.notification.domain.NotificationModels.Destination;
import id.mydev.peoplecore.notification.domain.NotificationModels.Intent;
import id.mydev.peoplecore.notification.domain.NotificationModels.Summary;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository {
    void storeIntent(Intent intent, Optional<Destination> destination, Instant now);
    int materialize(UUID eventId, Instant now);
    Optional<Delivery> claim(Instant now);
    Optional<Delivery> find(UUID id, boolean lock);
    List<Summary> list(int offset, int size);
    void expireLeases(Instant now);
    boolean reserve(String key, Instant window, int limit);
    boolean outcome(Delivery delivery, String state, String providerId, String error, Instant nextAttempt, Instant now);
    void evidence(UUID deliveryId, UUID attemptId, long generation, String outcome, String providerId,
                  String error, String sourceId, Instant now);
    Optional<UUID> attemptId(UUID deliveryId, long generation);
    boolean feedback(UUID deliveryId, UUID attemptId, String providerId, String outcome, String sourceId, Instant now);
    record EvidenceSummary(UUID id, UUID attemptId, String outcome, String providerMessageId, Instant createdAt) { }
    List<EvidenceSummary> evidence(UUID deliveryId);
    Optional<Evidence> evidenceReference(UUID id, UUID deliveryId);
    record Evidence(UUID attemptId, String providerId, String outcome) { }
    Optional<String> operationHash(String actor, String key);
    void operation(String actor, String key, String hash, UUID deliveryId, String action, String reason,
                   boolean acknowledgedDuplicate, Instant now);
    void retry(Delivery delivery, Instant now);
    void replacement(UUID id, UUID eventId, Delivery original, Destination destination, Instant now);
    void attention(UUID eventId, UUID deliveryId, String code, Instant now);
    int attentionCount();
}
