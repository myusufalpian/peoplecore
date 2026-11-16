package id.mydev.peoplecore.notification.infrastructure.config;

import id.mydev.peoplecore.identity.domain.InvitationCipher;
import id.mydev.peoplecore.identity.infrastructure.delivery.KmsInvitationCipher;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="peoplecore.notifications.enabled",havingValue="true")
public class NotificationCoreConfig {
    @Bean(destroyMethod="close")
    @ConditionalOnMissingBean(KmsClient.class)
    KmsClient invitationKms(@Value("${peoplecore.notifications.region}") String region) {
        return KmsClient.builder().region(Region.of(region)).overrideConfiguration(ClientOverrideConfiguration.builder()
            .apiCallTimeout(Duration.ofSeconds(2)).apiCallAttemptTimeout(Duration.ofSeconds(2))
            .retryStrategy(AwsRetryStrategy.doNotRetry()).build()).build();
    }
    @Bean
    @ConditionalOnMissingBean(InvitationCipher.class)
    InvitationCipher invitationCipher(KmsClient client,@Value("${peoplecore.notifications.invitation-key-id}") String keyId) {
        return new KmsInvitationCipher(client,keyId);
    }
}
