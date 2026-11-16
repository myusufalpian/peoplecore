package id.mydev.peoplecore.notification.infrastructure.config;

import id.mydev.peoplecore.notification.application.NotificationTransactions;
import id.mydev.peoplecore.notification.domain.EmailProvider;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.sesv2.SesV2Client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class NotificationSendConfigTest {
    private final ApplicationContextRunner contexts=new ApplicationContextRunner()
        .withUserConfiguration(NotificationSendConfig.class)
        .withBean(SesV2Client.class,()->mock(SesV2Client.class))
        .withBean(EmailProvider.class,()->mock(EmailProvider.class))
        .withBean(NotificationTransactions.class,()->mock(NotificationTransactions.class))
        .withBean(Clock.class,Clock::systemUTC);
    @Test void disabledFlagsNeverStartDispatch() {
        contexts.withPropertyValues("peoplecore.runtime.role=worker").run(context->
            assertThat(context).doesNotHaveBean(NotificationWorkerLifecycle.class));
    }
    @Test void apiRoleNeverStartsDispatchEvenWhenSendIsEnabled() {
        contexts.withPropertyValues("peoplecore.notifications.enabled=true","peoplecore.notifications.send.enabled=true",
            "peoplecore.runtime.role=api").run(context->assertThat(context).doesNotHaveBean(NotificationWorkerLifecycle.class));
    }
    @Test void workerOwnsItsSchedulingWithoutDocumentFlags() {
        contexts.withBean(NotificationAttentionConfig.NotificationAttentionPoller.class,()->mock(NotificationAttentionConfig.NotificationAttentionPoller.class))
            .withPropertyValues("peoplecore.notifications.enabled=true","peoplecore.notifications.send.enabled=true",
            "peoplecore.runtime.role=worker").run(context->assertThat(context).hasSingleBean(NotificationWorkerLifecycle.class));
    }
    @Test void workerCannotStartSendingWithoutAttentionDelivery() {
        contexts.withPropertyValues("peoplecore.notifications.enabled=true","peoplecore.notifications.send.enabled=true",
            "peoplecore.runtime.role=worker").run(context->assertThat(context).hasFailed());
    }
}
