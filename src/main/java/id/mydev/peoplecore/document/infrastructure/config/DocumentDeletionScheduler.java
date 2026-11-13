package id.mydev.peoplecore.document.infrastructure.config;

import id.mydev.peoplecore.document.application.service.DocumentDeletionService;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import software.amazon.awssdk.core.exception.SdkException;

public final class DocumentDeletionScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(DocumentDeletionScheduler.class);
    private static final String ENABLED = "peoplecore.documents.deletion.enabled";
    private final DocumentDeletionService deletions;
    private final Environment environment;
    private final int batchSize;

    public DocumentDeletionScheduler(DocumentDeletionService deletions, Environment environment, int batchSize) {
        this.deletions = Objects.requireNonNull(deletions, "deletions");
        this.environment = Objects.requireNonNull(environment, "environment");
        if (batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException("Batch size must be between 1 and 500");
        }
        this.batchSize = batchSize;
    }

    @Scheduled(initialDelayString = "${peoplecore.documents.deletion.poll-interval:PT1H}",
        fixedDelayString = "${peoplecore.documents.deletion.poll-interval:PT1H}")
    public void poll() {
        if (!environment.getProperty(ENABLED, Boolean.class, false)) {
            return;
        }
        try {
            int purged = deletions.purgeExpired(batchSize);
            if (purged > 0) {
                LOG.info("Purged {} expired document deletion claims", purged);
            }
        } catch (DataAccessException | SdkException | IllegalStateException exception) {
            LOG.warn("Document purge is temporarily unavailable");
        }
    }
}
