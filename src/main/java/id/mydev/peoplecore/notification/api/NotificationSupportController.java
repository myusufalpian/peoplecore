package id.mydev.peoplecore.notification.api;

import id.mydev.peoplecore.common.api.ApiData;
import id.mydev.peoplecore.common.security.CurrentCaller;
import id.mydev.peoplecore.notification.application.NotificationSupportService;
import id.mydev.peoplecore.notification.application.NotificationSupportService.Replacement;
import id.mydev.peoplecore.notification.domain.NotificationModels.Summary;
import id.mydev.peoplecore.notification.domain.repository.NotificationRepository.EvidenceSummary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/operations/notification-deliveries")
@ConditionalOnProperty(name="peoplecore.notifications.enabled",havingValue="true")
public class NotificationSupportController {
    private final NotificationSupportService service;
    public NotificationSupportController(NotificationSupportService service) { this.service=service; }
    @GetMapping public ApiData<List<Summary>> list(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return ApiData.of(service.list(CurrentCaller.require(),page,size));
    }
    @GetMapping("/{id}") public ApiData<Summary> detail(@PathVariable UUID id) { return ApiData.of(service.detail(CurrentCaller.require(),id)); }
    @GetMapping("/{id}/evidence") public ApiData<List<EvidenceSummary>> evidence(@PathVariable UUID id) {
        return ApiData.of(service.evidence(CurrentCaller.require(),id));
    }
    @GetMapping("/attention") public ApiData<Integer> attention() { return ApiData.of(service.attention(CurrentCaller.require())); }
    @PostMapping("/{id}/retry") public ApiData<Summary> retry(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,
        @Valid @RequestBody Retry request) {
        return ApiData.of(service.retry(CurrentCaller.require(),id,request.expectedVersion(),key,request.reason(),request.acknowledgePossibleDuplicate()));
    }
    @PostMapping("/{id}/reconcile") public ApiData<Summary> reconcile(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,
        @Valid @RequestBody Reconcile request) {
        return ApiData.of(service.reconcile(CurrentCaller.require(),id,request.expectedVersion(),key,request.reason(),request.evidenceId()));
    }
    @PostMapping("/{id}/replacements") public ApiData<Replacement> replace(@PathVariable UUID id,
        @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Retry request) {
        return ApiData.of(service.replacement(CurrentCaller.require(),id,request.expectedVersion(),key,request.reason(),request.acknowledgePossibleDuplicate()));
    }
    public record Retry(@PositiveOrZero long expectedVersion,@NotBlank @Size(max=1000) String reason,boolean acknowledgePossibleDuplicate) { }
    public record Reconcile(@PositiveOrZero long expectedVersion,@NotBlank @Size(max=1000) String reason,@NotNull UUID evidenceId) { }
}
