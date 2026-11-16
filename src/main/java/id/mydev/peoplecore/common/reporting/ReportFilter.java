package id.mydev.peoplecore.common.reporting;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ReportFilter(String reportType, LocalDate from, LocalDate to, Integer year,
                           UUID employeeId, String unit, String status, String workflowType,
                           UUID resourceId, String actorId, String action, String correlationId,
                           String category, long accountId, Instant now, int rowLimit, long deadlineNanos) { }
