package id.mydev.peoplecore.infrastructure.recovery;

import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.common.audit.AuditEvent;
import id.mydev.peoplecore.common.audit.AuditEventWriter;
import id.mydev.peoplecore.common.command.AdmissionGuard;
import id.mydev.peoplecore.common.command.CommandConflictException;
import id.mydev.peoplecore.common.command.CommandIds;
import id.mydev.peoplecore.common.command.IdempotencyKeys;
import id.mydev.peoplecore.common.security.OperationAccessService;
import id.mydev.peoplecore.document.infrastructure.storage.PrivateDocumentStorage;
import id.mydev.peoplecore.infrastructure.recovery.ServiceRecoveryMapper.ExceptionView;
import id.mydev.peoplecore.infrastructure.recovery.ServiceRecoveryMapper.IncidentDetail;
import id.mydev.peoplecore.infrastructure.recovery.ServiceRecoveryMapper.InventoryView;
import id.mydev.peoplecore.infrastructure.recovery.ServiceRecoveryMapper.OperationalHealthSummary;
import id.mydev.peoplecore.infrastructure.recovery.ServiceRecoveryMapper.ReconciliationView;
import id.mydev.peoplecore.infrastructure.recovery.ServiceRecoveryRepository.Approval;
import id.mydev.peoplecore.infrastructure.recovery.ServiceRecoveryRepository.Incident;
import id.mydev.peoplecore.infrastructure.recovery.ServiceRecoveryRepository.RecoveryException;
import id.mydev.peoplecore.infrastructure.recovery.ServiceRecoveryRepository.VerifiedDocumentCandidate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ServiceRecoveryService {
    private static final int MAX_REASON_LENGTH = 1000;
    private static final Set<String> REQUIRED_APPROVER_ROLES = Set.of("HR_ADMIN", "FINANCE_PAYROLL", "SYSTEM_ADMIN");
    private static final Set<String> ALLOWED_RESOLUTIONS = Set.of("RESOLVED", "ACCEPTED_RESTRICTION");

    private final ServiceRecoveryRepository repository;
    private final OperationAccessService access;
    private final AdmissionGuard admission;
    private final AuditEventWriter audit;
    private final ObjectProvider<PrivateDocumentStorage> documentStorage;
    private final ServiceRecoveryGate gate;
    private final Environment environment;
    private final Clock clock;

    public ServiceRecoveryService(ServiceRecoveryRepository repository, OperationAccessService access,
                                  AdmissionGuard admission, AuditEventWriter audit,
                                  ObjectProvider<PrivateDocumentStorage> documentStorage,
                                  ServiceRecoveryGate gate, Environment environment, Clock clock) {
        this.repository = repository;
        this.access = access;
        this.admission = admission;
        this.audit = audit;
        this.documentStorage = documentStorage;
        this.gate = gate;
        this.environment = environment;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public OperationalHealthSummary healthSummary(Authentication caller) {
        Instant now = clock.instant();
        access.requireOperator(caller, now);
        String role = environment.getProperty("peoplecore.runtime.role", "api");
        return ServiceRecoveryMapper.toHealthSummary(role, gate.isIsolated(), repository.queryBacklog(now), now);
    }

    @Transactional(readOnly = true)
    public IncidentDetail getIncident(Authentication caller, UUID incidentPublicId) {
        Instant now = clock.instant();
        access.requireRecoveryViewer(caller, now);
        Incident incident = repository.findIncident(incidentPublicId, false).orElseThrow(ResourceNotFoundException::new);
        return ServiceRecoveryMapper.toIncidentDetail(
            incident,
            repository.listExceptions(incidentPublicId),
            repository.listApprovals(incidentPublicId)
        );
    }

    @Transactional
    public IncidentDetail openIncident(Authentication caller, String idempotencyKey, Instant restorePointAt,
                                       long observedLossWindowSeconds, String reason) {
        admission.acquire(AdmissionGuard.Mode.EXCLUSIVE);
        Instant now = clock.instant();
        access.requireOperator(caller, now);
        validateKeyAndReason(idempotencyKey, reason);
        if (restorePointAt == null || restorePointAt.isAfter(now)) {
            throw new IllegalArgumentException("Restore point must not be in the future");
        }
        if (observedLossWindowSeconds < 0) {
            throw new IllegalArgumentException("Observed loss window must not be negative");
        }
        String trimmedReason = reason.trim();
        UUID incidentId = CommandIds.derive("SERVICE_RECOVERY_OPEN", caller.getName(), idempotencyKey);
        String requestHash = hash("OPEN|" + restorePointAt + "|" + observedLossWindowSeconds + "|" + trimmedReason);
        if (replayed(caller.getName(), idempotencyKey, requestHash)) {
            return getIncident(caller, incidentId);
        }
        Incident created = repository.openIncident(incidentId, restorePointAt, observedLossWindowSeconds,
            caller.getName(), trimmedReason, now);
        recordOperationAndAudit(caller.getName(), idempotencyKey, requestHash, created.publicId(),
            "SERVICE_RECOVERY_OPENED", trimmedReason,
            "{\"restorePointAt\":\"" + restorePointAt + "\",\"lossWindowSeconds\":" + observedLossWindowSeconds + "}",
            now);
        return ServiceRecoveryMapper.toIncidentDetail(
            created,
            repository.listExceptions(created.publicId()),
            repository.listApprovals(created.publicId())
        );
    }

    @Transactional
    public ReconciliationView reconcileIdentities(Authentication caller, UUID incidentPublicId, String idempotencyKey,
                                                  List<UUID> revokedAccountIds, Instant cutoffAt,
                                                  List<UUID> consumedOrRevokedInvitationIds, String reason) {
        admission.acquire(AdmissionGuard.Mode.EXCLUSIVE);
        Instant now = clock.instant();
        access.requireOperator(caller, now);
        validateKeyAndReason(idempotencyKey, reason);
        List<UUID> safeAccounts = sanitizeIds(revokedAccountIds);
        List<UUID> safeInvitations = sanitizeIds(consumedOrRevokedInvitationIds);
        Instant effectiveCutoff = cutoffAt == null ? now : cutoffAt;
        String trimmedReason = reason.trim();
        String requestHash = hash("RECONCILE_ID|" + incidentPublicId + "|" + safeAccounts + "|"
            + effectiveCutoff + "|" + safeInvitations + "|" + trimmedReason);
        Incident incident = requireActiveIncident(incidentPublicId);
        if (replayed(caller.getName(), idempotencyKey, requestHash)) {
            return new ReconciliationView(incident.publicId(), 0, 0, 0, 0L);
        }
        var summary = repository.reconcileIdentities(incident.publicId(), safeAccounts, effectiveCutoff,
            safeInvitations, now);
        recordOperationAndAudit(caller.getName(), idempotencyKey, requestHash, incident.publicId(),
            "SERVICE_RECOVERY_IDENTITIES_RECONCILED", trimmedReason,
            "{\"accountsCutOff\":" + summary.accountsCutOff()
                + ",\"invitationsRevoked\":" + summary.invitationsRevoked()
                + ",\"ciphertextsDisposed\":" + summary.ciphertextsDisposed() + "}",
            now);
        return ServiceRecoveryMapper.toReconciliationView(summary);
    }

    @Transactional
    public InventoryView inventoryDocuments(Authentication caller, UUID incidentPublicId,
                                            String idempotencyKey, String reason) {
        admission.acquire(AdmissionGuard.Mode.EXCLUSIVE);
        Instant now = clock.instant();
        access.requireOperator(caller, now);
        validateKeyAndReason(idempotencyKey, reason);
        PrivateDocumentStorage storage = documentStorage.getIfAvailable();
        if (storage == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Document storage unavailable");
        }
        String trimmedReason = reason.trim();
        String requestHash = hash("INVENTORY_DOCS|" + incidentPublicId + "|" + trimmedReason);
        Incident incident = requireActiveIncident(incidentPublicId);
        List<VerifiedDocumentCandidate> candidates = repository.verifiedDocumentsForInventory();
        if (replayed(caller.getName(), idempotencyKey, requestHash)) {
            int openCount = repository.openExceptionCount(incident.publicId());
            return new InventoryView(incident.publicId(), candidates.size(), openCount, openCount);
        }
        int missingOrCorrupt = 0;
        for (VerifiedDocumentCandidate candidate : candidates) {
            boolean intact = storage.verifyCandidate(candidate.attemptPublicId(), candidate.byteSize(), candidate.sha256());
            if (!intact) {
                missingOrCorrupt++;
                String ownerRole = ("PAYSLIP".equals(candidate.purpose()) || "CLAIM_EVIDENCE".equals(candidate.purpose()))
                    ? "FINANCE_PAYROLL" : "HR_ADMIN";
                String domain = "FINANCE_PAYROLL".equals(ownerRole) ? "FINANCE" : "HR";
                UUID exceptionId = CommandIds.derive("SERVICE_RECOVERY_DOC_EXCEPTION",
                    incident.publicId().toString(), candidate.documentPublicId().toString());
                repository.recordException(
                    exceptionId,
                    incident.publicId(),
                    domain,
                    ownerRole,
                    "PRIVATE_DOCUMENT",
                    candidate.documentPublicId(),
                    "OBJECT_MISSING_OR_CHECKSUM_MISMATCH",
                    "Verified document version " + candidate.versionPublicId() + " failed post-restore storage inventory",
                    now
                );
            }
        }
        repository.markIncidentReconciling(incident.publicId(), now);
        int openExceptions = repository.openExceptionCount(incident.publicId());
        recordOperationAndAudit(caller.getName(), idempotencyKey, requestHash, incident.publicId(),
            "SERVICE_RECOVERY_DOCUMENTS_INVENTORIED", trimmedReason,
            "{\"documentsChecked\":" + candidates.size() + ",\"missingOrCorrupt\":" + missingOrCorrupt + "}",
            now);
        return new InventoryView(incident.publicId(), candidates.size(), missingOrCorrupt, openExceptions);
    }

    @Transactional
    public ExceptionView resolveException(Authentication caller, UUID incidentPublicId, UUID exceptionPublicId,
                                          String idempotencyKey, String resolution, String reason) {
        admission.acquire(AdmissionGuard.Mode.EXCLUSIVE);
        Instant now = clock.instant();
        validateKeyAndReason(idempotencyKey, reason);
        if (resolution == null || !ALLOWED_RESOLUTIONS.contains(resolution)) {
            throw new IllegalArgumentException("Resolution must be RESOLVED or ACCEPTED_RESTRICTION");
        }
        Incident incident = requireActiveIncident(incidentPublicId);
        RecoveryException existing = repository.findException(incident.publicId(), exceptionPublicId, true)
            .orElseThrow(ResourceNotFoundException::new);
        access.requireGlobalRole(caller, existing.ownerRole(), now);
        String trimmedReason = reason.trim();
        String requestHash = hash("RESOLVE_EX|" + incident.publicId() + "|" + exceptionPublicId + "|"
            + resolution + "|" + trimmedReason);
        if (replayed(caller.getName(), idempotencyKey, requestHash)) {
            return ServiceRecoveryMapper.toExceptionView(
                repository.findException(incident.publicId(), exceptionPublicId, false).orElseThrow());
        }
        if (!"OPEN".equals(existing.status())) {
            throw new CommandConflictException("Recovery exception is already resolved");
        }
        repository.resolveException(exceptionPublicId, resolution, caller.getName(), trimmedReason, now);
        recordOperationAndAudit(caller.getName(), idempotencyKey, requestHash, incident.publicId(),
            "SERVICE_RECOVERY_EXCEPTION_" + resolution, trimmedReason,
            "{\"exceptionId\":\"" + exceptionPublicId + "\",\"resourcePublicId\":\"" + existing.resourcePublicId() + "\"}",
            now);
        return ServiceRecoveryMapper.toExceptionView(
            repository.findException(incident.publicId(), exceptionPublicId, false).orElseThrow());
    }

    @Transactional
    public IncidentDetail recordApproval(Authentication caller, UUID incidentPublicId, String idempotencyKey,
                                         String approverRole, String reason) {
        admission.acquire(AdmissionGuard.Mode.EXCLUSIVE);
        Instant now = clock.instant();
        validateKeyAndReason(idempotencyKey, reason);
        if (approverRole == null || !REQUIRED_APPROVER_ROLES.contains(approverRole)) {
            throw new IllegalArgumentException("Approver role must be HR_ADMIN, FINANCE_PAYROLL, or SYSTEM_ADMIN");
        }
        access.requireGlobalRole(caller, approverRole, now);
        String trimmedReason = reason.trim();
        String requestHash = hash("APPROVE|" + incidentPublicId + "|" + approverRole + "|" + trimmedReason);
        if (replayed(caller.getName(), idempotencyKey, requestHash)) {
            return getIncident(caller, incidentPublicId);
        }
        Incident incident = requireActiveIncident(incidentPublicId);
        if (repository.openExceptionCountForRole(incident.publicId(), approverRole) > 0) {
            throw new CommandConflictException("Open recovery exceptions remain for role " + approverRole);
        }
        if ("SYSTEM_ADMIN".equals(approverRole) && repository.openExceptionCount(incident.publicId()) > 0) {
            throw new CommandConflictException("All open recovery exceptions must be resolved before operations sign-off");
        }
        repository.recordApproval(incident.publicId(), approverRole, caller.getName(), trimmedReason, now);
        List<Approval> approvals = repository.listApprovals(incident.publicId());
        Set<String> approvedRoles = approvals.stream().map(Approval::approverRole).collect(Collectors.toSet());
        Incident updated = incident;
        if (approvedRoles.containsAll(REQUIRED_APPROVER_ROLES) && repository.openExceptionCount(incident.publicId()) == 0) {
            updated = repository.reopenIncident(incident.publicId(), now);
        }
        recordOperationAndAudit(caller.getName(), idempotencyKey, requestHash, incident.publicId(),
            "SERVICE_RECOVERY_APPROVED_" + approverRole, trimmedReason,
            "{\"incidentStatus\":\"" + updated.status() + "\"}",
            now);
        return ServiceRecoveryMapper.toIncidentDetail(
            updated,
            repository.listExceptions(incident.publicId()),
            repository.listApprovals(incident.publicId())
        );
    }

    private Incident requireActiveIncident(UUID incidentPublicId) {
        Incident incident = repository.findIncident(incidentPublicId, true).orElseThrow(ResourceNotFoundException::new);
        if ("REOPENED".equals(incident.status())) {
            throw new CommandConflictException("Service recovery incident is already reopened");
        }
        return incident;
    }

    private boolean replayed(String actor, String idempotencyKey, String requestHash) {
        var existing = repository.operationHash(actor, idempotencyKey);
        if (existing.isPresent() && !requestHash.equals(existing.get())) {
            throw new CommandConflictException("Idempotency key already used with different content");
        }
        return existing.isPresent();
    }

    private void recordOperationAndAudit(String actor, String idempotencyKey, String requestHash,
                                         UUID incidentPublicId, String action, String reason,
                                         String details, Instant now) {
        repository.recordOperation(actor, idempotencyKey, requestHash, incidentPublicId, action, reason, now);
        audit.append(new AuditEvent(UUID.randomUUID(), actor, action, "SERVICE_RECOVERY", null,
            incidentPublicId, null, reason, details, now));
    }

    private static List<UUID> sanitizeIds(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        if (ids.size() > 500 || ids.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Identifier list must contain 1 to 500 non-null UUIDs");
        }
        return ids.stream().distinct().sorted().toList();
    }

    private static void validateKeyAndReason(String idempotencyKey, String reason) {
        IdempotencyKeys.requireValid(idempotencyKey);
        if (!StringUtils.hasText(reason) || reason.length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("A recovery reason up to 1000 characters is required");
        }
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
