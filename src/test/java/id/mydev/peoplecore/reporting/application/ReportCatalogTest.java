package id.mydev.peoplecore.reporting.application;

import id.mydev.peoplecore.attendance.infrastructure.persistence.JdbcAttendanceReportQuery;
import id.mydev.peoplecore.common.audit.infrastructure.persistence.JdbcAuditReportQuery;
import id.mydev.peoplecore.common.reporting.ScopedReportQuery;
import id.mydev.peoplecore.leave.infrastructure.persistence.JdbcLeaveReportQuery;
import id.mydev.peoplecore.organization.infrastructure.persistence.JdbcProfileReportQuery;
import id.mydev.peoplecore.payslip.infrastructure.persistence.JdbcPayslipReportQuery;
import id.mydev.peoplecore.reimbursement.infrastructure.persistence.JdbcReimbursementReportQuery;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class ReportCatalogTest {
    private final DataSource source=mock(DataSource.class);
    private final Map<String,ScopedReportQuery> queries=Map.of(
        "attendance",new JdbcAttendanceReportQuery(source),"leave",new JdbcLeaveReportQuery(source),
        "profile",new JdbcProfileReportQuery(source),"reimbursement",new JdbcReimbursementReportQuery(source),
        "payslip",new JdbcPayslipReportQuery(source),"audit",new JdbcAuditReportQuery(source));

    @Test
    void catalogRoutesOnlyToTheOwningDomains() {
        assertRoutes("ATTENDANCE",null,List.of("attendance"));
        assertRoutes("LEAVE_BALANCES",null,List.of("leave"));
        assertRoutes("REIMBURSEMENTS",null,List.of("reimbursement"));
        assertRoutes("PAYSLIP_STATUS",null,List.of("payslip"));
        assertRoutes("AUDIT",null,List.of("audit"));
        assertRoutes("UNKNOWN",null,List.of());
    }

    @Test
    void requestStatusCombinesExplicitDomainsWithoutCrossWorkflowLeakage() {
        assertRoutes("REQUEST_STATUS",null,List.of("attendance","leave","profile","reimbursement"));
        assertRoutes("REQUEST_STATUS","PROFILE",List.of("profile"));
        assertRoutes("REQUEST_STATUS","ATTENDANCE",List.of("attendance"));
        assertRoutes("REQUEST_STATUS","LEAVE",List.of("leave"));
        assertRoutes("REQUEST_STATUS","LEAVE_CANCELLATION",List.of("leave"));
        assertRoutes("REQUEST_STATUS","REIMBURSEMENT",List.of("reimbursement"));
        assertRoutes("REQUEST_STATUS","UNKNOWN",List.of());
    }

    private void assertRoutes(String type,String workflow,List<String> expected) {
        List<String> actual=queries.entrySet().stream().filter(entry->entry.getValue().supports(type,workflow))
            .map(Map.Entry::getKey).sorted().toList();
        assertEquals(expected,actual);
    }
}
