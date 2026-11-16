package id.mydev.peoplecore.reporting.api;

import id.mydev.peoplecore.common.api.ApiData;
import id.mydev.peoplecore.reporting.application.ReportType;
import id.mydev.peoplecore.reporting.application.ScopedReportService;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name="peoplecore.reports.enabled",havingValue="true")
public class AuditSearchController {
    private final ScopedReportService reports;
    private final ReportDtoMapper mapper;
    public AuditSearchController(ScopedReportService reports,ReportDtoMapper mapper) { this.reports=reports;this.mapper=mapper; }

    @GetMapping("/api/v1/audit-events")
    public ResponseEntity<ApiData<List<Map<String,String>>>> get(@ModelAttribute ReportParameters parameters,Authentication caller) {
        var snapshot=reports.snapshot(caller,mapper.request(ReportType.AUDIT,parameters),ScopedReportService.INTERACTIVE_MAX_ROWS);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(mapper.page(snapshot,parameters));
    }
}
