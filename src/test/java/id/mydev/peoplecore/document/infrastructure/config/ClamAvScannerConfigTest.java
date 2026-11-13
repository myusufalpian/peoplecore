package id.mydev.peoplecore.document.infrastructure.config;

import id.mydev.peoplecore.document.infrastructure.scanner.ClamAvScanner;
import id.mydev.peoplecore.document.infrastructure.storage.PrivateDocumentStorage;
import id.mydev.peoplecore.document.application.service.DocumentFormatVerifier;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import id.mydev.peoplecore.document.domain.repository.DocumentVerificationRepository;
import org.springframework.transaction.PlatformTransactionManager;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ClamAvScannerConfigTest {
    private static final String[] CONFIG = {
        "peoplecore.documents.scanner.socket=/run/peoplecore/clamd.sock",
        "peoplecore.documents.scanner.timeout=PT5S",
        "peoplecore.documents.scanner.max-concurrent=2"
    };

    @Test
    void doesNotEnableScannerWithoutIndependentReadiness() {
        new ApplicationContextRunner().withUserConfiguration(ClamAvScannerConfig.class)
            .withBean(Clock.class, Clock::systemUTC).withPropertyValues(CONFIG)
            .run(context -> assertFalse(context.containsBean("clamAvScanner")));
    }

    @Test
    void createsScannerOnlyWithReadinessAndExplicitConfiguration() {
        new ApplicationContextRunner().withUserConfiguration(ClamAvScannerConfig.class)
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(ClamAvScanner.ScannerReadiness.class, () -> mock(ClamAvScanner.ScannerReadiness.class))
            .withPropertyValues(CONFIG)
            .run(context -> assertTrue(context.containsBean("clamAvScanner")));
    }

    @Test
    void rejectsRelativeSocketPath() {
        new ApplicationContextRunner().withUserConfiguration(ClamAvScannerConfig.class)
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(ClamAvScanner.ScannerReadiness.class, () -> mock(ClamAvScanner.ScannerReadiness.class))
            .withPropertyValues(CONFIG).withPropertyValues("peoplecore.documents.scanner.socket=relative.sock")
            .run(context -> assertTrue(context.getStartupFailure() != null));
    }

    @Test
    void configuredScannerEnablesVerificationWithAllDependencies() {
        new ApplicationContextRunner().withUserConfiguration(ClamAvScannerConfig.class, DocumentVerificationConfig.class)
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(ClamAvScanner.ScannerReadiness.class, () -> mock(ClamAvScanner.ScannerReadiness.class))
            .withBean(PrivateDocumentStorage.class, () -> mock(PrivateDocumentStorage.class))
            .withBean(DocumentFormatVerifier.class, () -> mock(DocumentFormatVerifier.class))
            .withBean(DocumentVerificationRepository.class, () -> mock(DocumentVerificationRepository.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
            .withPropertyValues(CONFIG)
            .withPropertyValues("peoplecore.documents.verification.lease=PT5M",
                "peoplecore.documents.verification.poll-interval=PT24H",
                "peoplecore.documents.verification.retry-delay=PT1M")
            .run(context -> assertTrue(context.containsBean("documentVerificationScheduler")));
    }
}
