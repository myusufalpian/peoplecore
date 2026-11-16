package id.mydev.peoplecore.reporting.api;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

public record ReportParameters(LocalDate from,LocalDate to,Integer year,YearMonth month,UUID employeeId,
                               String unit,String status,String workflowType,UUID resourceId,String actorId,
                               String action,String correlationId,String category,Integer size,String cursor) { }
