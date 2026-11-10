package id.mydev.peoplecore.document.application.service;

import id.mydev.peoplecore.document.infrastructure.scanner.ClamAvScanner;
import id.mydev.peoplecore.document.infrastructure.storage.PrivateDocumentStorage;
import java.io.IOException;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

public final class DocumentVerificationWorker {
    private static final int MAX_OPEN_VERIFICATIONS = 8;
    private final Semaphore sourceLeases = new Semaphore(MAX_OPEN_VERIFICATIONS);
    private final ThreadPoolExecutor sourceClosers = new ThreadPoolExecutor(MAX_OPEN_VERIFICATIONS,
        MAX_OPEN_VERIFICATIONS, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(MAX_OPEN_VERIFICATIONS),
        Thread.ofVirtual().name("document-verification-close-", 0).factory());
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final PrivateDocumentStorage storage;
    private final ClamAvScanner scanner;
    private final DocumentFormatVerifier formatVerifier;
    private final long leaseSeconds;

    public DocumentVerificationWorker(JdbcTemplate jdbc, PlatformTransactionManager manager,
                                      PrivateDocumentStorage storage, ClamAvScanner scanner,
                                      DocumentFormatVerifier formatVerifier, Duration lease) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.scanner = Objects.requireNonNull(scanner, "scanner");
        this.formatVerifier = Objects.requireNonNull(formatVerifier, "formatVerifier");
        this.leaseSeconds = Objects.requireNonNull(lease, "lease").toSeconds();
        if (leaseSeconds < 1) {
            throw new IllegalArgumentException("Verification lease must be positive");
        }
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public boolean verifyNext() throws IOException {
        if (!sourceLeases.tryAcquire()) {
            throw new IOException("Verification source capacity exhausted");
        }
        VerificationSources sources = new VerificationSources();
        try {
            return verifyNext(sources);
        } finally {
            if (sources.isEmpty()) {
                sourceLeases.release();
            } else {
                sourceClosers.execute(() -> {
                    try {
                        sources.close();
                        sourceLeases.release();
                    } catch (IOException ignored) {
                        // Retain the lease when closure cannot be established.
                    }
                });
            }
        }
    }

    private boolean verifyNext(VerificationSources sources) throws IOException {
        UUID token = UUID.randomUUID();
        Attempt attempt = transaction.execute(status -> claim(token));
        if (attempt == null) {
            return false;
        }
        try {
            storage.copyToCandidate(attempt.uploadId(), attempt.attemptId(), token, attempt.size());
            ClamAvScanner.Result result;
            InputStream scanSource = sources.add(storage.openCandidate(attempt.attemptId()));
            result = scanner.scan(scanSource, attempt.mediaType(), attempt.size());
            InputStream formatSource = sources.add(storage.openCandidate(attempt.attemptId()));
            formatVerifier.verify(formatSource, attempt.mediaType(), attempt.size(), result.content().sha256());
            transaction.executeWithoutResult(status -> finalizeVersion(attempt, token, result));
            return true;
        } catch (ClamAvScanner.MalwareDetectedException | DocumentContentInspector.InvalidDocumentContentException exception) {
            finishFailure(attempt, token, true);
            return true;
        } catch (IOException | RuntimeException exception) {
            finishFailure(attempt, token, false);
            throw exception;
        }
    }

    private static final class VerificationSources {
        private final java.util.ArrayList<CloseOnceInputStream> streams = new java.util.ArrayList<>();

        InputStream add(InputStream source) {
            CloseOnceInputStream wrapped = new CloseOnceInputStream(source);
            streams.add(wrapped);
            return wrapped;
        }

        boolean isEmpty() {
            return streams.isEmpty();
        }

        void close() throws IOException {
            for (CloseOnceInputStream stream : streams) {
                stream.close();
            }
        }
    }

    private static final class CloseOnceInputStream extends FilterInputStream {
        private boolean closed;
        private IOException failure;

        CloseOnceInputStream(InputStream source) {
            super(source);
        }

        @Override
        public synchronized void close() throws IOException {
            if (!closed) {
                closed = true;
                try {
                    super.close();
                } catch (IOException exception) {
                    failure = exception;
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    public boolean recoverNext(Duration retryDelay) {
        Objects.requireNonNull(retryDelay, "retryDelay");
        if (retryDelay.isNegative()) {
            throw new IllegalArgumentException("Retry delay must not be negative");
        }
        Boolean recovered = transaction.execute(status -> recover(retryDelay.toMillis()));
        return Boolean.TRUE.equals(recovered);
    }

    private boolean recover(long retryDelayMillis) {
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
            throw new IllegalStateException("Document verification recovery failed");
        }
        return true;
    }

    private Attempt claim(UUID token) {
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
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void finalizeVersion(Attempt attempt, UUID token, ClamAvScanner.Result result) {
        List<Long> parents = jdbc.query("""
            SELECT id FROM private_document WHERE id=? AND status='VERIFYING' AND deletion_state='LIVE'
              AND current_generation=? FOR UPDATE
            """, (rs, row) -> rs.getLong(1), attempt.documentId(), attempt.generation());
        if (parents.isEmpty()) {
            throw new IllegalStateException("Document verification is no longer current");
        }
        int changed = jdbc.update("""
            UPDATE document_verification_attempt SET status='CLEAN',completed_at=clock_timestamp()
            WHERE id=? AND status='CLAIMED' AND lease_token=? AND lease_expires_at>clock_timestamp()
              AND copy_completed_at IS NOT NULL
            """, attempt.id(), token);
        if (changed != 1) {
            throw new IllegalStateException("Document verification lease expired");
        }
        String scannerVersion = result.engineVersion() + "/" + result.definitionVersion();
        if (scannerVersion.length() > 255) {
            throw new IllegalStateException("Scanner identity is too long");
        }
        Long version = jdbc.queryForObject("""
            INSERT INTO document_version(public_id,document_id,attempt_id,lease_token,media_type,byte_size,sha256,scanner_version,verified_at)
            VALUES (?,?,?,?,?,?,?,?,clock_timestamp()) RETURNING id
            """, Long.class, UUID.randomUUID(), attempt.documentId(), attempt.id(), token,
            result.content().mediaType(), result.content().byteSize(), result.content().sha256(), scannerVersion);
        if (version == null || jdbc.update("""
            UPDATE private_document SET status='VERIFIED',current_version_id=?,updated_at=clock_timestamp()
            WHERE id=? AND status='VERIFYING' AND current_generation=?
            """, version, attempt.documentId(), attempt.generation()) != 1) {
            throw new IllegalStateException("Document verification is no longer current");
        }
    }

    private void finishFailure(Attempt attempt, UUID token, boolean rejected) {
        transaction.executeWithoutResult(status -> {
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
        });
    }

    private record Attempt(long id, UUID attemptId, long documentId, long generation, UUID uploadId,
                           String mediaType, long size) {
    }

    private record Recovery(long id, long documentId, long generation) {
    }
}
