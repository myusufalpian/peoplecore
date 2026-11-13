package id.mydev.peoplecore.document.infrastructure.config;

import id.mydev.peoplecore.document.application.service.DocumentDeletionService;
import id.mydev.peoplecore.document.infrastructure.storage.PrivateDocumentStorage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnBean(PrivateDocumentStorage.class)
@ConditionalOnProperty(name = {"peoplecore.documents.enabled", "peoplecore.documents.deletion.enabled"},
    havingValue = "true")
public class DocumentDeletionConfig {
    private static final int PURGE_BATCH_SIZE = 50;

    @Bean
    DocumentDeletionScheduler documentDeletionScheduler(DocumentDeletionService deletions, Environment environment) {
        return new DocumentDeletionScheduler(deletions, environment, PURGE_BATCH_SIZE);
    }
}
