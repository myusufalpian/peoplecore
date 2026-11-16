package id.mydev.peoplecore.reportexport.api;

import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.reportexport.application.ReportExportCommands;
import id.mydev.peoplecore.reportexport.application.ReportExportCommands.Request;
import id.mydev.peoplecore.reportexport.application.ReportExportService;
import id.mydev.peoplecore.reportexport.application.ReportExportService.Summary;
import id.mydev.peoplecore.reporting.application.ReportRequest;
import id.mydev.peoplecore.reporting.application.ReportType;
import id.mydev.peoplecore.support.JwtAuthFixture;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportExportControllerTest {
    private static final Instant NOW=Instant.parse("2026-11-13T10:00:00Z");
    private final ReportExportCommands commands=mock(ReportExportCommands.class);
    private final ReportExportService exports=mock(ReportExportService.class);
    private final ReportExportController controller=new ReportExportController(commands,exports);
    private final Authentication caller=JwtAuthFixture.token("https://identity.example","requester");

    @Test
    void requestReturnsAnAcceptedJobLocationAndMetadataIsNotCacheable() {
        UUID id=UUID.randomUUID();
        Summary summary=new Summary(id,"PENDING",NOW,NOW.plusSeconds(86400),null,null);
        Request request=new Request(ReportRequest.of(ReportType.ATTENDANCE,LocalDate.of(2026,11,1),LocalDate.of(2026,11,14)),"CSV");
        when(commands.create(caller,"key",request)).thenReturn(summary);
        var accepted=controller.create(caller,"key",request);
        assertEquals(202,accepted.getStatusCode().value());
        assertEquals("/api/v1/report-exports/"+id,accepted.getHeaders().getLocation().toString());
        assertEquals("no-store",accepted.getHeaders().getCacheControl());
        when(exports.detail(id,caller)).thenReturn(summary);
        var detail=controller.detail(caller,id);
        assertEquals(summary,detail.getBody());
        assertEquals("no-store",detail.getHeaders().getCacheControl());
    }

    @Test
    void csvContentRequiresAuthorizationBeforeHeadersAndStreamsWithTheOriginalCaller() throws Exception {
        UUID id=UUID.randomUUID();
        doThrow(new ResourceNotFoundException()).when(exports).requireDownload(id,caller);
        assertThrows(ResourceNotFoundException.class,()->controller.content(caller,id));
        UUID allowed=UUID.randomUUID();
        var response=controller.content(caller,allowed);
        assertEquals("no-store",response.getHeaders().getCacheControl());
        assertEquals("nosniff",response.getHeaders().getFirst("X-Content-Type-Options"));
        assertTrue(response.getHeaders().getFirst("Content-Disposition").contains(allowed.toString()));
        doAnswer(invocation->{
            ByteArrayOutputStream target=invocation.getArgument(2);
            target.write("csv".getBytes(StandardCharsets.UTF_8));
            return null;
        }).when(exports).download(eq(allowed),eq(caller),any());
        ByteArrayOutputStream target=new ByteArrayOutputStream();
        response.getBody().writeTo(target);
        assertEquals("csv",target.toString(StandardCharsets.UTF_8));
        verify(exports).requireDownload(allowed,caller);
    }
}
