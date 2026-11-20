package id.mydev.peoplecore.infrastructure.recovery;

import id.mydev.peoplecore.common.api.ApiData;
import id.mydev.peoplecore.common.security.CurrentCaller;
import id.mydev.peoplecore.infrastructure.recovery.ServiceRecoveryMapper.ExceptionView;
import id.mydev.peoplecore.infrastructure.recovery.ServiceRecoveryMapper.IncidentDetail;
import id.mydev.peoplecore.infrastructure.recovery.ServiceRecoveryMapper.InventoryView;
import id.mydev.peoplecore.infrastructure.recovery.ServiceRecoveryMapper.ReconciliationView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/operations/recovery/incidents")
public class ServiceRecoveryController {
    private final ServiceRecoveryService service;

    public ServiceRecoveryController(ServiceRecoveryService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApiData<IncidentDetail>> openIncident(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody OpenIncidentRequest request) {
        IncidentDetail detail = service.openIncident(
            CurrentCaller.require(),
            idempotencyKey,
            request.restorePointAt(),
            request.observedLossWindowSeconds(),
            request.reason()
        );
        return ResponseEntity.created(URI.create("/api/v1/operations/recovery/incidents/" + detail.publicId()))
            .cacheControl(CacheControl.noStore())
            .body(ApiData.of(detail));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiData<IncidentDetail>> getIncident(@PathVariable("id") UUID incidentPublicId) {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(ApiData.of(service.getIncident(CurrentCaller.require(), incidentPublicId)));
    }

    @PostMapping("/{id}/reconcile-identities")
    public ResponseEntity<ApiData<ReconciliationView>> reconcileIdentities(
            @PathVariable("id") UUID incidentPublicId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ReconcileIdentitiesRequest request) {
        ReconciliationView result = service.reconcileIdentities(
            CurrentCaller.require(),
            incidentPublicId,
            idempotencyKey,
            request.revokedAccountIds(),
            request.cutoffAt(),
            request.consumedOrRevokedInvitationIds(),
            request.reason()
        );
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(ApiData.of(result));
    }

    @PostMapping("/{id}/inventory-documents")
    public ResponseEntity<ApiData<InventoryView>> inventoryDocuments(
            @PathVariable("id") UUID incidentPublicId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody InventoryDocumentsRequest request) {
        InventoryView result = service.inventoryDocuments(
            CurrentCaller.require(),
            incidentPublicId,
            idempotencyKey,
            request.reason()
        );
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(ApiData.of(result));
    }

    @PostMapping("/{id}/exceptions/{exceptionId}/resolve")
    public ResponseEntity<ApiData<ExceptionView>> resolveException(
            @PathVariable("id") UUID incidentPublicId,
            @PathVariable("exceptionId") UUID exceptionPublicId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ResolveExceptionRequest request) {
        ExceptionView result = service.resolveException(
            CurrentCaller.require(),
            incidentPublicId,
            exceptionPublicId,
            idempotencyKey,
            request.resolution(),
            request.reason()
        );
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(ApiData.of(result));
    }

    @PostMapping("/{id}/approvals")
    public ResponseEntity<ApiData<IncidentDetail>> recordApproval(
            @PathVariable("id") UUID incidentPublicId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody RecordApprovalRequest request) {
        IncidentDetail result = service.recordApproval(
            CurrentCaller.require(),
            incidentPublicId,
            idempotencyKey,
            request.approverRole(),
            request.reason()
        );
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(ApiData.of(result));
    }

    public record OpenIncidentRequest(
        @NotNull Instant restorePointAt,
        @PositiveOrZero long observedLossWindowSeconds,
        @NotBlank @Size(max = 1000) String reason
    ) { }

    public record ReconcileIdentitiesRequest(
        @Size(max = 500) List<@NotNull UUID> revokedAccountIds,
        Instant cutoffAt,
        @Size(max = 500) List<@NotNull UUID> consumedOrRevokedInvitationIds,
        @NotBlank @Size(max = 1000) String reason
    ) { }

    public record InventoryDocumentsRequest(
        @NotBlank @Size(max = 1000) String reason
    ) { }

    public record ResolveExceptionRequest(
        @NotBlank String resolution,
        @NotBlank @Size(max = 1000) String reason
    ) { }

    public record RecordApprovalRequest(
        @NotBlank String approverRole,
        @NotBlank @Size(max = 1000) String reason
    ) { }
}
