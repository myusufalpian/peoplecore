package id.mydev.peoplecore.reportexport.api;

import id.mydev.peoplecore.common.api.GlobalExceptionHandler;
import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.reportexport.application.ReportExportCommands;
import id.mydev.peoplecore.reportexport.application.ReportExportCommands.Request;
import id.mydev.peoplecore.reportexport.application.ReportExportService;
import id.mydev.peoplecore.support.JwtAuthFixture;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.web.server.ResponseStatusException;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReportExportHttpTest {
    private static final String BODY="""
        {"format":"CSV","report":{"reportType":"ATTENDANCE","reportVersion":1,"from":"2026-11-01","to":"2026-11-14"}}
        """;
    private final ReportExportCommands commands=mock(ReportExportCommands.class);
    private final ReportExportService exports=mock(ReportExportService.class);
    private final Authentication caller=JwtAuthFixture.token("https://identity.example","requester");
    private MockMvc http;

    @BeforeEach
    void prepare() {
        http=MockMvcBuilders.standaloneSetup(new ReportExportController(commands,exports))
            .setControllerAdvice(new ReportExportExceptionHandler(),new GlobalExceptionHandler()).build();
    }

    @Test
    void unavailableExportAndQuotaUse503And409WithSafeErrorsAlongsideGlobalAdvice() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"provider credentials=secret"))
            .when(commands).create(any(),anyString(),any(Request.class));
        http.perform(post("/api/v1/report-exports").principal(caller).header("Idempotency-Key","key")
            .contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After","1"))
            .andExpect(jsonPath("$.key").value("EXPORT_UNAVAILABLE"))
            .andExpect(content().string(not(containsString("secret"))));
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT,"quota reached"))
            .when(commands).create(any(),anyString(),any(Request.class));
        http.perform(post("/api/v1/report-exports").principal(caller).header("Idempotency-Key","key")
            .contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.key").value("EXPORT_QUOTA_EXCEEDED"));
    }

    @Test
    void invalidRequestAndMissingContentRetain400And404InsteadOfGeneric500() throws Exception {
        doThrow(new IllegalArgumentException("invalid filter" )).when(commands).create(any(),anyString(),any(Request.class));
        http.perform(post("/api/v1/report-exports").principal(caller).header("Idempotency-Key","key")
            .contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.key").value("INVALID_EXPORT_REQUEST"));
        UUID id=UUID.randomUUID();
        doThrow(new ResourceNotFoundException()).when(exports).requireDownload(id,caller);
        http.perform(get("/api/v1/report-exports/"+id+"/content").principal(caller))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.key").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void authorizationTransactionTimeoutHasARetryableResponse() throws Exception {
        doThrow(new TransactionTimedOutException("private diagnostic" )).when(commands).create(any(),anyString(),any(Request.class));
        http.perform(post("/api/v1/report-exports").principal(caller).header("Idempotency-Key","key")
            .contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.key").value("EXPORT_UNAVAILABLE"));
    }
}
