package id.mydev.peoplecore.organization.infrastructure.persistence;

import id.mydev.peoplecore.common.infrastructure.persistence.ReportSql;
import id.mydev.peoplecore.common.reporting.ReportFilter;
import id.mydev.peoplecore.common.reporting.ReportTable;
import id.mydev.peoplecore.common.reporting.ScopedReportQuery;
import java.util.Arrays;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcProfileReportQuery implements ScopedReportQuery {
    private final DataSource source;
    public JdbcProfileReportQuery(DataSource source) { this.source=source; }
    @Override
    public boolean supports(String type,String workflow) { return "REQUEST_STATUS".equals(type) && (workflow==null || "PROFILE".equals(workflow)); }
    @Override
    public ReportTable query(ReportFilter f) {
        String sql="SELECT p.public_id request_id, "+ReportSql.EMPLOYEE_COLUMNS+", 'PROFILE' workflow, "
            +"COALESCE(v.evaluated_at,(SELECT MAX(prior.evaluated_at) FROM profile_revision prior WHERE prior.proposal_id=p.id),p.created_at) submitted_at, p.updated_at, p.state status, "
            +"CASE WHEN p.state='PENDING' THEN '1' ELSE '' END review_step, (p.state='PENDING' AND (p.blocked_reason IS NOT NULL OR NOT "
            +ReportSql.eligibleReviewerByPublicId("pa.assignee_account_public_id",null,"HR_ADMIN")+")) blocked "
            +"FROM profile_proposal p JOIN employees e ON e.id=p.employee_id "+ReportSql.CURRENT_ASSIGNMENT
            +"LEFT JOIN profile_revision v ON v.id=p.current_revision_id LEFT JOIN profile_assignment pa ON pa.id=p.current_assignment_id AND pa.status='ACTIVE' WHERE "
            +"COALESCE(v.evaluated_at,(SELECT MAX(prior.evaluated_at) FROM profile_revision prior WHERE prior.proposal_id=p.id),p.created_at)>=:fromTime AND COALESCE(v.evaluated_at,(SELECT MAX(prior.evaluated_at) FROM profile_revision prior WHERE prior.proposal_id=p.id),p.created_at)<:toTime AND "
            +ReportSql.employeeScope(true,true,false,true)+ReportSql.EMPLOYEE_FILTER
            +" AND (:status IS NULL OR p.state=:status) ORDER BY submitted_at,p.public_id LIMIT :limit";
        return table(sql,f,ReportSql.REQUEST_COLUMNS,"request_id,employee_id,employee_number,employee_name,workflow,submitted_at,updated_at,status,review_step,blocked");
    }

    private ReportTable table(String sql,ReportFilter filter,String headers,String aliases) {
        return new ReportTable(Arrays.asList(headers.split(",")), ReportSql.query(source,sql,filter,Arrays.asList(aliases.split(","))));
    }
}
