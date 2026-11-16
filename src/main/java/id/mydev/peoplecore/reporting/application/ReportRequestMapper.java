package id.mydev.peoplecore.reporting.application;

import id.mydev.peoplecore.common.reporting.ReportFilter;
import java.time.Instant;
import java.time.LocalDate;

public final class ReportRequestMapper {
    private ReportRequestMapper() { }
    public static ReportFilter filter(ReportRequest request,long accountId,Instant at,int maxRows,long deadline) {
        LocalDate from=request.month()==null?request.from():request.month().atDay(1);
        LocalDate to=request.month()==null?request.to():request.month().plusMonths(1).atDay(1);
        return new ReportFilter(request.reportType().name(),from,to,request.year(),request.employeeId(),request.unit(),
            request.status(),request.workflowType(),request.resourceId(),request.actorId(),request.action(),request.correlationId(),
            request.category(),accountId,at,maxRows,deadline);
    }
}
