package id.mydev.peoplecore.document.infrastructure.config;

import id.mydev.peoplecore.document.application.service.RevocableDocumentTransfer;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class DocumentTransferConfig {
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "peoplecore.documents.transfer.enabled", havingValue = "true")
    RevocableDocumentTransfer revocableDocumentTransfer(
            @Value("${peoplecore.documents.transfer.max-concurrent}") int maxConcurrent,
            @Value("${peoplecore.documents.transfer.poll-interval}") String pollInterval,
            @Value("${peoplecore.documents.transfer.check-timeout}") String checkTimeout) {
        return new RevocableDocumentTransfer(maxConcurrent, Duration.parse(pollInterval), Duration.parse(checkTimeout));
    }
}
