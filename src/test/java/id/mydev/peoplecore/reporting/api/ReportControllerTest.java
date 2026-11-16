package id.mydev.peoplecore.reporting.api;

import id.mydev.peoplecore.common.reporting.ReportLimitException;
import id.mydev.peoplecore.reporting.application.ReportSnapshot;
import id.mydev.peoplecore.reporting.application.ReportType;
import id.mydev.peoplecore.reporting.application.ScopedReportService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReportControllerTest {
    private final ScopedReportService reports=mock(ScopedReportService.class);
    private MockMvc http;

    @BeforeEach
    void prepare() {
        http=MockMvcBuilders.standaloneSetup(new ReportController(reports,new ReportDtoMapper()),
            new AuditSearchController(reports,new ReportDtoMapper())).setControllerAdvice(new ReportExceptionHandler()).build();
    }

    @Test
    void reportResponseIsScopedTabularDataWithAsOfMetadataAndNoCache() throws Exception {
        var snapshot=new ReportSnapshot(ReportType.ATTENDANCE,1,"filter",List.of("employeeId","employeeName"),
            List.of(List.of("employee","")),Instant.parse("2026-11-13T00:00:00Z"),3,null);
        when(reports.snapshot(any(),any(),eq(10_000))).thenReturn(snapshot);
        http.perform(get("/api/v1/reports/attendance").param("from","2026-11-01").param("to","2026-12-01"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.data[0].employeeId").value("employee"))
            .andExpect(jsonPath("$.metadata.reportVersion").value(1))
            .andExpect(jsonPath("$.metadata.unavailableColumns[0]").value("employeeName"));
    }

    @Test
    void rowOverflowAndInvalidPageSizeHaveActionableHttpErrors() throws Exception {
        when(reports.snapshot(any(),any(),eq(10_000))).thenThrow(new ReportLimitException("Narrow the selected filters"));
        http.perform(get("/api/v1/reports/attendance").param("from","2026-11-01").param("to","2026-12-01"))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.key").value("REPORT_LIMIT_EXCEEDED"));
        http.perform(get("/api/v1/reports/attendance").param("size","101"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.key").value("INVALID_REPORT_FILTER"));
    }
    @Test
    void auditSearchUsesTheSameSafeEnvelopeAndUnknownCatalogPathsFailValidation() throws Exception {
        var snapshot=new ReportSnapshot(ReportType.AUDIT,1,"audit",List.of("eventId"),List.of(List.of("event")),
            Instant.parse("2026-11-13T00:00:00Z"),3,null);
        when(reports.snapshot(any(),any(),eq(10_000))).thenReturn(snapshot);
        http.perform(get("/api/v1/audit-events").param("from","2026-11-01").param("to","2026-12-01"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].eventId").value("event"));
        http.perform(get("/api/v1/reports/arbitrary-sql"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.key").value("INVALID_REPORT_FILTER"));
    }

    @Test
    void transactionDeadlineHasTheSameExplicitNarrowingResponseAsRowOverflow() throws Exception {
        when(reports.snapshot(any(),any(),eq(10_000))).thenThrow(new TransactionTimedOutException("timeout"));
        http.perform(get("/api/v1/reports/attendance").param("from","2026-11-01").param("to","2026-12-01"))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.key").value("REPORT_LIMIT_EXCEEDED"));
    }

}
