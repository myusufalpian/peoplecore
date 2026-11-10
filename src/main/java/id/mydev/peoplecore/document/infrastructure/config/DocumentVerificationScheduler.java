package id.mydev.peoplecore.document.infrastructure.config;

import id.mydev.peoplecore.document.application.service.DocumentVerificationWorker;
import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import software.amazon.awssdk.core.exception.SdkException;

public final class DocumentVerificationScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(DocumentVerificationScheduler.class);
    private final DocumentVerificationWorker worker;
    private final Duration retryDelay;

    public DocumentVerificationScheduler(DocumentVerificationWorker worker, Duration retryDelay, Duration pollInterval) {
        this.worker = Objects.requireNonNull(worker, "worker");
        this.retryDelay = Objects.requireNonNull(retryDelay, "retryDelay");
        Objects.requireNonNull(pollInterval, "pollInterval");
        if (retryDelay.isNegative() || retryDelay.isZero() || retryDelay.toMillis() < 1
            || pollInterval.isNegative() || pollInterval.isZero() || pollInterval.toMillis() < 1) {
            throw new IllegalArgumentException("Verification timing must be positive");
        }
    }

    @Scheduled(initialDelayString = "${peoplecore.documents.verification.poll-interval}",
        fixedDelayString = "${peoplecore.documents.verification.poll-interval}")
    public void poll() {
        try {
            worker.recoverNext(retryDelay);
            worker.verifyNext();
        } catch (IOException | DataAccessException | SdkException | IllegalStateException exception) {
            LOG.warn("Document verification is temporarily unavailable");
        }
    }
}
