package id.mydev.peoplecore.infrastructure.queue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import software.amazon.awssdk.services.sqs.SqsClient;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class EventTransportConfigTest {
    private final ApplicationContextRunner context=new ApplicationContextRunner().withUserConfiguration(EventTransportConfig.class);

    @Test
    void apiRuntimeNeverRequiresQueueConnectivityOrCredentials() {
        context.withPropertyValues("peoplecore.events.enabled=true","peoplecore.runtime.role=api").run(result->{
            assertEquals(0,result.getBeansOfType(SqsClient.class).size());
            assertEquals(0,result.getBeansOfType(EventTransportWorker.class).size());
        });
    }

    @Test
    void disabledWorkerDoesNotStartDispatchers() {
        context.withPropertyValues("peoplecore.events.enabled=false","peoplecore.runtime.role=worker").run(result->{
            assertEquals(0,result.getBeansOfType(SqsClient.class).size());
            assertEquals(0,result.getBeansOfType(EventTransportWorker.class).size());
        });
    }

    @Test
    void productionCannotDisableRetentionVerification() {
        var config=new EventTransportConfig();
        var environment=environment().withProperty("peoplecore.events.endpoint","https://sqs.example.test");
        environment.setActiveProfiles("production");
        assertThrows(IllegalArgumentException.class,()->config.eventTransport(mock(SqsClient.class),new ObjectMapper(),environment));
        environment.setActiveProfiles("local");
        assertThrows(IllegalArgumentException.class,()->config.eventTransport(mock(SqsClient.class),new ObjectMapper(),environment));
        environment.withProperty("peoplecore.events.endpoint","http://127.0.0.1:4566");
        assertNotNull(config.eventTransport(mock(SqsClient.class),new ObjectMapper(),environment));
    }

    @Test
    void nonlocalPlaintextAndCredentialBearingEndpointsAreRejected() {
        var config=new EventTransportConfig();
        assertThrows(IllegalArgumentException.class,()->config.eventSqsClient("ap-southeast-1","http://untrusted.example.test"));
        assertThrows(IllegalArgumentException.class,()->config.eventSqsClient("ap-southeast-1","https://user:secret@example.test"));
        assertThrows(IllegalArgumentException.class,()->config.eventSqsClient("ap-southeast-1","https://example.test?secret=value"));
        assertThrows(IllegalArgumentException.class,()->config.eventSqsClient("ap-southeast-1","https://example.test#fragment"));
        assertThrows(IllegalArgumentException.class,()->config.eventSqsClient("ap-southeast-1","file:/tmp/queue"));
    }

    @Test
    void defaultAwsHttpsAndLoopbackClientsCanBeConstructedWithoutContactingAProvider() {
        var config=new EventTransportConfig();
        for(String endpoint:new String[]{"","https://sqs.example.test","http://localhost:4566"}) {
            try(SqsClient client=config.eventSqsClient("ap-southeast-1",endpoint)) {
                assertNotNull(client);
            }
        }
    }

    private static MockEnvironment environment() {
        return new MockEnvironment().withProperty("peoplecore.events.verify-retention","false")
            .withProperty("peoplecore.events.notifications.queue-url","source-notifications")
            .withProperty("peoplecore.events.notifications.dlq-url","dead-notifications")
            .withProperty("peoplecore.events.exports.queue-url","source-exports")
            .withProperty("peoplecore.events.exports.dlq-url","dead-exports");
    }
}
