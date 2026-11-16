package id.mydev.peoplecore.notification.infrastructure.config;

import id.mydev.peoplecore.notification.application.NotificationTransactions;
import id.mydev.peoplecore.notification.application.NotificationWorker;
import id.mydev.peoplecore.notification.domain.EmailProvider;
import id.mydev.peoplecore.notification.infrastructure.provider.SesEmailProvider;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"peoplecore.notifications.enabled","peoplecore.notifications.send.enabled"},havingValue="true")
public class NotificationSendConfig {
    @Bean(destroyMethod="close")
    @ConditionalOnMissingBean(SesV2Client.class)
    SesV2Client notificationSes(@Value("${peoplecore.notifications.region}") String region) {
        return SesV2Client.builder().region(Region.of(region)).overrideConfiguration(ClientOverrideConfiguration.builder()
            .apiCallTimeout(Duration.ofSeconds(10)).apiCallAttemptTimeout(Duration.ofSeconds(10))
            .retryStrategy(AwsRetryStrategy.doNotRetry()).build()).build();
    }
    @Bean
    @ConditionalOnMissingBean(EmailProvider.class)
    EmailProvider emailProvider(SesV2Client client,@Value("${peoplecore.notifications.sender}") String sender,
                               @Value("${peoplecore.notifications.configuration-set}") String configurationSet) {
        return new SesEmailProvider(client,sender,configurationSet);
    }
    @Bean(initMethod="start",destroyMethod="close")
    @ConditionalOnProperty(name="peoplecore.runtime.role",havingValue="worker")
    NotificationWorkerLifecycle notificationLifecycle(NotificationTransactions transactions,EmailProvider provider,Clock clock,Environment environment,
            ObjectProvider<NotificationAttentionConfig.NotificationAttentionPoller> alerts) {
        if(alerts.getIfAvailable()==null)
            throw new IllegalStateException("Notification sending requires an independent attention channel");
        return new NotificationWorkerLifecycle(new NotificationWorker(transactions,provider,clock,environment));
    }
}
