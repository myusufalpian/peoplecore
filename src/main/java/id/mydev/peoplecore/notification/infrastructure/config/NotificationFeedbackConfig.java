package id.mydev.peoplecore.notification.infrastructure.config;

import id.mydev.peoplecore.notification.application.SesFeedbackService;
import id.mydev.peoplecore.notification.infrastructure.provider.SesFeedbackWorker;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import software.amazon.awssdk.services.sqs.SqsClient;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"peoplecore.notifications.enabled","peoplecore.notifications.feedback.enabled","peoplecore.events.enabled"},havingValue="true")
public class NotificationFeedbackConfig {
    @Bean(initMethod="start",destroyMethod="close")
    @ConditionalOnProperty(name="peoplecore.runtime.role",havingValue="worker")
    SesFeedbackWorker feedbackWorker(SqsClient client,SesFeedbackService service,
        @Value("${peoplecore.notifications.feedback.queue-url}") String queueUrl,
        @Value("${peoplecore.notifications.feedback.topic-arn}") String topicArn,Environment environment,ObjectMapper mapper,
        ObjectProvider<NotificationAttentionConfig.NotificationAttentionPoller> alerts) {
        if(alerts.getIfAvailable()==null)
            throw new IllegalStateException("Notification feedback requires an independent attention channel");
        return new SesFeedbackWorker(client,service,queueUrl,topicArn,environment,mapper);
    }
}
