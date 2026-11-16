package id.mydev.peoplecore.reportexport.api;

import id.mydev.peoplecore.reportexport.application.ReportExportCommands;
import id.mydev.peoplecore.reportexport.application.ReportExportCommands.Request;
import id.mydev.peoplecore.reportexport.application.ReportExportService;
import id.mydev.peoplecore.reportexport.application.ReportExportService.Summary;
import java.net.URI;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/v1/report-exports")
@ConditionalOnProperty(name="peoplecore.exports.enabled",havingValue="true")
public class ReportExportController {
    private final ReportExportCommands commands;
    private final ReportExportService exports;

    public ReportExportController(ReportExportCommands commands,ReportExportService exports) {
        this.commands=commands; this.exports=exports;
    }

    @PostMapping
    public ResponseEntity<Summary> create(Authentication caller,@RequestHeader("Idempotency-Key") String key,@RequestBody Request request) {
        Summary result=commands.create(caller,key,request);
        return ResponseEntity.accepted().location(URI.create("/api/v1/report-exports/"+result.publicId()))
            .cacheControl(CacheControl.noStore()).body(result);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Summary> detail(Authentication caller,@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(exports.detail(id,caller));
    }

    @GetMapping("/{id}/content")
    public ResponseEntity<StreamingResponseBody> content(Authentication caller,@PathVariable UUID id) {
        exports.requireDownload(id,caller);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"report-"+id+".csv\"")
            .header("X-Content-Type-Options","nosniff").contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
            .body(output -> exports.download(id,caller,output));
    }
}
