package id.mydev.peoplecore.attendance.infrastructure.persistence;

import id.mydev.peoplecore.notification.domain.NotificationModels.Intent;
import id.mydev.peoplecore.notification.domain.NotificationModels.Recipient;
import id.mydev.peoplecore.notification.domain.NotificationResolver;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class AttendanceNotificationResolver implements NotificationResolver {
    private static final Set<String> ACTIONS=Set.of("SUBMIT","RESUBMIT","WITHDRAW","APPROVE","REJECT","RETURN","REASSIGN","ROUTE_RETRY");
    private final JdbcTemplate jdbc;
    public AttendanceNotificationResolver(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Override public boolean supports(String type) { return type.startsWith("ATTENDANCE_CORRECTION_") && ACTIONS.contains(type.substring("ATTENDANCE_CORRECTION_".length())); }
    @Override public List<Recipient> resolve(String type,UUID aggregate,Instant at) {
        var rows=jdbc.query("SELECT p.id,e.public_id,p.status FROM attendance_correction p JOIN employees e ON e.id=p.employee_id WHERE p.public_id=?",
            (r,i)->new Source(r.getLong(1),r.getObject(2,UUID.class),r.getString(3)),aggregate);
        if(rows.isEmpty()) return List.of();
        Source source=rows.getFirst();
        List<Recipient> result=new ArrayList<>();
        String action=type.substring("ATTENDANCE_CORRECTION_".length());
        boolean pending="PENDING".equals(source.state());
        if(pending && Set.of("SUBMIT","RESUBMIT","APPROVE","REASSIGN","ROUTE_RETRY").contains(action)) {
            var reviewer=reviewer(source.id());
            result.addAll(reviewer);
            if(reviewer.isEmpty()) result.add(owner(source.employee(),"REVIEW_BLOCKED"));
        }
        if(!pending || "REASSIGN".equals(action)) result.add(owner(source.employee(),"REASSIGN".equals(action)?"REVIEW_REASSIGNED":
            "WITHDRAW".equals(action)?"REQUEST_CLOSED":"REQUEST_STATUS"));
        if("WITHDRAW".equals(action)) result.addAll(closedReviewers(source.id()));
        return List.copyOf(result);
    }
    @Override public boolean eligible(Intent intent,Instant now) {
        if("OWNER".equals(intent.eligibilityType())) return true;
        if("CLOSED_REVIEW".equals(intent.eligibilityType())) {
            var closed=jdbc.query("SELECT id FROM attendance_correction WHERE public_id=? AND status IN ('WITHDRAWN','CANCELLED')",(r,i)->r.getLong(1),intent.aggregateId());
            return !closed.isEmpty() && authority(intent,now) && closedReviewers(closed.getFirst()).stream()
                .anyMatch(r->r.accountId().equals(intent.accountId())&&r.assignmentId().equals(intent.assignmentId()));
        }
        var ids=jdbc.query("SELECT id FROM attendance_correction WHERE public_id=? AND status='PENDING'",(r,i)->r.getLong(1),intent.aggregateId());
        if("HR_RECOVERY".equals(intent.eligibilityType())) return !ids.isEmpty() && reviewer(ids.getFirst()).isEmpty();
        return !ids.isEmpty() && authority(intent,now) && reviewer(ids.getFirst()).stream().anyMatch(r->r.accountId().equals(intent.accountId()) && r.assignmentId().equals(intent.assignmentId()));
    }
    private boolean authority(Intent intent,Instant now) {
        Integer count=jdbc.queryForObject("SELECT count(ra.id) FROM attendance_correction p JOIN user_accounts ua ON ua.public_id=? "
            + "JOIN account_bindings rb ON rb.account_id=ua.id AND rb.revoked_at IS NULL "
            + "JOIN role_assignments ra ON ra.account_id=ua.id AND ra.effective_from<=? AND (ra.effective_to IS NULL OR ra.effective_to>?) "
            + "LEFT JOIN employee_assignments ea ON ea.employee_id=p.employee_id AND ea.superseded_at IS NULL AND ea.valid_from<=? AND (ea.valid_to IS NULL OR ea.valid_to>?) "
            + "WHERE p.public_id=? AND ((ra.role='HR_ADMIN' AND (ra.scope IS NULL OR ra.scope='' OR ra.scope=ea.org_unit)) OR (ra.role='MANAGER' AND (ra.scope IS NULL OR ra.scope='' OR ra.scope=ea.org_unit) AND ea.manager_employee_id=rb.employee_id))",Integer.class,
            intent.accountId(),Timestamp.from(now),Timestamp.from(now),Timestamp.from(now),Timestamp.from(now),intent.aggregateId());
        return count!=null && count>0;
    }
    private List<Recipient> reviewer(long id) {
        return jdbc.query("SELECT a.public_id,u.public_id,e.public_id FROM attendance_correction_assignment a JOIN user_accounts u ON u.id=a.reviewer_id JOIN account_bindings b ON b.account_id=u.id AND b.revoked_at IS NULL JOIN employees e ON e.id=b.employee_id WHERE a.revision_id=(SELECT active_revision_id FROM attendance_correction WHERE id=?) AND ? IS NOT NULL ORDER BY a.id DESC LIMIT 1",
            (r,i)->new Recipient(r.getObject(3,UUID.class),r.getObject(2,UUID.class),"REVIEW_REQUIRED","ATTENDANCE_REVIEW",
                r.getObject(1,UUID.class)),id,id);
    }
    private List<Recipient> closedReviewers(long id) {
        return jdbc.query("SELECT a.public_id,u.public_id,e.public_id FROM attendance_correction_assignment a JOIN user_accounts u ON u.id=a.reviewer_id JOIN account_bindings b ON b.account_id=u.id AND b.revoked_at IS NULL JOIN employees e ON e.id=b.employee_id WHERE a.revision_id=(SELECT active_revision_id FROM attendance_correction WHERE id=?) AND ? IS NOT NULL ORDER BY a.id DESC LIMIT 1",
            (r,i)->new Recipient(r.getObject(3,UUID.class),r.getObject(2,UUID.class),"REQUEST_CLOSED","CLOSED_REVIEW",
                r.getObject(1,UUID.class)),id,id);
    }
    private Recipient owner(UUID employee,String template) {
        var accounts=jdbc.query("SELECT u.public_id FROM account_bindings b JOIN user_accounts u ON u.id=b.account_id WHERE b.employee_public_id=? AND b.revoked_at IS NULL",
            (r,i)->r.getObject(1,UUID.class),employee);
        return new Recipient(employee,accounts.isEmpty()?null:accounts.getFirst(),template,"OWNER",null);
    }
    private record Source(long id,UUID employee,String state) { }
}
