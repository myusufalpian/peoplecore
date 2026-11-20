package id.mydev.peoplecore.infrastructure.persistence;

import id.mydev.peoplecore.common.command.CommandConflictException;
import id.mydev.peoplecore.infrastructure.recovery.ServiceRecoveryRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcServiceRecoveryRepository implements ServiceRecoveryRepository {
    private static final String INCIDENT_COLUMNS = "public_id,status,restore_point_at,observed_loss_window_seconds,"
        + "initiated_by,reason,created_at,reconciled_at,reopened_at,rto_seconds";
    private static final String EXCEPTION_COLUMNS = "public_id,incident_public_id,domain,owner_role,resource_type,"
        + "resource_public_id,exception_code,status,detail,resolved_by,resolution_reason,created_at,resolved_at";

    private final JdbcTemplate jdbc;

    public JdbcServiceRecoveryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean hasActiveIncident() {
        Boolean exists = jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM service_recovery_incident WHERE status IN ('ISOLATED','RECONCILING'))",
            Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public Optional<Incident> findActiveIncident() {
        return jdbc.query("SELECT " + INCIDENT_COLUMNS + " FROM service_recovery_incident "
                + "WHERE status IN ('ISOLATED','RECONCILING') ORDER BY created_at DESC LIMIT 1",
            (rs, row) -> mapIncident(rs)).stream().findFirst();
    }

    @Override
    public Optional<Incident> findIncident(UUID incidentPublicId, boolean forUpdate) {
        return jdbc.query("SELECT " + INCIDENT_COLUMNS + " FROM service_recovery_incident WHERE public_id=?"
                + (forUpdate ? " FOR UPDATE" : ""),
            (rs, row) -> mapIncident(rs), incidentPublicId).stream().findFirst();
    }

    @Override
    public Optional<String> operationHash(String actor, String idempotencyKey) {
        return jdbc.query("SELECT request_hash FROM service_recovery_operation WHERE actor=? AND idempotency_key=?",
            (rs, row) -> rs.getString(1), actor, idempotencyKey).stream().findFirst();
    }

    @Override
    public void recordOperation(String actor, String idempotencyKey, String requestHash, UUID incidentPublicId,
                                String action, String reason, Instant createdAt) {
        jdbc.update("INSERT INTO service_recovery_operation(actor,idempotency_key,request_hash,incident_public_id,"
                + "action,reason,created_at) VALUES (?,?,?,?,?,?,?)",
            actor, idempotencyKey, requestHash, incidentPublicId, action, reason, Timestamp.from(createdAt));
    }

    @Override
    public Incident openIncident(UUID incidentPublicId, Instant restorePointAt, long observedLossWindowSeconds,
                                 String initiatedBy, String reason, Instant now) {
        try {
            jdbc.update("INSERT INTO service_recovery_incident(public_id,status,restore_point_at,"
                    + "observed_loss_window_seconds,initiated_by,reason,created_at) VALUES (?,'ISOLATED',?,?,?,?,?)",
                incidentPublicId, Timestamp.from(restorePointAt), observedLossWindowSeconds,
                initiatedBy, reason, Timestamp.from(now));
        } catch (DataIntegrityViolationException exception) {
            throw new CommandConflictException("An active service recovery incident already exists");
        }
        jdbc.update("UPDATE report_export_job SET status='INVALIDATED',reason_code='SERVICE_RECOVERY' "
            + "WHERE status IN ('PENDING','GENERATING','READY')");
        jdbc.update("UPDATE outbox_events SET status='QUARANTINED',lease_until=NULL,"
            + "transport_reason='RECOVERY_QUARANTINE' WHERE status IN ('PENDING','FAILED')");
        jdbc.update("INSERT INTO event_quarantine(id,event_id,consumer,reason,created_at) "
            + "SELECT gen_random_uuid(),o.event_id,'notifications','RECOVERY_QUARANTINE',? "
            + "FROM outbox_events o WHERE o.status='QUARANTINED' AND o.transport_reason='RECOVERY_QUARANTINE' "
            + "ON CONFLICT(consumer,event_id) DO NOTHING", Timestamp.from(now));
        jdbc.update("UPDATE notification_delivery SET state='FAILED',lease_ends_at=NULL,"
            + "error_code='RECOVERY_QUARANTINE',version=version+1,updated_at=? "
            + "WHERE state IN ('PENDING','SENDING')", Timestamp.from(now));
        jdbc.update("UPDATE auth_generation SET generation=generation+1 WHERE id=1");
        return findIncident(incidentPublicId, false).orElseThrow();
    }

    @Override
    public ReconciliationSummary reconcileIdentities(UUID incidentPublicId, List<UUID> revokedAccountIds,
                                                     Instant cutoffAt, List<UUID> consumedOrRevokedInvitationIds,
                                                     Instant now) {
        int accountsCutOff = 0;
        for (UUID accountPublicId : revokedAccountIds) {
            int updated = jdbc.update("UPDATE user_accounts SET status='INACTIVE',"
                    + "access_ends_at=LEAST(COALESCE(access_ends_at,?),?),version=version+1 WHERE public_id=?",
                Timestamp.from(cutoffAt), Timestamp.from(cutoffAt), accountPublicId);
            if (updated > 0) {
                accountsCutOff += updated;
                jdbc.update("UPDATE role_assignments SET effective_to=LEAST(COALESCE(effective_to,?),?) "
                        + "WHERE account_id=(SELECT id FROM user_accounts WHERE public_id=?) "
                        + "AND (effective_to IS NULL OR effective_to>?)",
                    Timestamp.from(cutoffAt), Timestamp.from(cutoffAt), accountPublicId, Timestamp.from(cutoffAt));
            }
        }
        int invitationsRevoked = 0;
        for (UUID invitationPublicId : consumedOrRevokedInvitationIds) {
            invitationsRevoked += jdbc.update("UPDATE enrollment_invitations SET status='REVOKED',"
                    + "revoked_at=COALESCE(revoked_at,?),version=version+1 "
                    + "WHERE public_id=? AND status='ISSUED'",
                Timestamp.from(now), invitationPublicId);
        }
        int ciphertextsDisposed = jdbc.update("UPDATE invitation_delivery_source SET ciphertext=NULL "
            + "WHERE ciphertext IS NOT NULL AND invitation_public_id IN ("
            + "SELECT public_id FROM enrollment_invitations WHERE status<>'ISSUED' OR expires_at<=?)",
            Timestamp.from(now));
        jdbc.update("UPDATE auth_generation SET generation=generation+1 WHERE id=1");
        jdbc.update("UPDATE service_recovery_incident SET status='RECONCILING',"
            + "reconciled_at=COALESCE(reconciled_at,?) WHERE public_id=? AND status IN ('ISOLATED','RECONCILING')",
            Timestamp.from(now), incidentPublicId);
        Long generation = jdbc.queryForObject("SELECT generation FROM auth_generation WHERE id=1", Long.class);
        return new ReconciliationSummary(incidentPublicId, accountsCutOff, invitationsRevoked,
            ciphertextsDisposed, generation == null ? 0L : generation);
    }

    @Override
    public List<VerifiedDocumentCandidate> verifiedDocumentsForInventory() {
        return jdbc.query("SELECT d.public_id,v.public_id,a.public_id,d.purpose,v.byte_size,v.sha256 "
                + "FROM private_document d "
                + "JOIN document_version v ON v.id=d.current_version_id AND v.document_id=d.id "
                + "JOIN document_verification_attempt a ON a.id=v.attempt_id AND a.document_id=d.id "
                + "WHERE d.status='VERIFIED' AND d.deletion_state<>'DELETED' ORDER BY d.id",
            (rs, row) -> new VerifiedDocumentCandidate(
                rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class),
                rs.getString(4),
                rs.getLong(5),
                rs.getString(6)
            ));
    }

    @Override
    public void recordException(UUID exceptionPublicId, UUID incidentPublicId, String domain, String ownerRole,
                                String resourceType, UUID resourcePublicId, String exceptionCode, String detail,
                                Instant now) {
        jdbc.update("INSERT INTO service_recovery_exception(public_id,incident_public_id,domain,owner_role,"
                + "resource_type,resource_public_id,exception_code,status,detail,created_at) "
                + "VALUES (?,?,?,?,?,?,?,'OPEN',?,?) "
                + "ON CONFLICT (incident_public_id,resource_type,resource_public_id,exception_code) DO NOTHING",
            exceptionPublicId, incidentPublicId, domain, ownerRole, resourceType, resourcePublicId,
            exceptionCode, detail, Timestamp.from(now));
    }

    @Override
    public void markIncidentReconciling(UUID incidentPublicId, Instant now) {
        jdbc.update("UPDATE service_recovery_incident SET status='RECONCILING',"
                + "reconciled_at=COALESCE(reconciled_at,?) WHERE public_id=? AND status IN ('ISOLATED','RECONCILING')",
            Timestamp.from(now), incidentPublicId);
    }

    @Override
    public Optional<RecoveryException> findException(UUID incidentPublicId, UUID exceptionPublicId, boolean forUpdate) {
        return jdbc.query("SELECT " + EXCEPTION_COLUMNS + " FROM service_recovery_exception "
                + "WHERE incident_public_id=? AND public_id=?" + (forUpdate ? " FOR UPDATE" : ""),
            (rs, row) -> mapException(rs), incidentPublicId, exceptionPublicId).stream().findFirst();
    }

    @Override
    public List<RecoveryException> listExceptions(UUID incidentPublicId) {
        return jdbc.query("SELECT " + EXCEPTION_COLUMNS + " FROM service_recovery_exception "
                + "WHERE incident_public_id=? ORDER BY created_at,public_id",
            (rs, row) -> mapException(rs), incidentPublicId);
    }

    @Override
    public void resolveException(UUID exceptionPublicId, String status, String resolvedBy, String reason, Instant now) {
        jdbc.update("UPDATE service_recovery_exception SET status=?,resolved_by=?,resolution_reason=?,resolved_at=? "
                + "WHERE public_id=? AND status='OPEN'",
            status, resolvedBy, reason, Timestamp.from(now), exceptionPublicId);
    }

    @Override
    public int openExceptionCount(UUID incidentPublicId) {
        Integer count = jdbc.queryForObject("SELECT count(public_id) FROM service_recovery_exception "
            + "WHERE incident_public_id=? AND status='OPEN'", Integer.class, incidentPublicId);
        return count == null ? 0 : count;
    }

    @Override
    public int openExceptionCountForRole(UUID incidentPublicId, String ownerRole) {
        Integer count = jdbc.queryForObject("SELECT count(public_id) FROM service_recovery_exception "
            + "WHERE incident_public_id=? AND owner_role=? AND status='OPEN'",
            Integer.class, incidentPublicId, ownerRole);
        return count == null ? 0 : count;
    }

    @Override
    public List<Approval> listApprovals(UUID incidentPublicId) {
        return jdbc.query("SELECT incident_public_id,approver_role,actor_id,reason,approved_at "
                + "FROM service_recovery_approval WHERE incident_public_id=? ORDER BY approved_at,approver_role",
            (rs, row) -> new Approval(
                rs.getObject(1, UUID.class),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getTimestamp(5).toInstant()
            ), incidentPublicId);
    }

    @Override
    public void recordApproval(UUID incidentPublicId, String approverRole, String actorId, String reason, Instant now) {
        jdbc.update("INSERT INTO service_recovery_approval(incident_public_id,approver_role,actor_id,reason,approved_at) "
                + "VALUES (?,?,?,?,?) ON CONFLICT (incident_public_id,approver_role) DO NOTHING",
            incidentPublicId, approverRole, actorId, reason, Timestamp.from(now));
    }

    @Override
    public Incident reopenIncident(UUID incidentPublicId, Instant now) {
        Incident current = findIncident(incidentPublicId, true).orElseThrow();
        long rtoSeconds = Math.max(0L, Duration.between(current.createdAt(), now).toSeconds());
        jdbc.update("UPDATE service_recovery_incident SET status='REOPENED',"
                + "reconciled_at=COALESCE(reconciled_at,?),reopened_at=?,rto_seconds=? "
                + "WHERE public_id=? AND status IN ('ISOLATED','RECONCILING')",
            Timestamp.from(now), Timestamp.from(now), rtoSeconds, incidentPublicId);
        jdbc.update("UPDATE auth_generation SET generation=generation+1 WHERE id=1");
        return findIncident(incidentPublicId, false).orElseThrow();
    }

    @Override
    public OperationalBacklog queryBacklog(Instant now) {
        QueueBacklog outbox = backlog("SELECT count(id),min(created_at) FROM outbox_events WHERE status IN ('PENDING','FAILED')", now);
        QueueBacklog deliveries = backlog("SELECT count(public_id),min(created_at) FROM notification_delivery WHERE state IN ('PENDING','SENDING')", now);
        QueueBacklog alerts = backlog("SELECT count(attention_id),min(next_attempt_at) FROM notification_attention_alert WHERE delivered_at IS NULL", now);
        QueueBacklog exports = backlog("SELECT count(public_id),min(created_at) FROM report_export_job WHERE status IN ('PENDING','GENERATING')", now);
        QueueBacklog verifications = backlog("SELECT count(a.id),min(a.created_at) FROM document_verification_attempt a "
            + "JOIN private_document d ON d.id=a.document_id "
            + "WHERE a.status IN ('QUEUED','CLAIMED') AND d.status='VERIFYING' AND d.deletion_state='LIVE'", now);
        Long quarantined = jdbc.queryForObject("SELECT (SELECT count(id) FROM event_quarantine) + "
            + "(SELECT count(o.id) FROM outbox_events o WHERE o.status='QUARANTINED' "
            + "AND NOT EXISTS (SELECT 1 FROM event_quarantine q WHERE q.event_id=o.event_id))", Long.class);
        Optional<Incident> active = findActiveIncident();
        Long openExceptions = jdbc.queryForObject(
            "SELECT count(public_id) FROM service_recovery_exception WHERE status='OPEN'", Long.class);
        return new OperationalBacklog(
            outbox,
            deliveries,
            alerts,
            exports,
            verifications,
            quarantined == null ? 0L : quarantined,
            active.map(Incident::publicId).orElse(null),
            active.map(Incident::status).orElse(null),
            openExceptions == null ? 0L : openExceptions
        );
    }

    private QueueBacklog backlog(String sql, Instant now) {
        return jdbc.query(sql, (rs, row) -> {
            long count = rs.getLong(1);
            Timestamp oldest = rs.getTimestamp(2);
            long age = (count == 0L || oldest == null) ? 0L : Math.max(0L, Duration.between(oldest.toInstant(), now).toSeconds());
            return new QueueBacklog(count, age);
        }).getFirst();
    }

    private static Incident mapIncident(ResultSet rs) throws SQLException {
        Timestamp reconciled = rs.getTimestamp("reconciled_at");
        Timestamp reopened = rs.getTimestamp("reopened_at");
        long rtoRaw = rs.getLong("rto_seconds");
        Long rtoSeconds = rs.wasNull() ? null : rtoRaw;
        return new Incident(
            rs.getObject("public_id", UUID.class),
            rs.getString("status"),
            rs.getTimestamp("restore_point_at").toInstant(),
            rs.getLong("observed_loss_window_seconds"),
            rs.getString("initiated_by"),
            rs.getString("reason"),
            rs.getTimestamp("created_at").toInstant(),
            reconciled == null ? null : reconciled.toInstant(),
            reopened == null ? null : reopened.toInstant(),
            rtoSeconds
        );
    }

    private static RecoveryException mapException(ResultSet rs) throws SQLException {
        Timestamp resolved = rs.getTimestamp("resolved_at");
        return new RecoveryException(
            rs.getObject("public_id", UUID.class),
            rs.getObject("incident_public_id", UUID.class),
            rs.getString("domain"),
            rs.getString("owner_role"),
            rs.getString("resource_type"),
            rs.getObject("resource_public_id", UUID.class),
            rs.getString("exception_code"),
            rs.getString("status"),
            rs.getString("detail"),
            rs.getString("resolved_by"),
            rs.getString("resolution_reason"),
            rs.getTimestamp("created_at").toInstant(),
            resolved == null ? null : resolved.toInstant()
        );
    }
}
