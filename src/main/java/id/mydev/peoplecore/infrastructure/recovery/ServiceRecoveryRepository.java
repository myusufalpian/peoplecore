package id.mydev.peoplecore.infrastructure.recovery;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ServiceRecoveryRepository {
    boolean hasActiveIncident();

    Optional<Incident> findActiveIncident();

    Optional<Incident> findIncident(UUID incidentPublicId, boolean forUpdate);

    Optional<String> operationHash(String actor, String idempotencyKey);

    void recordOperation(String actor, String idempotencyKey, String requestHash, UUID incidentPublicId,
                         String action, String reason, Instant createdAt);

    Incident openIncident(UUID incidentPublicId, Instant restorePointAt, long observedLossWindowSeconds,
                          String initiatedBy, String reason, Instant now);

    ReconciliationSummary reconcileIdentities(UUID incidentPublicId, List<UUID> revokedAccountIds, Instant cutoffAt,
                                              List<UUID> consumedOrRevokedInvitationIds, Instant now);

    List<VerifiedDocumentCandidate> verifiedDocumentsForInventory();

    void recordException(UUID exceptionPublicId, UUID incidentPublicId, String domain, String ownerRole,
                         String resourceType, UUID resourcePublicId, String exceptionCode, String detail, Instant now);

    void markIncidentReconciling(UUID incidentPublicId, Instant now);

    Optional<RecoveryException> findException(UUID incidentPublicId, UUID exceptionPublicId, boolean forUpdate);

    List<RecoveryException> listExceptions(UUID incidentPublicId);

    void resolveException(UUID exceptionPublicId, String status, String resolvedBy, String reason, Instant now);

    int openExceptionCount(UUID incidentPublicId);

    int openExceptionCountForRole(UUID incidentPublicId, String ownerRole);

    List<Approval> listApprovals(UUID incidentPublicId);

    void recordApproval(UUID incidentPublicId, String approverRole, String actorId, String reason, Instant now);

    Incident reopenIncident(UUID incidentPublicId, Instant now);

    OperationalBacklog queryBacklog(Instant now);

    record Incident(
        UUID publicId,
        String status,
        Instant restorePointAt,
        long observedLossWindowSeconds,
        String initiatedBy,
        String reason,
        Instant createdAt,
        Instant reconciledAt,
        Instant reopenedAt,
        Long rtoSeconds
    ) { }

    record RecoveryException(
        UUID publicId,
        UUID incidentPublicId,
        String domain,
        String ownerRole,
        String resourceType,
        UUID resourcePublicId,
        String exceptionCode,
        String status,
        String detail,
        String resolvedBy,
        String resolutionReason,
        Instant createdAt,
        Instant resolvedAt
    ) { }

    record Approval(
        UUID incidentPublicId,
        String approverRole,
        String actorId,
        String reason,
        Instant approvedAt
    ) { }

    record VerifiedDocumentCandidate(
        UUID documentPublicId,
        UUID versionPublicId,
        UUID attemptPublicId,
        String purpose,
        long byteSize,
        String sha256
    ) { }

    record ReconciliationSummary(
        UUID incidentPublicId,
        int accountsCutOff,
        int invitationsRevoked,
        int ciphertextsDisposed,
        long authorizationGeneration
    ) { }

    record QueueBacklog(long pendingCount, long oldestAgeSeconds) { }

    record OperationalBacklog(
        QueueBacklog outbox,
        QueueBacklog notificationDeliveries,
        QueueBacklog notificationAlerts,
        QueueBacklog reportExports,
        QueueBacklog documentVerifications,
        long quarantinedEvents,
        UUID activeIncidentPublicId,
        String activeIncidentStatus,
        long openRecoveryExceptions
    ) { }
}
