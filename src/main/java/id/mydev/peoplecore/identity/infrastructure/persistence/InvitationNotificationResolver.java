package id.mydev.peoplecore.identity.infrastructure.persistence;

import id.mydev.peoplecore.notification.domain.NotificationModels.Intent;
import id.mydev.peoplecore.notification.domain.NotificationModels.Recipient;
import id.mydev.peoplecore.notification.domain.NotificationResolver;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class InvitationNotificationResolver implements NotificationResolver {
    private final JdbcTemplate jdbc;
    public InvitationNotificationResolver(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Override public boolean supports(String type) { return "INVITATION_ISSUE".equals(type)||"INVITATION_REISSUE".equals(type); }
    @Override public List<Recipient> resolve(String type,UUID aggregate,Instant at) {
        return jdbc.query("SELECT e.public_id FROM enrollment_invitations i JOIN employees e ON e.id=i.employee_id WHERE i.public_id=?",
            (r,i)->new Recipient(r.getObject(1,UUID.class),null,"INVITATION","INVITATION",null),aggregate);
    }
    @Override public boolean eligible(Intent intent,Instant now) {
        Integer count=jdbc.queryForObject("SELECT count(i.id) FROM enrollment_invitations i JOIN employees e ON e.id=i.employee_id "
            + "WHERE i.public_id=? AND i.status='ISSUED' AND i.expires_at>? AND e.status='ACTIVE'",Integer.class,intent.aggregateId(),Timestamp.from(now));
        return count!=null && count==1;
    }
}
