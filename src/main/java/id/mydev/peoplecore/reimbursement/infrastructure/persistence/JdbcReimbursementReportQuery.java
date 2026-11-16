package id.mydev.peoplecore.reimbursement.infrastructure.persistence;

import id.mydev.peoplecore.common.infrastructure.persistence.ReportSql;
import id.mydev.peoplecore.common.reporting.ReportFilter;
import id.mydev.peoplecore.common.reporting.ReportTable;
import id.mydev.peoplecore.common.reporting.ScopedReportQuery;
import java.util.Arrays;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcReimbursementReportQuery implements ScopedReportQuery {
    private final DataSource source;
    public JdbcReimbursementReportQuery(DataSource source) { this.source=source; }
    @Override
    public boolean supports(String type,String workflow) { return "REIMBURSEMENTS".equals(type) || ("REQUEST_STATUS".equals(type) && (workflow==null || "REIMBURSEMENT".equals(workflow))); }
    @Override
    public ReportTable query(ReportFilter f) {
        String updated="COALESCE((SELECT MAX(h.created_at) FROM reimbursement_history h WHERE h.claim_id=c.id),c.created_at)";
        String blocked="(c.status='PENDING' AND EXISTS(SELECT 1 FROM reimbursement_review_assignment ra WHERE ra.claim_id=c.id AND ra.revision=c.revision AND ra.active AND NOT ("
            +"(ra.step=1 AND "+ReportSql.eligibleReviewerByPublicId("ra.reviewer_account_public_id","ra.reviewer_employee_public_id","MANAGER")+") OR "
            +"(ra.step=2 AND "+ReportSql.eligibleReviewerByPublicId("ra.reviewer_account_public_id","ra.reviewer_employee_public_id","FINANCE_PAYROLL")+"))))";
        String step="CASE WHEN c.status='PENDING' THEN CASE WHEN EXISTS(SELECT 1 FROM reimbursement_decision d WHERE d.claim_id=c.id AND d.revision=c.revision AND d.step=1 AND d.outcome='APPROVE') THEN '2' ELSE '1' END ELSE '' END";
        String fields="REQUEST_STATUS".equals(f.reportType())
            ?"c.public_id request_id, "+ReportSql.EMPLOYEE_COLUMNS+", 'REIMBURSEMENT' workflow, COALESCE(revision.submitted_at,c.created_at) submitted_at, "+updated+" updated_at,c.status,"+step+" review_step,"+blocked+" blocked"
            :"c.public_id claim_id, "+ReportSql.EMPLOYEE_COLUMNS+", c.category_code,c.transaction_date,c.amount,c.status,"+updated+" updated_at,CASE WHEN c.status='PAID' THEN 'RECORDED' ELSE 'UNRECORDED' END payment_status";
        String time="REQUEST_STATUS".equals(f.reportType())?"COALESCE(revision.submitted_at,c.created_at)>=:fromTime AND COALESCE(revision.submitted_at,c.created_at)<:toTime":"c.transaction_date>=:fromDate AND c.transaction_date<:toDate";
        String sql="SELECT "+fields+" FROM reimbursement_claim c JOIN employees e ON e.id=c.employee_id LEFT JOIN reimbursement_revision revision ON revision.claim_id=c.id AND revision.revision=c.revision "+ReportSql.CURRENT_ASSIGNMENT
            +" WHERE "+time+" AND ("+ReportSql.employeeScope(false,true,true,true)+" OR ("+ReportSql.role("MANAGER")
            +" AND c.status='PENDING' AND EXISTS(SELECT 1 FROM reimbursement_review_assignment ra JOIN user_accounts reviewer ON reviewer.public_id=ra.reviewer_account_public_id "
            +"JOIN account_bindings rb ON rb.account_id=reviewer.id AND rb.employee_public_id=ra.reviewer_employee_public_id AND rb.revoked_at IS NULL "
            +"WHERE reviewer.id=:account AND ra.claim_id=c.id AND ra.revision=c.revision AND ra.step=1 AND ra.active "
            +"AND NOT EXISTS(SELECT 1 FROM reimbursement_decision done WHERE done.claim_id=c.id AND done.revision=c.revision AND done.step=1))))"
            +ReportSql.EMPLOYEE_FILTER
            +" AND (:status IS NULL OR c.status=:status OR (CASE WHEN c.status='PAID' THEN 'RECORDED' ELSE 'UNRECORDED' END)=:status) AND (:category IS NULL OR c.category_code=:category) ORDER BY c.created_at,c.public_id LIMIT :limit";
        if ("REQUEST_STATUS".equals(f.reportType())) return table(sql,f,ReportSql.REQUEST_COLUMNS,
            "request_id,employee_id,employee_number,employee_name,workflow,submitted_at,updated_at,status,review_step,blocked");
        return table(sql,f,"claimId,employeeId,employeeNumber,employeeName,category,transactionDate,amountIdr,approvalStatus,updatedAt,paymentStatus",
            "claim_id,employee_id,employee_number,employee_name,category_code,transaction_date,amount,status,updated_at,payment_status");
    }

    private ReportTable table(String sql,ReportFilter filter,String headers,String aliases) {
        return new ReportTable(Arrays.asList(headers.split(",")), ReportSql.query(source,sql,filter,Arrays.asList(aliases.split(","))));
    }
}
