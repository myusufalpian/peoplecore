package id.mydev.peoplecore.notification.domain.repository;

import id.mydev.peoplecore.notification.domain.NotificationAttentionSink.Attention;
import java.time.Instant;
import java.util.Optional;

public interface NotificationAttentionRepository {
    Optional<Claim> claim(Instant now);
    boolean complete(Claim claim,Instant now);
    void defer(Claim claim,Instant now);

    record Claim(Attention attention,long generation) { }
}
