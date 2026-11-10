package id.mydev.peoplecore.reimbursement.api.controller;

import id.mydev.peoplecore.common.api.ApiData;
import id.mydev.peoplecore.reimbursement.application.command.ReimbursementCommands;
import id.mydev.peoplecore.reimbursement.application.command.ReimbursementModels.Action;
import id.mydev.peoplecore.reimbursement.application.command.ReimbursementModels.Draft;
import id.mydev.peoplecore.reimbursement.application.command.ReimbursementModels.Receipt;
import id.mydev.peoplecore.reimbursement.application.command.ReimbursementModels.View;
import id.mydev.peoplecore.reimbursement.application.command.ReimbursementModels.HistoryPage;
import id.mydev.peoplecore.reimbursement.application.command.ReimbursementModels.ClaimPage;
import id.mydev.peoplecore.reimbursement.application.service.ReimbursementCategoryService.Category;
import id.mydev.peoplecore.reimbursement.application.service.ReimbursementCategoryService.CategoryPage;
import id.mydev.peoplecore.reimbursement.application.service.ReimbursementCategoryService;
import id.mydev.peoplecore.reimbursement.application.service.ReimbursementWorkflowService;
import jakarta.validation.Valid;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.time.LocalDate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reimbursement")
@ConditionalOnProperty(prefix = "peoplecore.reimbursement", name = "enabled", havingValue = "true")
public class ReimbursementController {
    private static final Set<String> ACTIONS = Set.of("SUBMIT", "RESUBMIT", "WITHDRAW", "REASSIGN", "RETRY_ROUTE",
        "APPROVE", "REJECT", "RETURN", "RECORD_PAYMENT", "CORRECT_PAYMENT");
    private final ReimbursementCommands commands;
    private final ReimbursementWorkflowService workflow;
    private final ReimbursementCategoryService categories;

    public ReimbursementController(ReimbursementCommands commands, ReimbursementWorkflowService workflow,
                                   ReimbursementCategoryService categories) {
        this.commands = commands;
        this.workflow = workflow;
        this.categories = categories;
    }

    @GetMapping("/categories")
    public ApiData<CategoryPage> categories(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                            @RequestParam(defaultValue = "50") int limit,
                                            @RequestParam(required = false) UUID cursor, Authentication caller) {
        return ApiData.of(categories.applicable(date, limit, cursor, caller));
    }

    @PostMapping("/categories")
    public ApiData<Receipt> publish(@RequestHeader("Idempotency-Key") String key, @Valid @RequestBody Category body,
                                    Authentication caller) {
        return ApiData.of(commands.publishCategory(caller, key, body));
    }

    @PostMapping("/claims")
    public ApiData<Receipt> create(@RequestHeader("Idempotency-Key") String key, @Valid @RequestBody Draft body,
                                   Authentication caller) {
        return ApiData.of(commands.create(caller, key, body));
    }

    @PatchMapping("/claims/{id}")
    public ApiData<Receipt> edit(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                 @Valid @RequestBody Draft body, Authentication caller) {
        return ApiData.of(commands.edit(caller, key, id, body));
    }

    @PostMapping("/claims/{id}/{action}")
    public ApiData<Receipt> act(@PathVariable UUID id, @PathVariable String action,
                                @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody Action body,
                                Authentication caller) {
        String normalized = action.toUpperCase(Locale.ROOT).replace('-', '_');
        if (!ACTIONS.contains(normalized)) throw new IllegalArgumentException("Unknown reimbursement action");
        return ApiData.of(commands.act(caller, key, id, normalized, body));
    }

    @GetMapping("/claims/{id}")
    public ApiData<View> get(@PathVariable UUID id, Authentication caller) {
        return ApiData.of(workflow.get(id, caller));
    }

    @GetMapping("/claims")
    public ApiData<ClaimPage> list(@RequestParam(defaultValue = "50") int limit,
                                   @RequestParam(required = false) UUID cursor, Authentication caller) {
        return ApiData.of(workflow.list(limit, cursor, caller));
    }

    @GetMapping("/claims/{id}/history")
    public ApiData<HistoryPage> history(@PathVariable UUID id, @RequestParam(defaultValue = "50") int limit,
                                        @RequestParam(required = false) UUID cursor, Authentication caller) {
        return ApiData.of(workflow.history(id, limit, cursor, caller));
    }
}
