package id.mydev.peoplecore.common.reporting;

public interface ScopedReportQuery {
    boolean supports(String reportType, String workflowType);
    ReportTable query(ReportFilter filter);
}
