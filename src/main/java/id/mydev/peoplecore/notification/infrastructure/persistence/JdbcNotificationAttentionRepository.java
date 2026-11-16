package id.mydev.peoplecore.notification.infrastructure.persistence;

import id.mydev.peoplecore.notification.domain.NotificationAttentionSink.Attention;
import id.mydev.peoplecore.notification.domain.repository.NotificationAttentionRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcNotificationAttentionRepository implements NotificationAttentionRepository {
    private final JdbcTemplate jdbc;
    public JdbcNotificationAttentionRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    @Override public Optional<Claim> claim(Instant now) {
        var ids=jdbc.query("SELECT attention_id FROM notification_attention_alert WHERE delivered_at IS NULL "
            + "AND next_attempt_at<=? AND (lease_until IS NULL OR lease_until<=?) "
            + "ORDER BY next_attempt_at,attention_id LIMIT 1 FOR UPDATE SKIP LOCKED",
            (row,index)->row.getObject(1,UUID.class),Timestamp.from(now),Timestamp.from(now));
        if(ids.isEmpty()) return Optional.empty();
        UUID id=ids.getFirst();
        jdbc.update("UPDATE notification_attention_alert SET lease_generation=lease_generation+1,attempt_count=attempt_count+1,"
            + "lease_until=? WHERE attention_id=?",Timestamp.from(now.plusSeconds(60)),id);
        return jdbc.query("SELECT a.public_id,a.event_id,a.delivery_id,a.code,a.created_at,d.lease_generation "
            + "FROM notification_attention a JOIN notification_attention_alert d ON d.attention_id=a.public_id WHERE a.public_id=?",
            (row,index)->new Claim(new Attention(row.getObject(1,UUID.class),row.getObject(2,UUID.class),
                row.getObject(3,UUID.class),row.getString(4),row.getTimestamp(5).toInstant()),row.getLong(6)),id).stream().findFirst();
    }
    @Override public boolean complete(Claim claim,Instant now) {
        return jdbc.update("UPDATE notification_attention_alert SET delivered_at=?,lease_until=NULL "
            + "WHERE attention_id=? AND lease_generation=? AND lease_until>? AND delivered_at IS NULL",
            Timestamp.from(now),claim.attention().id(),claim.generation(),Timestamp.from(now))==1;
    }
    @Override public void defer(Claim claim,Instant now) {
        jdbc.update("UPDATE notification_attention_alert SET next_attempt_at=?,lease_until=NULL "
            + "WHERE attention_id=? AND lease_generation=? AND lease_until>? AND delivered_at IS NULL",
            Timestamp.from(now.plusSeconds(30)),claim.attention().id(),claim.generation(),Timestamp.from(now));
    }
}
