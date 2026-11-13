package id.mydev.peoplecore.document.infrastructure.persistence;

import id.mydev.peoplecore.document.domain.repository.DocumentVerificationRepository;
import id.mydev.peoplecore.document.domain.exception.DocumentVerificationException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDocumentVerificationRepository implements DocumentVerificationRepository {
    private final JdbcTemplate jdbc;

    public JdbcDocumentVerificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean recover(long retryDelayMillis) {
        List<Recovery> candidates = jdbc.query("""
            SELECT a.id,a.document_id,a.generation FROM document_verification_attempt a
            JOIN private_document d ON d.id=a.document_id
            WHERE d.status='VERIFYING' AND d.deletion_state='LIVE' AND d.current_generation=a.generation
              AND d.current_upload_id=a.upload_attempt_id
              AND ((a.status='CLAIMED' AND a.lease_expires_at<=clock_timestamp())
                OR (a.status='ABANDONED' AND a.completed_at<=clock_timestamp()-(? * interval '1 millisecond')))
            ORDER BY a.id LIMIT 1
            """, (rs, row) -> new Recovery(rs.getLong(1), rs.getLong(2), rs.getLong(3)), retryDelayMillis);
        if (candidates.isEmpty()) {
            return false;
        }
        Recovery candidate = candidates.getFirst();
        List<Long> parents = jdbc.query("""
            SELECT d.id FROM private_document d JOIN document_verification_attempt a ON a.document_id=d.id
            WHERE d.id=? AND d.current_generation=? AND d.status='VERIFYING' AND d.deletion_state='LIVE'
              AND a.id=? AND d.current_upload_id=a.upload_attempt_id FOR UPDATE OF d
            """, (rs, row) -> rs.getLong(1), candidate.documentId(), candidate.generation(), candidate.id());
        if (parents.isEmpty()) {
            return false;
        }
        List<String> attempts = jdbc.query("""
            SELECT status FROM document_verification_attempt WHERE id=? AND document_id=?
              AND ((status='CLAIMED' AND lease_expires_at<=clock_timestamp())
                OR (status='ABANDONED' AND completed_at<=clock_timestamp()-(? * interval '1 millisecond')))
            FOR UPDATE
            """, (rs, row) -> rs.getString(1), candidate.id(), candidate.documentId(), retryDelayMillis);
        if (attempts.isEmpty()) {
            return false;
        }
        if (attempts.getFirst().equals("CLAIMED")) {
            jdbc.update("update document_verification_attempt set status='ABANDONED',completed_at=clock_timestamp() where id=?",
                candidate.id());
        }
        long generation = Math.incrementExact(candidate.generation());
        jdbc.update("update private_document set current_generation=?,updated_at=clock_timestamp() where id=?",
            generation, candidate.documentId());
        UUID nextId = UUID.randomUUID();
        int created = jdbc.update("""
            INSERT INTO document_verification_attempt(public_id,document_id,upload_attempt_id,generation,candidate_key,status,created_at)
            SELECT ?,id,current_upload_id,?,?,'QUEUED',clock_timestamp() FROM private_document WHERE id=?
            """, nextId, generation, "candidates/" + nextId, candidate.documentId());
        if (created != 1) {
            throw new DocumentVerificationException("Document verification recovery failed");
        }
        return true;
    }

    @Override
    public Optional<Attempt> claim(UUID token, long leaseSeconds) {
        List<Attempt> rows = jdbc.query("""
            WITH next_attempt AS (
                SELECT a.id FROM document_verification_attempt a
                JOIN private_document d ON d.id=a.document_id
                JOIN document_upload_attempt u ON u.id=a.upload_attempt_id AND u.document_id=a.document_id
                WHERE a.status='QUEUED' AND d.status='VERIFYING' AND d.deletion_state='LIVE'
                  AND d.current_generation=a.generation AND d.current_upload_id=u.id AND u.status='COMPLETED'
                ORDER BY a.id LIMIT 1 FOR UPDATE OF a SKIP LOCKED
            ), claimed AS (
                UPDATE document_verification_attempt a SET status='CLAIMED',lease_token=?,
                    lease_expires_at=clock_timestamp()+(? * interval '1 second')
                FROM next_attempt n WHERE a.id=n.id
                RETURNING a.id,a.public_id,a.document_id,a.upload_attempt_id,a.generation
            )
            SELECT c.id,c.public_id,c.document_id,c.generation,u.public_id,d.declared_media_type,d.declared_size
            FROM claimed c JOIN document_upload_attempt u ON u.id=c.upload_attempt_id
            JOIN private_document d ON d.id=c.document_id
            """, (rs, row) -> new Attempt(rs.getLong(1), rs.getObject(2, UUID.class), rs.getLong(3),
                rs.getLong(4), rs.getObject(5, UUID.class), rs.getString(6), rs.getLong(7)), token, leaseSeconds);
        return rows.stream().findFirst();
    }

    @Override
    public void finalizeVersion(Attempt attempt, UUID token, VerifiedContent content) {
        List<Long> parents = jdbc.query("""
            SELECT id FROM private_document WHERE id=? AND status='VERIFYING' AND deletion_state='LIVE'
              AND current_generation=? FOR UPDATE
            """, (rs, row) -> rs.getLong(1), attempt.documentId(), attempt.generation());
        if (parents.isEmpty()) {
            throw new DocumentVerificationException("Document verification is no longer current");
        }
        int changed = jdbc.update("""
            UPDATE document_verification_attempt SET status='CLEAN',completed_at=clock_timestamp()
            WHERE id=? AND status='CLAIMED' AND lease_token=? AND lease_expires_at>clock_timestamp()
              AND copy_completed_at IS NOT NULL
            """, attempt.id(), token);
        if (changed != 1) {
            throw new DocumentVerificationException("Document verification lease expired");
        }
        String scannerVersion = content.scannerVersion();
        if (scannerVersion.length() > 255) {
            throw new DocumentVerificationException("Scanner identity is too long");
        }
        Long version = jdbc.queryForObject("""
            INSERT INTO document_version(public_id,document_id,attempt_id,lease_token,media_type,byte_size,sha256,scanner_version,verified_at)
            VALUES (?,?,?,?,?,?,?,?,clock_timestamp()) RETURNING id
            """, Long.class, UUID.randomUUID(), attempt.documentId(), attempt.id(), token,
            content.mediaType(), content.byteSize(), content.sha256(), scannerVersion);
        if (version == null || jdbc.update("""
            UPDATE private_document SET status='VERIFIED',current_version_id=?,updated_at=clock_timestamp()
            WHERE id=? AND status='VERIFYING' AND current_generation=?
            """, version, attempt.documentId(), attempt.generation()) != 1) {
            throw new DocumentVerificationException("Document verification is no longer current");
        }
    }

    @Override
    public void finishFailure(Attempt attempt, UUID token, boolean rejected) {
        jdbc.query("SELECT id FROM private_document WHERE id=? FOR UPDATE", (rs, row) -> rs.getLong(1), attempt.documentId());
        int changed = jdbc.update("""
            UPDATE document_verification_attempt SET status=?,completed_at=clock_timestamp()
            WHERE id=? AND status='CLAIMED' AND lease_token=?
              AND (? = 'ABANDONED' OR lease_expires_at>clock_timestamp())
            """, rejected ? "REJECTED" : "ABANDONED", attempt.id(), token, rejected ? "REJECTED" : "ABANDONED");
        if (changed == 0 && rejected) {
            jdbc.update("""
                UPDATE document_verification_attempt SET status='ABANDONED',completed_at=clock_timestamp()
                WHERE id=? AND status='CLAIMED' AND lease_token=?
                """, attempt.id(), token);
        }
        if (changed == 1 && rejected) {
            jdbc.update("""
                UPDATE private_document SET status='REJECTED',updated_at=clock_timestamp()
                WHERE id=? AND status='VERIFYING' AND current_generation=?
                """, attempt.documentId(), attempt.generation());
        }
    }

    private record Recovery(long id, long documentId, long generation) { }
}
