package id.mydev.peoplecore.notification.infrastructure.config;

import id.mydev.peoplecore.notification.application.NotificationAttentionWorker;
import id.mydev.peoplecore.notification.domain.NotificationAttentionSink;
import id.mydev.peoplecore.notification.domain.repository.NotificationAttentionRepository;
import id.mydev.peoplecore.notification.infrastructure.provider.WebhookNotificationAttentionSink;
import java.net.URI;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods=false)
@EnableScheduling
@ConditionalOnProperty(name={"peoplecore.notifications.enabled","peoplecore.notifications.attention.enabled"},havingValue="true")
@ConditionalOnProperty(name="peoplecore.runtime.role",havingValue="worker")
public class NotificationAttentionConfig {
    @Bean
    @ConditionalOnMissingBean(NotificationAttentionSink.class)
    NotificationAttentionSink notificationAttentionSink(@Value("${peoplecore.notifications.attention.webhook-url}") URI endpoint,
            @Value("${peoplecore.notifications.attention.token}") String token,ObjectMapper mapper) {
        return new WebhookNotificationAttentionSink(endpoint,token,mapper);
    }
    @Bean
    NotificationAttentionPoller notificationAttentionPoller(NotificationAttentionRepository repository,
            NotificationAttentionSink sink,Clock clock,PlatformTransactionManager manager) {
        return new NotificationAttentionPoller(new NotificationAttentionWorker(repository,sink,clock,manager));
    }
    static class NotificationAttentionPoller {
        private final NotificationAttentionWorker worker;
        NotificationAttentionPoller(NotificationAttentionWorker worker) { this.worker=worker; }
        @Scheduled(fixedDelay=5000,initialDelay=5000)
        public void poll() {
            for(int index=0;index<10;index++) if(!worker.poll()) break;
        }
    }
}
