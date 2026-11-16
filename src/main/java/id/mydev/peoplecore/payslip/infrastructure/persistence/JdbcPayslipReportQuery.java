package id.mydev.peoplecore.payslip.infrastructure.persistence;

import id.mydev.peoplecore.common.infrastructure.persistence.ReportSql;
import id.mydev.peoplecore.common.reporting.ReportFilter;
import id.mydev.peoplecore.common.reporting.ReportTable;
import id.mydev.peoplecore.common.reporting.ScopedReportQuery;
import java.util.Arrays;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPayslipReportQuery implements ScopedReportQuery {
    private final DataSource source;
    public JdbcPayslipReportQuery(DataSource source) { this.source=source; }
    @Override
    public boolean supports(String type,String workflow) { return "PAYSLIP_STATUS".equals(type); }
    @Override
    public ReportTable query(ReportFilter f) {
        String status="CASE WHEN p.id IS NULL THEN r.status WHEN current_publication.publication_id=p.id THEN 'PUBLISHED' ELSE 'REPLACED' END";
        String sql="SELECT "+ReportSql.EMPLOYEE_COLUMNS+",i.period_start,p.public_id publication_id,r.revision_number,"+status+" status,p.published_at "
            +"FROM payslip_batch_item i JOIN payslip_batch_revision r ON r.id=i.revision_id JOIN payslip_batch b ON b.id=r.batch_id "
            +"JOIN employees e ON e.id=i.employee_id "+ReportSql.CURRENT_ASSIGNMENT
            +"LEFT JOIN payslip_publication p ON p.item_id=i.id LEFT JOIN payslip_current current_publication ON current_publication.employee_id=i.employee_id AND current_publication.period_start=i.period_start WHERE i.period_start=:fromDate AND ("
            +ReportSql.employeeScope(true,false,true,false)+" OR ("+ReportSql.employeeScope(false,false,false,true)
            +" AND p.id IS NOT NULL AND b.status='PUBLISHED'))"+ReportSql.EMPLOYEE_FILTER
            +" AND (:status IS NULL OR "+status+"=:status) ORDER BY i.period_start,e.public_id,r.revision_number LIMIT :limit";
        return table(sql,f,"employeeId,employeeNumber,employeeName,period,publicationId,version,status,publishedAt",
            "employee_id,employee_number,employee_name,period_start,publication_id,revision_number,status,published_at");
    }

    private ReportTable table(String sql,ReportFilter filter,String headers,String aliases) {
        return new ReportTable(Arrays.asList(headers.split(",")), ReportSql.query(source,sql,filter,Arrays.asList(aliases.split(","))));
    }
}
