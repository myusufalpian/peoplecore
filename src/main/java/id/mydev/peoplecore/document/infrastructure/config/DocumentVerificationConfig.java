package id.mydev.peoplecore.document.infrastructure.config;

import id.mydev.peoplecore.document.application.service.DocumentFormatVerifier;
import id.mydev.peoplecore.document.application.service.DocumentVerificationWorker;
import id.mydev.peoplecore.document.infrastructure.scanner.ClamAvScanner;
import id.mydev.peoplecore.document.infrastructure.storage.PrivateDocumentStorage;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnBean({PrivateDocumentStorage.class, ClamAvScanner.class, DocumentFormatVerifier.class,
    ClamAvScanner.ScannerReadiness.class})
@ConditionalOnProperty(prefix = "peoplecore.documents.verification", name = {"lease", "poll-interval", "retry-delay"})
public class DocumentVerificationConfig {
    @Bean
    DocumentVerificationWorker documentVerificationWorker(JdbcTemplate jdbc, PlatformTransactionManager transactions,
            PrivateDocumentStorage storage, ClamAvScanner scanner, DocumentFormatVerifier formatVerifier,
            @Value("${peoplecore.documents.verification.lease}") String lease) {
        return new DocumentVerificationWorker(jdbc, transactions, storage, scanner, formatVerifier, Duration.parse(lease));
    }

    @Bean
    DocumentVerificationScheduler documentVerificationScheduler(DocumentVerificationWorker worker,
            @Value("${peoplecore.documents.verification.retry-delay}") String retryDelay,
            @Value("${peoplecore.documents.verification.poll-interval}") String pollInterval) {
        return new DocumentVerificationScheduler(worker, Duration.parse(retryDelay), Duration.parse(pollInterval));
    }
}
