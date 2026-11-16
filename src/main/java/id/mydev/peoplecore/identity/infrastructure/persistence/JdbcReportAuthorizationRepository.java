package id.mydev.peoplecore.identity.infrastructure.persistence;

import id.mydev.peoplecore.common.infrastructure.persistence.ReportSql;
import id.mydev.peoplecore.common.reporting.ReportFilter;
import id.mydev.peoplecore.identity.domain.repository.ReportAuthorizationRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcReportAuthorizationRepository implements ReportAuthorizationRepository {
    private final DataSource source;
    public JdbcReportAuthorizationRepository(DataSource source) { this.source=source; }

    @Override
    public boolean containsTarget(long accountId,String type,UUID employee,String unit,Instant now,long deadline) {
        boolean finance=List.of("REIMBURSEMENTS","PAYSLIP_STATUS","AUDIT","REQUEST_STATUS").contains(type);
        boolean manager=!List.of("PAYSLIP_STATUS","AUDIT").contains(type);
        ReportFilter filter=new ReportFilter(type,null,null,null,employee,unit,null,null,null,null,null,null,null,
            accountId,now,1,deadline);
        String sql="SELECT e.public_id FROM employees e"+ReportSql.CURRENT_ASSIGNMENT+" WHERE "
            +"("+ReportSql.employeeScope(!"REIMBURSEMENTS".equals(type),manager,finance,!"AUDIT".equals(type))
            +reviewerScope(type)
            +("AUDIT".equals(type)?" OR "+ReportSql.operationsScope():"")+")"+ReportSql.EMPLOYEE_FILTER+" LIMIT 1";
        return !ReportSql.query(source,sql,filter,List.of("public_id")).isEmpty();
    }

    private static String reviewerScope(String type) {
        if (!List.of("REQUEST_STATUS","REIMBURSEMENTS").contains(type)) return "";
        String reimbursement="EXISTS(SELECT 1 FROM reimbursement_claim rc JOIN reimbursement_review_assignment ra ON ra.claim_id=rc.id AND ra.revision=rc.revision "
            +"JOIN user_accounts reviewer ON reviewer.public_id=ra.reviewer_account_public_id "
            +"JOIN account_bindings rb ON rb.account_id=reviewer.id AND rb.employee_public_id=ra.reviewer_employee_public_id AND rb.revoked_at IS NULL "
            +"WHERE reviewer.id=:account AND rc.employee_id=e.id AND rc.status='PENDING' AND ra.step=1 AND ra.active "
            +"AND NOT EXISTS(SELECT 1 FROM reimbursement_decision done WHERE done.claim_id=rc.id AND done.revision=rc.revision AND done.step=1))";
        if ("REIMBURSEMENTS".equals(type)) return " OR ("+ReportSql.role("MANAGER")+" AND "+reimbursement+")";
        String attendance="EXISTS(SELECT 1 FROM attendance_correction ac JOIN attendance_correction_assignment ca ON ca.revision_id=ac.active_revision_id "
            +"JOIN account_bindings rb ON rb.account_id=ca.reviewer_id AND rb.revoked_at IS NULL "
            +"WHERE ac.employee_id=e.id AND ac.status='PENDING' AND ca.reviewer_id=:account "
            +"AND ca.id=(SELECT MAX(latest.id) FROM attendance_correction_assignment latest WHERE latest.revision_id=ac.active_revision_id))";
        String leave="EXISTS(SELECT 1 FROM leave_request lr JOIN leave_review_assignment ra ON ra.request_id=lr.id AND ra.revision=lr.revision "
            +"JOIN user_accounts reviewer ON reviewer.public_id=ra.reviewer_account_public_id "
            +"JOIN account_bindings rb ON rb.account_id=reviewer.id AND rb.employee_public_id=ra.reviewer_employee_public_id AND rb.revoked_at IS NULL "
            +"WHERE reviewer.id=:account AND lr.employee_id=e.id AND lr.status='PENDING' AND ra.step=1 AND ra.active "
            +"AND NOT EXISTS(SELECT 1 FROM leave_decision done WHERE done.request_id=lr.id AND done.revision=lr.revision AND done.step=1))";
        return " OR ("+ReportSql.role("MANAGER")+" AND ("+reimbursement+" OR "+attendance+" OR "+leave+"))";
    }
}
