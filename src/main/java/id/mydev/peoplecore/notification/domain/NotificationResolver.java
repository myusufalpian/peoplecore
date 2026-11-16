package id.mydev.peoplecore.notification.domain;

import id.mydev.peoplecore.notification.domain.NotificationModels.Intent;
import id.mydev.peoplecore.notification.domain.NotificationModels.Recipient;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface NotificationResolver {
    boolean supports(String eventType);
    List<Recipient> resolve(String eventType, UUID aggregateId, Instant occurredAt);
    boolean eligible(Intent intent, Instant now);
}
