package id.mydev.peoplecore.payslip.infrastructure.persistence;

import id.mydev.peoplecore.notification.domain.NotificationModels.Intent;
import id.mydev.peoplecore.notification.domain.NotificationModels.Recipient;
import id.mydev.peoplecore.notification.domain.NotificationResolver;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class PayslipNotificationResolver implements NotificationResolver {
    private final JdbcTemplate jdbc;
    public PayslipNotificationResolver(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Override public boolean supports(String type) { return "PAYSLIP_PUBLISHED".equals(type); }
    @Override public List<Recipient> resolve(String type,UUID aggregate,Instant at) {
        return jdbc.query("SELECT e.public_id,u.public_id FROM payslip_publication p JOIN employees e ON e.id=p.employee_id "
            + "LEFT JOIN account_bindings b ON b.employee_id=e.id AND b.revoked_at IS NULL "
            + "LEFT JOIN user_accounts u ON u.id=b.account_id WHERE p.public_id=?",
            (r,i)->new Recipient(r.getObject(1,UUID.class),r.getObject(2,UUID.class),"PAYSLIP_AVAILABLE","OWNER",null),aggregate);
    }
    @Override public boolean eligible(Intent intent,Instant now) {
        Integer count=jdbc.queryForObject("SELECT count(p.id) FROM payslip_publication p JOIN payslip_current c ON c.publication_id=p.id "
            + "JOIN payslip_batch_revision r ON r.id=p.revision_id JOIN payslip_batch b ON b.id=r.batch_id "
            + "WHERE p.public_id=? AND b.status='PUBLISHED'",Integer.class,intent.aggregateId());
        return count!=null && count==1;
    }
}
