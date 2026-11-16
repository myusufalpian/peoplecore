package id.mydev.peoplecore.common.audit.infrastructure.persistence;

import id.mydev.peoplecore.common.infrastructure.persistence.ReportSql;
import id.mydev.peoplecore.common.reporting.ReportFilter;
import id.mydev.peoplecore.common.reporting.ReportTable;
import id.mydev.peoplecore.common.reporting.ScopedReportQuery;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAuditReportQuery implements ScopedReportQuery {
    private final DataSource source;
    public JdbcAuditReportQuery(DataSource source) { this.source=source; }
    @Override
    public boolean supports(String type,String workflow) { return "AUDIT".equals(type); }

    @Override
    public ReportTable query(ReportFilter f) {
        String owners="SELECT 'EMPLOYEE' domain,e1.public_id resource,e1.id employee FROM employees e1 "
            +"UNION ALL SELECT 'PROFILE_PROPOSAL',p.public_id,p.employee_id FROM profile_proposal p "
            +"UNION ALL SELECT 'PROFILE_PROPOSAL',e2.public_id,e2.id FROM employees e2 "
            +"UNION ALL SELECT 'ATTENDANCE',c.public_id,c.employee_id FROM attendance_correction c "
            +"UNION ALL SELECT 'ATTENDANCE',ce.public_id,ce.employee_id FROM clock_event ce "
            +"UNION ALL SELECT 'LEAVE_ENTITLEMENT',e3.public_id,e3.id FROM employees e3 "
            +"UNION ALL SELECT 'LEAVE_WORKFLOW',q.public_id,q.employee_id FROM leave_request q "
            +"UNION ALL SELECT 'LEAVE_WORKFLOW',lc.public_id,lq.employee_id FROM leave_cancellation lc JOIN leave_request lq ON lq.id=lc.request_id "
            +"UNION ALL SELECT 'REIMBURSEMENT',rc.public_id,rc.employee_id FROM reimbursement_claim rc "
            +"UNION ALL SELECT 'DOCUMENT',doc.public_id,doc.employee_id FROM private_document doc "
            +"UNION ALL SELECT 'PAYSLIP_BATCH',pb.public_id,pi.employee_id FROM payslip_batch pb JOIN payslip_batch_item pi ON pi.revision_id=pb.current_revision_id";
        String sql="SELECT DISTINCT ev.event_id,CASE WHEN (SELECT COUNT(ua.id) FROM user_accounts ua WHERE ua.oidc_subject=ev.actor_id)=1 "
            +"THEN (SELECT CAST(ua.public_id AS VARCHAR) FROM user_accounts ua WHERE ua.oidc_subject=ev.actor_id) ELSE '' END actor_public_id, "
            +"ev.action,ev.aggregate_type,ev.aggregate_public_id,ev.created_at,(ev.reason IS NOT NULL AND LENGTH(TRIM(ev.reason))>0) reason_present "
            +"FROM audit_events ev JOIN ("+owners+") owner ON owner.domain=ev.aggregate_type AND owner.resource=ev.aggregate_public_id "
            +"JOIN employees e ON e.id=owner.employee "+ReportSql.CURRENT_ASSIGNMENT
            +"WHERE ev.created_at>=:fromTime AND ev.created_at<:toTime AND ("
            +"(ev.aggregate_type NOT IN ('REIMBURSEMENT') AND "+ReportSql.employeeScope(true,false,false,false)+") OR "
            +"(ev.aggregate_type IN ('REIMBURSEMENT','DOCUMENT','PAYSLIP_BATCH') AND "+ReportSql.employeeScope(false,false,true,false)+") OR "
            +ReportSql.operationsScope()+")"
            +batchScopeGuard()
            +ReportSql.EMPLOYEE_FILTER+" AND (:resource IS NULL OR ev.aggregate_public_id=CAST(:resource AS UUID)) "
            +"AND (:actor IS NULL OR EXISTS(SELECT 1 FROM user_accounts actor WHERE CAST(actor.public_id AS VARCHAR)=:actor AND actor.oidc_subject=ev.actor_id) AND (SELECT COUNT(identity_account.id) FROM user_accounts identity_account WHERE identity_account.oidc_subject=ev.actor_id)=1) "
            +"AND (:action IS NULL OR ev.action=:action) AND (:correlation IS NULL OR ev.correlation_id=:correlation) "
            +"AND (:workflow IS NULL OR ev.aggregate_type=:workflow) ORDER BY ev.created_at,ev.event_id LIMIT :limit";
        return new ReportTable(List.of("eventId","actorId","action","domain","resourceId","timestamp","reasonPresent"),
            ReportSql.query(source,sql,f,
                List.of("event_id","actor_public_id","action","aggregate_type","aggregate_public_id","created_at","reason_present")));
    }
    private static String batchScopeGuard() {
        return " AND (ev.aggregate_type<>'PAYSLIP_BATCH' OR "+ReportSql.operationsScope()+" OR ("
            +"EXISTS(SELECT 1 FROM payslip_batch known_batch JOIN payslip_batch_item known_item "
            +"ON known_item.revision_id=known_batch.current_revision_id WHERE known_batch.public_id=ev.aggregate_public_id) "
            +"AND NOT EXISTS(SELECT 1 FROM payslip_batch scoped_batch JOIN payslip_batch_item scoped_item "
            +"ON scoped_item.revision_id=scoped_batch.current_revision_id "
            +"JOIN employees batch_employee ON batch_employee.id=scoped_item.employee_id "
            +"LEFT JOIN employee_assignments batch_assignment ON batch_assignment.employee_id=batch_employee.id "
            +"AND batch_assignment.superseded_at IS NULL AND batch_assignment.valid_from<=:now "
            +"AND (batch_assignment.valid_to IS NULL OR batch_assignment.valid_to>:now) "
            +"WHERE scoped_batch.public_id=ev.aggregate_public_id AND NOT EXISTS("
            +"SELECT 1 FROM role_assignments batch_role WHERE batch_role.account_id=:account "
            +"AND batch_role.role IN ('HR_ADMIN','FINANCE_PAYROLL') AND batch_role.effective_from<=:now "
            +"AND (batch_role.effective_to IS NULL OR batch_role.effective_to>:now) "
            +"AND (batch_role.scope IS NULL OR TRIM(batch_role.scope)='' OR batch_role.scope=batch_assignment.org_unit)))))";
    }

}
