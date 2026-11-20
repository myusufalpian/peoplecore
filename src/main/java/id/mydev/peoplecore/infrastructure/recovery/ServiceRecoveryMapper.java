package id.mydev.peoplecore.infrastructure.recovery;

import id.mydev.peoplecore.infrastructure.recovery.ServiceRecoveryRepository.Approval;
import id.mydev.peoplecore.infrastructure.recovery.ServiceRecoveryRepository.Incident;
import id.mydev.peoplecore.infrastructure.recovery.ServiceRecoveryRepository.OperationalBacklog;
import id.mydev.peoplecore.infrastructure.recovery.ServiceRecoveryRepository.QueueBacklog;
import id.mydev.peoplecore.infrastructure.recovery.ServiceRecoveryRepository.ReconciliationSummary;
import id.mydev.peoplecore.infrastructure.recovery.ServiceRecoveryRepository.RecoveryException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ServiceRecoveryMapper {
    private ServiceRecoveryMapper() {
    }

    public static IncidentDetail toIncidentDetail(Incident incident, List<RecoveryException> exceptions,
                                                  List<Approval> approvals) {
        return new IncidentDetail(
            incident.publicId(),
            incident.status(),
            incident.restorePointAt(),
            incident.observedLossWindowSeconds(),
            incident.initiatedBy(),
            incident.reason(),
            incident.createdAt(),
            incident.reconciledAt(),
            incident.reopenedAt(),
            incident.rtoSeconds(),
            exceptions.stream().map(ServiceRecoveryMapper::toExceptionView).toList(),
            approvals.stream().map(ServiceRecoveryMapper::toApprovalView).toList()
        );
    }

    public static ExceptionView toExceptionView(RecoveryException exception) {
        return new ExceptionView(
            exception.publicId(),
            exception.incidentPublicId(),
            exception.domain(),
            exception.ownerRole(),
            exception.resourceType(),
            exception.resourcePublicId(),
            exception.exceptionCode(),
            exception.status(),
            exception.detail(),
            exception.resolvedBy(),
            exception.resolutionReason(),
            exception.createdAt(),
            exception.resolvedAt()
        );
    }

    public static ApprovalView toApprovalView(Approval approval) {
        return new ApprovalView(
            approval.incidentPublicId(),
            approval.approverRole(),
            approval.actorId(),
            approval.reason(),
            approval.approvedAt()
        );
    }

    public static ReconciliationView toReconciliationView(ReconciliationSummary summary) {
        return new ReconciliationView(
            summary.incidentPublicId(),
            summary.accountsCutOff(),
            summary.invitationsRevoked(),
            summary.ciphertextsDisposed(),
            summary.authorizationGeneration()
        );
    }

    public static OperationalHealthSummary toHealthSummary(String runtimeRole, boolean isolated,
                                                           OperationalBacklog backlog, Instant checkedAt) {
        return new OperationalHealthSummary(
            runtimeRole,
            isolated,
            backlog.activeIncidentPublicId(),
            backlog.activeIncidentStatus(),
            backlog.openRecoveryExceptions(),
            backlog.quarantinedEvents(),
            toBacklogMetric(backlog.outbox()),
            toBacklogMetric(backlog.notificationDeliveries()),
            toBacklogMetric(backlog.notificationAlerts()),
            toBacklogMetric(backlog.reportExports()),
            toBacklogMetric(backlog.documentVerifications()),
            checkedAt
        );
    }

    private static BacklogMetric toBacklogMetric(QueueBacklog backlog) {
        return new BacklogMetric(backlog.pendingCount(), backlog.oldestAgeSeconds());
    }

    public record IncidentDetail(
        UUID publicId,
        String status,
        Instant restorePointAt,
        long observedLossWindowSeconds,
        String initiatedBy,
        String reason,
        Instant createdAt,
        Instant reconciledAt,
        Instant reopenedAt,
        Long rtoSeconds,
        List<ExceptionView> exceptions,
        List<ApprovalView> approvals
    ) { }

    public record ExceptionView(
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

    public record ApprovalView(
        UUID incidentPublicId,
        String approverRole,
        String actorId,
        String reason,
        Instant approvedAt
    ) { }

    public record ReconciliationView(
        UUID incidentPublicId,
        int accountsCutOff,
        int invitationsRevoked,
        int ciphertextsDisposed,
        long authorizationGeneration
    ) { }

    public record InventoryView(
        UUID incidentPublicId,
        int documentsChecked,
        int missingOrCorruptObjects,
        int openExceptions
    ) { }

    public record BacklogMetric(long pendingCount, long oldestAgeSeconds) { }

    public record OperationalHealthSummary(
        String runtimeRole,
        boolean recoveryIsolated,
        UUID activeIncidentPublicId,
        String activeIncidentStatus,
        long openRecoveryExceptions,
        long quarantinedEvents,
        BacklogMetric outbox,
        BacklogMetric notificationDeliveries,
        BacklogMetric notificationAlerts,
        BacklogMetric reportExports,
        BacklogMetric documentVerifications,
        Instant checkedAt
    ) { }
}
