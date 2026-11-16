package id.mydev.peoplecore.notification.infrastructure.config;

import id.mydev.peoplecore.notification.domain.NotificationAttentionSink;
import id.mydev.peoplecore.notification.domain.repository.NotificationAttentionRepository;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;
import id.mydev.peoplecore.notification.application.SesFeedbackService;
import software.amazon.awssdk.services.sqs.SqsClient;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class NotificationAttentionConfigTest {
    private final ApplicationContextRunner contexts=new ApplicationContextRunner().withUserConfiguration(NotificationAttentionConfig.class)
        .withBean(NotificationAttentionRepository.class,()->mock(NotificationAttentionRepository.class))
        .withBean(PlatformTransactionManager.class,()->mock(PlatformTransactionManager.class))
        .withBean(Clock.class,Clock::systemUTC).withBean(ObjectMapper.class,ObjectMapper::new);
    @Test void disabledOrApiModeNeverSchedulesExternalAlerts() {
        contexts.withPropertyValues("peoplecore.notifications.enabled=true","peoplecore.runtime.role=worker")
            .run(context->assertThat(context).doesNotHaveBean(NotificationAttentionConfig.NotificationAttentionPoller.class));
        contexts.withPropertyValues("peoplecore.notifications.enabled=true","peoplecore.notifications.attention.enabled=true","peoplecore.runtime.role=api")
            .run(context->assertThat(context).doesNotHaveBean(NotificationAttentionConfig.NotificationAttentionPoller.class));
    }
    @Test void explicitWorkerChannelRequiresEndpointAndAuthentication() {
        contexts.withPropertyValues("peoplecore.notifications.enabled=true","peoplecore.notifications.attention.enabled=true","peoplecore.runtime.role=worker")
            .run(context->assertThat(context).hasFailed());
        contexts.withPropertyValues("peoplecore.notifications.enabled=true","peoplecore.notifications.attention.enabled=true","peoplecore.runtime.role=worker",
            "peoplecore.notifications.attention.webhook-url=https://monitor.example.test/alert","peoplecore.notifications.attention.token=test-token")
            .run(context->{assertThat(context).hasSingleBean(NotificationAttentionSink.class);assertThat(context).hasSingleBean(NotificationAttentionConfig.NotificationAttentionPoller.class);});
    }
    @Test void feedbackCannotStartWithoutAnIndependentAlertPoller() {
        new ApplicationContextRunner().withUserConfiguration(NotificationFeedbackConfig.class)
            .withBean(SqsClient.class,()->mock(SqsClient.class)).withBean(SesFeedbackService.class,()->mock(SesFeedbackService.class))
            .withBean(ObjectMapper.class,ObjectMapper::new)
            .withPropertyValues("peoplecore.notifications.enabled=true","peoplecore.notifications.feedback.enabled=true",
                "peoplecore.events.enabled=true","peoplecore.runtime.role=worker",
                "peoplecore.notifications.feedback.queue-url=https://sqs.ap-southeast-1.amazonaws.com/123456789012/feedback",
                "peoplecore.notifications.feedback.topic-arn=arn:aws:sns:ap-southeast-1:123456789012:feedback")
            .run(context->assertThat(context).hasFailed());
    }
}
