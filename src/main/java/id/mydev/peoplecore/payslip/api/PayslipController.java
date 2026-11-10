package id.mydev.peoplecore.payslip.api;

import id.mydev.peoplecore.payslip.application.PayslipModels.Page;
import id.mydev.peoplecore.payslip.application.PayslipService;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "peoplecore.payslip.enabled", havingValue = "true")
@RequestMapping("/api/v1/payslips")
public class PayslipController {
    private final PayslipService service;

    public PayslipController(PayslipService service) {
        this.service = service;
    }

    @GetMapping
    public Page list(Authentication caller, @RequestParam(defaultValue = "50") int limit,
                     @RequestParam(required = false) UUID cursor) {
        return service.published(caller, limit, cursor);
    }
}
