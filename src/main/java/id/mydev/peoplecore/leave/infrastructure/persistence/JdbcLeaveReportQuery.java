package id.mydev.peoplecore.leave.infrastructure.persistence;

import id.mydev.peoplecore.common.infrastructure.persistence.ReportSql;
import id.mydev.peoplecore.common.reporting.ReportFilter;
import id.mydev.peoplecore.common.reporting.ReportTable;
import id.mydev.peoplecore.common.reporting.ScopedReportQuery;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcLeaveReportQuery implements ScopedReportQuery {
    private final DataSource source;
    public JdbcLeaveReportQuery(DataSource source) { this.source=source; }
    @Override
    public boolean supports(String type,String workflow) { return "LEAVE_BALANCES".equals(type) || ("REQUEST_STATUS".equals(type) && (workflow==null || "LEAVE".equals(workflow) || "LEAVE_CANCELLATION".equals(workflow))); }
    @Override
    public ReportTable query(ReportFilter f) {
        if ("LEAVE_BALANCES".equals(f.reportType())) return balances(f);
        if ("LEAVE_CANCELLATION".equals(f.workflowType())) return cancellations(f);
        ReportTable requests=requests(f);
        if (f.workflowType()!=null) return requests;
        List<List<String>> rows=new ArrayList<>(requests.rows());
        rows.addAll(cancellations(f).rows());
        return new ReportTable(requests.columns(),rows);
    }

    private ReportTable balances(ReportFilter f) {
        String sql="SELECT "+ReportSql.EMPLOYEE_COLUMNS+", p.period_year, "
            +"COALESCE(l.opening,0) opening, COALESCE(l.adjustment,0) adjustment, COALESCE(r.reserved,0) reserved, COALESCE(l.consumed,0) consumed, "
            +"COALESCE(l.opening,0)+COALESCE(l.adjustment,0)-COALESCE(l.consumed,0)-COALESCE(r.reserved,0) available "
            +"FROM leave_period p JOIN employees e ON e.id=p.employee_id "+ReportSql.CURRENT_ASSIGNMENT
            +"LEFT JOIN (SELECT period_id,SUM(CASE WHEN entry_type='OPENING' THEN half_units ELSE 0 END) opening, "
            +"SUM(CASE WHEN entry_type='ADJUSTMENT' THEN half_units ELSE 0 END) adjustment, "
            +"SUM(CASE WHEN entry_type='CONSUMPTION' THEN half_units WHEN entry_type='REVERSAL' THEN -half_units ELSE 0 END) consumed "
            +"FROM leave_ledger GROUP BY period_id) l ON l.period_id=p.id "
            +"LEFT JOIN (SELECT period_id,SUM(half_units) reserved FROM leave_reservation WHERE active GROUP BY period_id) r ON r.period_id=p.id "
            +"WHERE p.period_year=:year AND "+ReportSql.employeeScope(true,true,false,true)+ReportSql.EMPLOYEE_FILTER
            +" ORDER BY e.public_id LIMIT :limit";
        return table(sql,f,"employeeId,employeeNumber,employeeName,year,openingHalfUnits,adjustmentHalfUnits,reservedHalfUnits,consumedHalfUnits,availableHalfUnits",
            "employee_id,employee_number,employee_name,period_year,opening,adjustment,reserved,consumed,available");
    }

    private ReportTable requests(ReportFilter f) {
        String sql="SELECT q.public_id request_id, "+ReportSql.EMPLOYEE_COLUMNS+", 'LEAVE' workflow, "
            +"COALESCE(v.submitted_at,q.created_at) submitted_at, COALESCE((SELECT MAX(h.created_at) FROM leave_history h WHERE h.request_id=q.id),q.created_at) updated_at, q.status, "
            +"CASE WHEN q.status='PENDING' THEN CASE WHEN EXISTS(SELECT 1 FROM leave_decision d WHERE d.request_id=q.id AND d.revision=q.revision AND d.step=1 AND d.outcome='APPROVE') THEN '2' ELSE '1' END ELSE '' END review_step, "
            +"(q.status='PENDING' AND NOT EXISTS(SELECT 1 FROM leave_review_assignment ra WHERE ra.request_id=q.id AND ra.revision=q.revision AND ra.active "
            +"AND ra.step=(CASE WHEN EXISTS(SELECT 1 FROM leave_decision completed WHERE completed.request_id=q.id AND completed.revision=q.revision AND completed.step=1 AND completed.outcome='APPROVE') THEN 2 ELSE 1 END) AND ("
            +"(ra.step=1 AND NOT ra.fallback AND "+ReportSql.eligibleReviewerByPublicId("ra.reviewer_account_public_id","ra.reviewer_employee_public_id","MANAGER")+") OR "
            +"((ra.step=2 OR ra.fallback) AND "+ReportSql.eligibleReviewerByPublicId("ra.reviewer_account_public_id","ra.reviewer_employee_public_id","HR_ADMIN")+")))) blocked "
            +"FROM leave_request q JOIN employees e ON e.id=q.employee_id "+ReportSql.CURRENT_ASSIGNMENT
            +"LEFT JOIN leave_revision v ON v.request_id=q.id AND v.revision=q.revision "
            +"WHERE COALESCE(v.submitted_at,q.created_at)>=:fromTime AND COALESCE(v.submitted_at,q.created_at)<:toTime AND "
            +"("+ReportSql.employeeScope(true,true,false,true)+" OR ("+ReportSql.role("MANAGER")
            +" AND q.status='PENDING' AND EXISTS(SELECT 1 FROM leave_review_assignment ra JOIN user_accounts reviewer ON reviewer.public_id=ra.reviewer_account_public_id "
            +"JOIN account_bindings rb ON rb.account_id=reviewer.id AND rb.employee_public_id=ra.reviewer_employee_public_id AND rb.revoked_at IS NULL "
            +"WHERE reviewer.id=:account AND ra.request_id=q.id AND ra.revision=q.revision AND ra.step=1 AND ra.active "
            +"AND NOT EXISTS(SELECT 1 FROM leave_decision done WHERE done.request_id=q.id AND done.revision=q.revision AND done.step=1))))"
            +ReportSql.EMPLOYEE_FILTER
            +" AND (:status IS NULL OR q.status=:status) ORDER BY submitted_at,q.public_id LIMIT :limit";
        return table(sql,f,ReportSql.REQUEST_COLUMNS,"request_id,employee_id,employee_number,employee_name,workflow,submitted_at,updated_at,status,review_step,blocked");
    }

    private ReportTable cancellations(ReportFilter f) {
        String sql="SELECT c.public_id request_id, "+ReportSql.EMPLOYEE_COLUMNS+", 'LEAVE_CANCELLATION' workflow, c.created_at submitted_at, "
            +"COALESCE((SELECT MAX(d.created_at) FROM leave_cancellation_decision d WHERE d.cancellation_id=c.id),c.created_at) updated_at, c.status, "
            +"CASE WHEN c.status='PENDING' THEN '1' ELSE '' END review_step, "
            +"(c.status='PENDING' AND NOT EXISTS(SELECT 1 FROM leave_cancellation_assignment ca WHERE ca.cancellation_id=c.id AND ca.active AND "
            +ReportSql.eligibleReviewerByPublicId("ca.reviewer_account_public_id","ca.reviewer_employee_public_id","HR_ADMIN")+")) blocked "
            +"FROM leave_cancellation c JOIN leave_request q ON q.id=c.request_id JOIN employees e ON e.id=q.employee_id "+ReportSql.CURRENT_ASSIGNMENT
            +"WHERE c.created_at>=:fromTime AND c.created_at<:toTime AND "+ReportSql.employeeScope(true,true,false,true)+ReportSql.EMPLOYEE_FILTER
            +" AND (:status IS NULL OR c.status=:status) ORDER BY submitted_at,c.public_id LIMIT :limit";
        return table(sql,f,ReportSql.REQUEST_COLUMNS,"request_id,employee_id,employee_number,employee_name,workflow,submitted_at,updated_at,status,review_step,blocked");
    }

    private ReportTable table(String sql,ReportFilter filter,String headers,String aliases) {
        return new ReportTable(Arrays.asList(headers.split(",")), ReportSql.query(source,sql,filter,Arrays.asList(aliases.split(","))));
    }
}
