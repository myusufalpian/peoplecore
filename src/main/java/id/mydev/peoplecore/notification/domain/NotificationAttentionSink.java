package id.mydev.peoplecore.notification.domain;

import java.time.Instant;
import java.util.UUID;

public interface NotificationAttentionSink {
    void send(Attention attention);

    record Attention(UUID id,UUID eventId,UUID deliveryId,String code,Instant createdAt) { }
}
