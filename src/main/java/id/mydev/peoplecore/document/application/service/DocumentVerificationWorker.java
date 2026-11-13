package id.mydev.peoplecore.document.application.service;

import id.mydev.peoplecore.document.infrastructure.scanner.ClamAvScanner;
import id.mydev.peoplecore.document.infrastructure.storage.PrivateDocumentStorage;
import java.io.IOException;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import id.mydev.peoplecore.document.domain.repository.DocumentVerificationRepository;
import id.mydev.peoplecore.document.domain.repository.DocumentVerificationRepository.Attempt;
import id.mydev.peoplecore.document.domain.repository.DocumentVerificationRepository.VerifiedContent;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.ArrayList;

public final class DocumentVerificationWorker {
    private static final Logger LOG = LoggerFactory.getLogger(DocumentVerificationWorker.class);
    private static final int MAX_OPEN_VERIFICATIONS = 8;
    private final Semaphore sourceLeases = new Semaphore(MAX_OPEN_VERIFICATIONS);
    private final ThreadPoolExecutor sourceClosers = new ThreadPoolExecutor(MAX_OPEN_VERIFICATIONS,
        MAX_OPEN_VERIFICATIONS, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(MAX_OPEN_VERIFICATIONS),
        Thread.ofVirtual().name("document-verification-close-", 0).factory());
    private final DocumentVerificationRepository repository;
    private final TransactionTemplate transaction;
    private final PrivateDocumentStorage storage;
    private final ClamAvScanner scanner;
    private final DocumentFormatVerifier formatVerifier;
    private final long leaseSeconds;

    public DocumentVerificationWorker(DocumentVerificationRepository repository, PlatformTransactionManager manager,
                                      PrivateDocumentStorage storage, ClamAvScanner scanner,
                                      DocumentFormatVerifier formatVerifier, Duration lease) {
        this.repository = Objects.requireNonNull(repository, "repository");
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
        Attempt attempt = transaction.execute(status -> repository.claim(token, leaseSeconds).orElse(null));
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
            transaction.executeWithoutResult(status -> repository.finalizeVersion(attempt, token,
                new VerifiedContent(result.content().mediaType(), result.content().byteSize(),
                    result.content().sha256(), result.engineVersion() + "/" + result.definitionVersion())));
            return true;
        } catch (ClamAvScanner.MalwareDetectedException | DocumentContentInspector.InvalidDocumentContentException exception) {
            transaction.executeWithoutResult(status -> repository.finishFailure(attempt, token, true));
            discardRejectedCandidate(attempt);
            return true;
        } catch (IOException | RuntimeException exception) {
            transaction.executeWithoutResult(status -> repository.finishFailure(attempt, token, false));
            throw exception;
        }
    }

    private static final class VerificationSources {
        private final ArrayList<CloseOnceInputStream> streams = new ArrayList<>();

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
        Boolean recovered = transaction.execute(status -> repository.recover(retryDelay.toMillis()));
        return Boolean.TRUE.equals(recovered);
    }

    private void discardRejectedCandidate(Attempt attempt) {
        try {
            storage.deleteCandidate(attempt.attemptId());
        } catch (RuntimeException exception) {
            LOG.warn("Rejected document candidate could not be deleted");
        }
    }

}
