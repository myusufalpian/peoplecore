package id.mydev.peoplecore.infrastructure.recovery;

import id.mydev.peoplecore.common.api.ApiData;
import id.mydev.peoplecore.common.security.CurrentCaller;
import id.mydev.peoplecore.infrastructure.recovery.ServiceRecoveryMapper.OperationalHealthSummary;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/operations")
public class OperationsObservabilityController {
    private final ServiceRecoveryService service;

    public OperationsObservabilityController(ServiceRecoveryService service) {
        this.service = service;
    }

    @GetMapping("/health-summary")
    public ResponseEntity<ApiData<OperationalHealthSummary>> healthSummary() {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(ApiData.of(service.healthSummary(CurrentCaller.require())));
    }
}
