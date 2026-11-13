package id.mydev.peoplecore.document.infrastructure.persistence;

import id.mydev.peoplecore.document.domain.repository.DocumentDeletionRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDocumentDeletionRepository implements DocumentDeletionRepository {
    private final JdbcTemplate jdbc;

    public JdbcDocumentDeletionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<TargetRecord> findDocument(UUID publicId) {
        return jdbc.query("SELECT d.id,d.public_id,e.public_id,d.deletion_state,d.deletion_requested_at,"
                + "d.deletion_restore_deadline "
                + "FROM private_document d JOIN employees e ON e.id=d.employee_id WHERE d.public_id=?",
            (rs, row) -> new TargetRecord(rs.getLong(1), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getString(4), instant(rs.getTimestamp(5)),
                instant(rs.getTimestamp(6))),
            publicId).stream().findFirst();
    }

    @Override
    public boolean hasActiveHold(long documentId) {
        Integer count = jdbc.queryForObject("SELECT count(id) FROM document_hold "
            + "WHERE document_id=? AND released_at IS NULL", Integer.class, documentId);
        return count != null && count > 0;
    }

    @Override
    public boolean underRetention(long documentId) {
        Boolean referenced = jdbc.queryForObject("SELECT EXISTS ("
                + "SELECT 1 FROM reimbursement_evidence e JOIN reimbursement_revision r "
                + "ON r.claim_id=e.claim_id AND r.revision=e.revision "
                + "WHERE e.document_version_id=d.current_version_id AND r.sealed) OR EXISTS ("
                + "SELECT 1 FROM payslip_publication p JOIN payslip_batch_item i ON i.id=p.item_id "
                + "WHERE i.document_version_id=d.current_version_id) OR EXISTS ("
                + "SELECT 1 FROM payslip_batch_item i JOIN payslip_batch_revision r ON r.id=i.revision_id "
                + "WHERE i.document_version_id=d.current_version_id AND r.status='REVIEWED') "
                + "FROM private_document d WHERE d.id=?", Boolean.class, documentId);
        return Boolean.TRUE.equals(referenced);
    }

    @Override
    public boolean claim(long documentId, Instant requestedAt, Instant restoreDeadline) {
        return jdbc.update("UPDATE private_document SET deletion_state='CLAIMED',deletion_requested_at=?,"
                + "deletion_restore_deadline=?,updated_at=? WHERE id=? AND deletion_state='LIVE'",
            Timestamp.from(requestedAt), Timestamp.from(restoreDeadline), Timestamp.from(requestedAt), documentId) == 1;
    }

    @Override
    public boolean restore(long documentId, Instant restoredAt) {
        return jdbc.update("UPDATE private_document SET deletion_state='LIVE',deletion_requested_at=NULL,"
                + "deletion_restore_deadline=NULL,updated_at=? WHERE id=? AND deletion_state='CLAIMED' "
                + "AND deletion_restore_deadline>? AND purged_at IS NULL",
            Timestamp.from(restoredAt), documentId, Timestamp.from(restoredAt)) == 1;
    }

    @Override
    public List<Long> findExpired(Instant now, int limit) {
        return jdbc.query("SELECT d.id FROM private_document d WHERE (d.deletion_state='CLAIMED' "
                + "AND d.deletion_restore_deadline<? AND NOT EXISTS (SELECT 1 FROM document_hold h "
                + "WHERE h.document_id=d.id AND h.released_at IS NULL)) "
                + "OR (d.deletion_state='DELETED' AND d.objects_purged_at IS NULL "
                + "AND NOT EXISTS (SELECT 1 FROM document_verification_attempt a WHERE a.document_id=d.id "
                + "AND a.copy_started_at IS NOT NULL AND a.copy_completed_at IS NULL)) "
                + "ORDER BY d.deletion_restore_deadline,d.id LIMIT ?",
            (rs, row) -> rs.getLong(1), Timestamp.from(now), limit);
    }

    @Override
    public List<String> candidateKeys(long documentId) {
        return jdbc.query("SELECT a.candidate_key FROM document_verification_attempt a "
                + "JOIN private_document d ON d.id=a.document_id "
                + "WHERE d.id=? AND d.deletion_state='DELETED' AND a.candidate_key LIKE 'candidates/%' ORDER BY a.id",
            (rs, row) -> rs.getString(1), documentId);
    }

    @Override
    public boolean markPurged(long documentId, Instant purgedAt) {
        return jdbc.update("UPDATE private_document SET deletion_state='DELETED',purged_at=?,updated_at=? "
                + "WHERE id=? AND deletion_state='CLAIMED' AND deletion_restore_deadline<?",
            Timestamp.from(purgedAt), Timestamp.from(purgedAt), documentId, Timestamp.from(purgedAt)) == 1;
    }

    @Override
    public boolean readyForObjectPurge(long documentId) {
        Boolean ready = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM private_document d "
                + "WHERE d.id=? AND d.deletion_state='DELETED' AND d.objects_purged_at IS NULL "
                + "AND NOT EXISTS (SELECT 1 FROM document_verification_attempt a WHERE a.document_id=d.id "
                + "AND a.copy_started_at IS NOT NULL AND a.copy_completed_at IS NULL))",
            Boolean.class, documentId);
        return Boolean.TRUE.equals(ready);
    }

    @Override
    public boolean completeObjectPurge(long documentId, Instant completedAt) {
        return jdbc.update("UPDATE private_document SET objects_purged_at=?,updated_at=? "
                + "WHERE id=? AND deletion_state='DELETED' AND objects_purged_at IS NULL "
                + "AND NOT EXISTS (SELECT 1 FROM document_verification_attempt a "
                + "WHERE a.document_id=private_document.id AND a.copy_started_at IS NOT NULL "
                + "AND a.copy_completed_at IS NULL)",
            Timestamp.from(completedAt), Timestamp.from(completedAt), documentId) == 1;
    }

    @Override
    public void recordPurgeAudit(long documentId, Instant purgedAt) {
        jdbc.update("INSERT INTO audit_events(event_id,actor_id,action,aggregate_type,aggregate_id,"
                + "aggregate_public_id,details,created_at) SELECT gen_random_uuid(),'system:document-purge',"
                + "'DOCUMENT_PURGED','DOCUMENT',d.id,d.public_id,"
                + "'Document deletion deadline expired',? FROM private_document d WHERE d.id=?",
            Timestamp.from(purgedAt), documentId);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
