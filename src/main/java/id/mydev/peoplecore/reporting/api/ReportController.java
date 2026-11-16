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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reports")
@ConditionalOnProperty(name="peoplecore.reports.enabled",havingValue="true")
public class ReportController {
    private static final Map<String,ReportType> TYPES=Map.of("attendance",ReportType.ATTENDANCE,
        "leave-balances",ReportType.LEAVE_BALANCES,"request-status",ReportType.REQUEST_STATUS,
        "reimbursements",ReportType.REIMBURSEMENTS,"payslip-status",ReportType.PAYSLIP_STATUS);
    private final ScopedReportService reports;
    private final ReportDtoMapper mapper;
    public ReportController(ScopedReportService reports,ReportDtoMapper mapper) { this.reports=reports;this.mapper=mapper; }

    @GetMapping("/{type}")
    public ResponseEntity<ApiData<List<Map<String,String>>>> get(@PathVariable String type,
        @ModelAttribute ReportParameters parameters,Authentication caller) {
        ReportType reportType=TYPES.get(type);
        if(reportType==null) throw new IllegalArgumentException("Unknown report type");
        var snapshot=reports.snapshot(caller,mapper.request(reportType,parameters),ScopedReportService.INTERACTIVE_MAX_ROWS);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(mapper.page(snapshot,parameters));
    }
}
