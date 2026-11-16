package id.mydev.peoplecore.infrastructure.queue;

import id.mydev.peoplecore.common.outbox.EventEnvelope;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "PEOPLECORE_SQS_TEST_ENDPOINT", matches = ".+")
class LocalEventTransportTest {
    @Test
    void configuredTopologyAndEnvelopeRoundTripWithAcknowledgement() {
        try (var client = client()) {
            String source = client.getQueueUrl(request -> request.queueName("peoplecore-notifications")).queueUrl();
            String dead = client.getQueueUrl(request -> request.queueName("peoplecore-notifications-dlq")).queueUrl();
            var mapper = new ObjectMapper();
            var transport = new SqsEventTransport(client, mapper, Map.of("notifications", source), Map.of("notifications", dead), false);
            transport.validateTopology();
            var event = new EventEnvelope(UUID.randomUUID(), "PAYSLIP_PUBLISHED", 1, "PAYSLIP_PUBLICATION", UUID.randomUUID(), Instant.now(), null, Map.of());
            transport.send("notifications", event);
            var message = transport.receive("notifications").getFirst();
            assertEquals(event, mapper.readValue(message.body(), EventEnvelope.class));
            transport.extend("notifications", message.receipt());
            transport.acknowledge("notifications", message.receipt());
            assertTrue(client.receiveMessage(request -> request.queueUrl(source).waitTimeSeconds(0)).messages().isEmpty());
        }
    }

    @Test
    void unacknowledgedPoisonMessageMovesToDeadLetterQueue() {
        try (var client = client()) {
            String suffix = UUID.randomUUID().toString();
            String dlq = client.createQueue(request -> request.queueName("test-dead-" + suffix)).queueUrl();
            String arn = client.getQueueAttributes(request -> request.queueUrl(dlq).attributeNames(QueueAttributeName.QUEUE_ARN))
                .attributes().get(QueueAttributeName.QUEUE_ARN);
            String source = client.createQueue(request -> request.queueName("test-source-" + suffix).attributes(Map.of(
                QueueAttributeName.VISIBILITY_TIMEOUT, "0", QueueAttributeName.RECEIVE_MESSAGE_WAIT_TIME_SECONDS, "0",
                QueueAttributeName.REDRIVE_POLICY, "{\"deadLetterTargetArn\":\"" + arn + "\",\"maxReceiveCount\":2}"))).queueUrl();
            try {
                client.sendMessage(request -> request.queueUrl(source).messageBody("invalid-test-envelope"));
                assertFalse(client.receiveMessage(request -> request.queueUrl(source).waitTimeSeconds(0)).messages().isEmpty());
                assertFalse(client.receiveMessage(request -> request.queueUrl(source).waitTimeSeconds(0)).messages().isEmpty());
                client.receiveMessage(request -> request.queueUrl(source).waitTimeSeconds(0));
                var dead = client.receiveMessage(request -> request.queueUrl(dlq).waitTimeSeconds(5));
                assertEquals("invalid-test-envelope", dead.messages().getFirst().body());
            } finally {
                client.deleteQueue(request -> request.queueUrl(source));
                client.deleteQueue(request -> request.queueUrl(dlq));
            }
        }
    }

    private static SqsClient client() {
        return SqsClient.builder().endpointOverride(URI.create(System.getenv("PEOPLECORE_SQS_TEST_ENDPOINT")))
            .region(Region.AP_SOUTHEAST_1).credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local-test", "local-test")))
            .overrideConfiguration(ClientOverrideConfiguration.builder().apiCallTimeout(Duration.ofSeconds(25)).build()).build();
    }
}
