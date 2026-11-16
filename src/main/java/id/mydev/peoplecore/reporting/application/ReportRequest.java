package id.mydev.peoplecore.reporting.application;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

public record ReportRequest(ReportType reportType, int reportVersion, LocalDate from, LocalDate to,
                            Integer year, YearMonth month, UUID employeeId, String unit, String status,
                            String workflowType, UUID resourceId, String actorId, String action,
                            String correlationId, String category) {
    public static ReportRequest of(ReportType type, LocalDate from, LocalDate to) {
        return new ReportRequest(type, 1, from, to, null, null, null, null, null, null,
            null, null, null, null, null);
    }
}
