package id.mydev.peoplecore.infrastructure.queue;

import id.mydev.peoplecore.common.outbox.EventDispatchService;
import id.mydev.peoplecore.common.outbox.EventIngestionService;
import id.mydev.peoplecore.common.outbox.TransportRepository;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.util.Assert;
import org.springframework.transaction.PlatformTransactionManager;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "peoplecore.events.enabled", havingValue = "true")
@ConditionalOnProperty(name = "peoplecore.runtime.role", havingValue = "worker")
public class EventTransportConfig {
    @Bean(destroyMethod = "close")
    SqsClient eventSqsClient(@Value("${peoplecore.events.region}") String region,
                             @Value("${peoplecore.events.endpoint:}") String endpoint) {
        var builder = SqsClient.builder().region(Region.of(region)).overrideConfiguration(ClientOverrideConfiguration.builder()
            .apiCallTimeout(Duration.ofSeconds(30)).apiCallAttemptTimeout(Duration.ofSeconds(25))
            .retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build()).build());
        if (!endpoint.isBlank()) {
            URI uri=URI.create(endpoint);
            Assert.isTrue(uri.getHost()!=null && uri.getUserInfo()==null && uri.getQuery()==null && uri.getFragment()==null
                && ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())
                && List.of("localhost","127.0.0.1","[::1]","::1").contains(uri.getHost())),
                "Queue endpoint must use HTTPS or local loopback");
            builder.endpointOverride(uri);
        }
        return builder.build();
    }

    @Bean(initMethod = "validateTopology")
    SqsEventTransport eventTransport(SqsClient eventSqsClient, ObjectMapper mapper, Environment environment) {
        boolean retention = environment.getProperty("peoplecore.events.verify-retention", Boolean.class, true);
        if (!retention) {
            URI endpoint = URI.create(environment.getRequiredProperty("peoplecore.events.endpoint"));
            Assert.isTrue(environment.acceptsProfiles(Profiles.of("local"))
                && List.of("localhost", "127.0.0.1", "::1").contains(endpoint.getHost()),
                "Retention validation exception is restricted to the local loopback emulator");
        }
        return new SqsEventTransport(eventSqsClient, mapper, Map.of(
            "notifications", environment.getRequiredProperty("peoplecore.events.notifications.queue-url"),
            "exports", environment.getRequiredProperty("peoplecore.events.exports.queue-url")), Map.of(
            "notifications", environment.getRequiredProperty("peoplecore.events.notifications.dlq-url"),
            "exports", environment.getRequiredProperty("peoplecore.events.exports.dlq-url")), retention);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnProperty(name = "peoplecore.runtime.role", havingValue = "worker")
    EventTransportWorker eventTransportWorker(SqsEventTransport transport, EventDispatchService dispatcher,
        EventIngestionService ingestion, TransportRepository repository, ObjectMapper mapper, Environment environment,
        Clock clock, PlatformTransactionManager manager) {
        return new EventTransportWorker(transport, dispatcher, ingestion, repository, mapper, environment, clock, manager);
    }
}
