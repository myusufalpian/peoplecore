package id.mydev.peoplecore.document.infrastructure.config;

import id.mydev.peoplecore.document.application.service.DocumentVerificationWorker;
import id.mydev.peoplecore.document.application.service.DocumentFormatVerifier;
import id.mydev.peoplecore.document.infrastructure.scanner.ClamAvScanner;
import id.mydev.peoplecore.document.infrastructure.storage.PrivateDocumentStorage;
import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentVerificationSchedulerTest {
    @Test
    void schedulerIsAbsentWithoutTrustedDependenciesEvenWhenTimingIsConfigured() {
        new ApplicationContextRunner().withUserConfiguration(DocumentVerificationConfig.class)
            .withPropertyValues("peoplecore.documents.verification.lease=PT5M",
                "peoplecore.documents.verification.poll-interval=PT1S",
                "peoplecore.documents.verification.retry-delay=PT1M")
            .run(context -> assertFalse(context.containsBean("documentVerificationScheduler")));
    }

    @Test
    void schedulerIsAvailableWhenEveryDependencyAndTimingIsConfigured() {
        new ApplicationContextRunner().withUserConfiguration(DocumentVerificationConfig.class)
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
            .withBean(PrivateDocumentStorage.class, () -> mock(PrivateDocumentStorage.class))
            .withBean(ClamAvScanner.class, () -> mock(ClamAvScanner.class))
            .withBean(ClamAvScanner.ScannerReadiness.class, () -> mock(ClamAvScanner.ScannerReadiness.class))
            .withBean(DocumentFormatVerifier.class, () -> mock(DocumentFormatVerifier.class))
            .withPropertyValues("peoplecore.documents.verification.lease=PT5M",
                "peoplecore.documents.verification.poll-interval=PT24H",
                "peoplecore.documents.verification.retry-delay=PT1M")
            .run(context -> assertTrue(context.containsBean("documentVerificationScheduler")));
    }

    @Test
    void pollRecoversExpiredWorkAndProcessesAQueuedAttempt() throws IOException {
        DocumentVerificationWorker worker = mock(DocumentVerificationWorker.class);
        var scheduler = new DocumentVerificationScheduler(worker, Duration.ofMinutes(1), Duration.ofSeconds(1));
        scheduler.poll();
        verify(worker).recoverNext(Duration.ofMinutes(1));
        verify(worker).verifyNext();
    }

    @Test
    void unavailableScannerDoesNotEscapeTheScheduledPoll() throws IOException {
        DocumentVerificationWorker worker = mock(DocumentVerificationWorker.class);
        when(worker.verifyNext()).thenThrow(new IOException("unavailable"));
        new DocumentVerificationScheduler(worker, Duration.ofMinutes(1), Duration.ofSeconds(1)).poll();
        verify(worker).verifyNext();
    }

    @Test
    void rejectsBusyLoopOrImmediateRecoveryConfiguration() {
        DocumentVerificationWorker worker = mock(DocumentVerificationWorker.class);
        assertThrows(IllegalArgumentException.class,
            () -> new DocumentVerificationScheduler(worker, Duration.ZERO, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
            () -> new DocumentVerificationScheduler(worker, Duration.ofSeconds(1), Duration.ZERO));
    }
}
