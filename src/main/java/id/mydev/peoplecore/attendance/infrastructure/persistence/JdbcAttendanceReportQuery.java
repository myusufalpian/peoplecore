package id.mydev.peoplecore.attendance.infrastructure.persistence;

import id.mydev.peoplecore.common.infrastructure.persistence.ReportSql;
import id.mydev.peoplecore.common.reporting.ReportFilter;
import id.mydev.peoplecore.common.reporting.ReportTable;
import id.mydev.peoplecore.common.reporting.ScopedReportQuery;
import java.util.Arrays;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAttendanceReportQuery implements ScopedReportQuery {
    private final DataSource source;
    public JdbcAttendanceReportQuery(DataSource source) { this.source=source; }
    @Override
    public boolean supports(String type,String workflow) { return "ATTENDANCE".equals(type) || ("REQUEST_STATUS".equals(type) && (workflow==null || "ATTENDANCE".equals(workflow))); }
    @Override
    public ReportTable query(ReportFilter f) {
        if ("REQUEST_STATUS".equals(f.reportType())) return requests(f);
        String sql="SELECT "+ReportSql.EMPLOYEE_COLUMNS+", o.work_date, v.in_at, v.out_at, "
            +"COALESCE(c.status,'NONE') correction_status, CASE WHEN v.in_at IS NULL THEN 'NOT_RECORDED' WHEN v.out_at IS NULL THEN 'IN_PROGRESS' ELSE 'COMPLETE' END recording_status, COALESCE(v.created_at,o.created_at) updated_at "
            +"FROM attendance_occurrence o JOIN employees e ON e.id=o.employee_id "+ReportSql.CURRENT_ASSIGNMENT
            +"LEFT JOIN attendance_effective_revision v ON v.id=(SELECT MAX(ev.id) FROM attendance_effective_revision ev WHERE ev.occurrence_id=o.id) "
            +"LEFT JOIN attendance_correction c ON c.id=(SELECT MAX(ac.id) FROM attendance_correction ac WHERE ac.employee_id=e.id AND ac.work_date=o.work_date) "
            +"WHERE o.work_date>=:fromDate AND o.work_date<:toDate AND "+ReportSql.employeeScope(true,true,false,true)
            +ReportSql.EMPLOYEE_FILTER+" AND (:status IS NULL OR COALESCE(c.status,'NONE')=:status OR (CASE WHEN v.in_at IS NULL THEN 'NOT_RECORDED' WHEN v.out_at IS NULL THEN 'IN_PROGRESS' ELSE 'COMPLETE' END)=:status) ORDER BY o.work_date,e.public_id LIMIT :limit";
        return table(sql,f,"employeeId,employeeNumber,employeeName,workday,clockIn,clockOut,correctionStatus,recordingStatus,updatedAt",
            "employee_id,employee_number,employee_name,work_date,in_at,out_at,correction_status,recording_status,updated_at");
    }

    private ReportTable requests(ReportFilter f) {
        String sql="SELECT c.public_id request_id, "+ReportSql.EMPLOYEE_COLUMNS+", 'ATTENDANCE' workflow, "
            +"COALESCE(v.created_at,c.created_at) submitted_at, COALESCE((SELECT MAX(d.created_at) FROM attendance_correction_decision d WHERE d.revision_id=c.active_revision_id),v.created_at,c.created_at) updated_at, "
            +"c.status, CASE WHEN c.status='PENDING' THEN '1' ELSE '' END review_step, "
            +"(c.status='PENDING' AND NOT EXISTS (SELECT 1 FROM attendance_correction_assignment current_assignment WHERE current_assignment.revision_id=c.active_revision_id "
            +"AND current_assignment.id=(SELECT MAX(last_assignment.id) FROM attendance_correction_assignment last_assignment WHERE last_assignment.revision_id=c.active_revision_id) "
            +"AND ("+ReportSql.eligibleReviewerById("current_assignment.reviewer_id","MANAGER")+" OR "+ReportSql.eligibleReviewerById("current_assignment.reviewer_id","HR_ADMIN")+"))) blocked "
            +"FROM attendance_correction c JOIN employees e ON e.id=c.employee_id "+ReportSql.CURRENT_ASSIGNMENT
            +"LEFT JOIN attendance_correction_revision v ON v.public_id=c.active_revision_id "
            +"WHERE COALESCE(v.created_at,c.created_at)>=:fromTime AND COALESCE(v.created_at,c.created_at)<:toTime AND "
            +"("+ReportSql.employeeScope(true,true,false,true)+" OR ("+ReportSql.role("MANAGER")
            +" AND c.status='PENDING' AND EXISTS(SELECT 1 FROM attendance_correction_assignment ca JOIN account_bindings rb ON rb.account_id=ca.reviewer_id "
            +"AND rb.revoked_at IS NULL WHERE ca.revision_id=c.active_revision_id AND ca.reviewer_id=:account "
            +"AND ca.id=(SELECT MAX(latest.id) FROM attendance_correction_assignment latest WHERE latest.revision_id=c.active_revision_id))))"
            +ReportSql.EMPLOYEE_FILTER
            +" AND (:status IS NULL OR c.status=:status) ORDER BY submitted_at,c.public_id LIMIT :limit";
        return table(sql,f,ReportSql.REQUEST_COLUMNS,"request_id,employee_id,employee_number,employee_name,workflow,submitted_at,updated_at,status,review_step,blocked");
    }

    private ReportTable table(String sql,ReportFilter filter,String headers,String aliases) {
        return new ReportTable(Arrays.asList(headers.split(",")), ReportSql.query(source,sql,filter,Arrays.asList(aliases.split(","))));
    }
}
