package id.mydev.peoplecore.payslip.api;

import id.mydev.peoplecore.payslip.application.PayslipCommands;
import id.mydev.peoplecore.payslip.application.PayslipModels.Detail;
import id.mydev.peoplecore.payslip.application.PayslipModels.Draft;
import id.mydev.peoplecore.payslip.application.PayslipModels.Result;
import id.mydev.peoplecore.payslip.application.PayslipService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "peoplecore.payslip.enabled", havingValue = "true")
@RequestMapping("/api/v1/payslips/batches")
public class PayslipBatchController {
    private final PayslipCommands commands;
    private final PayslipService service;

    public PayslipBatchController(PayslipCommands commands, PayslipService service) {
        this.commands = commands;
        this.service = service;
    }

    public record EditRequest(@NotNull @Positive Long expectedRevision, @NotNull Draft draft) { }
    public record ActionRequest(@NotNull @Positive Long expectedRevision) { }

    @PostMapping
    public Result create(Authentication caller, @RequestHeader("Idempotency-Key") String key, @RequestBody Draft draft) {
        return commands.create(caller, key, draft);
    }

    @PatchMapping("/{batchId}")
    public Result edit(Authentication caller, @RequestHeader("Idempotency-Key") String key,
                       @PathVariable UUID batchId, @Valid @RequestBody EditRequest request) {
        return commands.edit(caller, key, batchId, request.draft(), request.expectedRevision());
    }

    @PostMapping("/{batchId}/review")
    public Result review(Authentication caller, @RequestHeader("Idempotency-Key") String key,
                         @PathVariable UUID batchId, @Valid @RequestBody ActionRequest request) {
        return commands.review(caller, key, batchId, request.expectedRevision());
    }

    @PostMapping("/{batchId}/publish")
    public Result publish(Authentication caller, @RequestHeader("Idempotency-Key") String key,
                          @PathVariable UUID batchId, @Valid @RequestBody ActionRequest request) {
        return commands.publish(caller, key, batchId, request.expectedRevision());
    }

    @GetMapping("/{batchId}")
    public Detail detail(Authentication caller, @PathVariable UUID batchId) {
        return service.detail(batchId, caller);
    }
}
