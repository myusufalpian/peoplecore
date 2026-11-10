package id.mydev.peoplecore.document.infrastructure.storage;

import java.util.UUID;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

public final class DocumentCopyClaim {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public DocumentCopyClaim(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public String start(UUID uploadId, UUID attemptId, UUID token, long declaredSize) {
        List<String> claimed = transaction.execute(status -> jdbc.query("""
            UPDATE document_verification_attempt a SET copy_started_at = clock_timestamp()
            WHERE a.public_id = ? AND a.lease_token = ? AND a.status = 'CLAIMED'
              AND a.lease_expires_at > clock_timestamp() AND a.copy_started_at IS NULL
              AND a.candidate_key = ?
              AND EXISTS (SELECT 1 FROM document_upload_attempt u
                  WHERE u.id = a.upload_attempt_id AND u.document_id = a.document_id
                    AND u.public_id = ? AND u.status = 'COMPLETED' AND u.byte_size = ?
                    AND u.staging_key = ?)
              AND EXISTS (SELECT 1 FROM private_document d WHERE d.id = a.document_id
                  AND d.status = 'VERIFYING' AND d.deletion_state = 'LIVE'
                  AND d.current_generation = a.generation AND d.current_upload_id = a.upload_attempt_id)
            RETURNING (SELECT u.object_etag FROM document_upload_attempt u WHERE u.id = a.upload_attempt_id)
            """, (rs, row) -> rs.getString(1), attemptId, token, "candidates/" + attemptId, uploadId, declaredSize, "staging/" + uploadId));
        if (claimed == null || claimed.size() != 1 || claimed.getFirst() == null || claimed.getFirst().isBlank()) {
            throw new IllegalStateException("Document copy claim is unavailable or expired");
        }
        return claimed.getFirst();
    }

    public void complete(UUID attemptId, UUID token) {
        Integer updated = transaction.execute(status -> jdbc.update("""
            UPDATE document_verification_attempt SET copy_completed_at = clock_timestamp()
            WHERE public_id = ? AND lease_token = ? AND status = 'CLAIMED'
              AND lease_expires_at > clock_timestamp() AND copy_started_at IS NOT NULL
              AND copy_completed_at IS NULL
            """, attemptId, token));
        requireOne(updated);
    }

    private static void requireOne(Integer updated) {
        if (updated == null || updated != 1) {
            throw new IllegalStateException("Document copy claim is unavailable or expired");
        }
    }
}
