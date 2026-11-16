package id.mydev.peoplecore.leave.infrastructure.persistence;

import id.mydev.peoplecore.notification.domain.NotificationModels.Intent;
import id.mydev.peoplecore.notification.domain.NotificationModels.Recipient;
import id.mydev.peoplecore.notification.domain.NotificationResolver;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class CancellationNotificationResolver implements NotificationResolver {
    private static final Set<String> TYPES=Set.of("LEAVE_CANCELLATION_CREATE","LEAVE_CANCELLATION_WITHDRAW",
        "LEAVE_CANCELLATION_APPROVE","LEAVE_CANCELLATION_REJECT","LEAVE_CANCELLATION_REASSIGN","LEAVE_CANCELLATION_RETRY_ROUTE");
    private final JdbcTemplate jdbc;
    public CancellationNotificationResolver(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Override public boolean supports(String type) { return TYPES.contains(type); }
    @Override public List<Recipient> resolve(String type,UUID aggregate,Instant at) {
        var rows=jdbc.query("SELECT c.id,e.public_id,c.status FROM leave_cancellation c JOIN leave_request r ON r.id=c.request_id "
            + "JOIN employees e ON e.id=r.employee_id WHERE c.public_id=?",(r,i)->new Source(r.getLong(1),r.getObject(2,UUID.class),r.getString(3)),aggregate);
        if(rows.isEmpty()) return List.of();
        Source source=rows.getFirst();
        List<Recipient> result=new ArrayList<>();
        if("PENDING".equals(source.status())) {
            result.addAll(reviewers(source.id()));
            if(result.isEmpty()) result.add(owner(source.employee(),"REVIEW_BLOCKED"));
        } else result.add(owner(source.employee(),"REQUEST_CLOSED"));
        if(type.endsWith("REASSIGN")) result.add(owner(source.employee(),"REVIEW_REASSIGNED"));
        if(type.endsWith("WITHDRAW")||type.endsWith("APPROVE")) result.addAll(closedReviewers(source.id()));
        return List.copyOf(result);
    }
    @Override public boolean eligible(Intent intent,Instant now) {
        if("OWNER".equals(intent.eligibilityType())) return true;
        if("CLOSED_REVIEW".equals(intent.eligibilityType())) {
            var closed=jdbc.query("SELECT id FROM leave_cancellation WHERE public_id=? AND status IN ('WITHDRAWN','APPROVED')",(r,i)->r.getLong(1),intent.aggregateId());
            return !closed.isEmpty() && authority(intent,now) && closedReviewers(closed.getFirst()).stream()
                .anyMatch(r->r.accountId().equals(intent.accountId())&&r.assignmentId().equals(intent.assignmentId()));
        }
        var ids=jdbc.query("SELECT id FROM leave_cancellation WHERE public_id=? AND status='PENDING'",(r,i)->r.getLong(1),intent.aggregateId());
        if("HR_RECOVERY".equals(intent.eligibilityType())) return !ids.isEmpty() && reviewers(ids.getFirst()).isEmpty();
        return !ids.isEmpty() && authority(intent,now) && reviewers(ids.getFirst()).stream().anyMatch(r->r.accountId().equals(intent.accountId()) && r.assignmentId().equals(intent.assignmentId()));
    }
    private boolean authority(Intent intent,Instant now) {
        Integer count=jdbc.queryForObject("SELECT count(ra.id) FROM leave_cancellation c JOIN leave_request r ON r.id=c.request_id "
            + "JOIN user_accounts ua ON ua.public_id=? JOIN role_assignments ra ON ra.account_id=ua.id "
            + "AND ra.role='HR_ADMIN' AND ra.effective_from<=? AND (ra.effective_to IS NULL OR ra.effective_to>?) "
            + "LEFT JOIN employee_assignments ea ON ea.employee_id=r.employee_id AND ea.superseded_at IS NULL "
            + "AND ea.valid_from<=? AND (ea.valid_to IS NULL OR ea.valid_to>?) WHERE c.public_id=? "
            + "AND (ra.scope IS NULL OR ra.scope='' OR ra.scope=ea.org_unit)",Integer.class,intent.accountId(),Timestamp.from(now),
            Timestamp.from(now),Timestamp.from(now),Timestamp.from(now),intent.aggregateId());
        return count!=null && count>0;
    }
    private List<Recipient> reviewers(long id) {
        return jdbc.query("SELECT a.id,u.public_id,e.public_id FROM leave_cancellation_assignment a "
            + "JOIN user_accounts u ON u.public_id=a.reviewer_account_public_id JOIN account_bindings b ON b.account_id=u.id AND b.revoked_at IS NULL "
            + "JOIN employees e ON e.id=b.employee_id WHERE a.cancellation_id=? AND a.active ORDER BY a.id DESC LIMIT 1",
            (r,i)->new Recipient(r.getObject(3,UUID.class),r.getObject(2,UUID.class),"REVIEW_REQUIRED","LEAVE_REVIEW",
                UUID.nameUUIDFromBytes(("cancellation:"+r.getLong(1)).getBytes(StandardCharsets.UTF_8))),id);
    }
    private List<Recipient> closedReviewers(long id) {
        return jdbc.query("SELECT a.id,u.public_id,e.public_id FROM leave_cancellation_assignment a "
            + "JOIN user_accounts u ON u.public_id=a.reviewer_account_public_id JOIN account_bindings b ON b.account_id=u.id AND b.revoked_at IS NULL "
            + "JOIN employees e ON e.id=b.employee_id WHERE a.cancellation_id=? ORDER BY a.id DESC LIMIT 1",
            (r,i)->new Recipient(r.getObject(3,UUID.class),r.getObject(2,UUID.class),"REQUEST_CLOSED","CLOSED_REVIEW",
                UUID.nameUUIDFromBytes(("cancellation:"+r.getLong(1)).getBytes(StandardCharsets.UTF_8))),id);
    }
    private Recipient owner(UUID employee,String template) {
        var accounts=jdbc.query("SELECT u.public_id FROM account_bindings b JOIN user_accounts u ON u.id=b.account_id "
            + "WHERE b.employee_public_id=? AND b.revoked_at IS NULL",(r,i)->r.getObject(1,UUID.class),employee);
        return new Recipient(employee,accounts.isEmpty()?null:accounts.getFirst(),template,"OWNER",null);
    }
    private record Source(long id,UUID employee,String status) { }
}
