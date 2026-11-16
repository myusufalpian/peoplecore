package id.mydev.peoplecore.infrastructure.queue;

import id.mydev.peoplecore.common.outbox.EventEnvelope;
import id.mydev.peoplecore.common.outbox.EventTransport;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import org.springframework.util.Assert;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import tools.jackson.databind.ObjectMapper;

public class SqsEventTransport implements EventTransport {
    public static final int MAX_BYTES = 16 * 1024;
    private final SqsClient client;
    private final ObjectMapper mapper;
    private final Map<String, String> queues;
    private final Map<String, String> deadLetters;
    private final boolean verifyRetention;

    public SqsEventTransport(SqsClient client, ObjectMapper mapper, Map<String, String> queues, Map<String, String> deadLetters) {
        this(client, mapper, queues, deadLetters, true);
    }

    public SqsEventTransport(SqsClient client, ObjectMapper mapper, Map<String, String> queues, Map<String, String> deadLetters,
                              boolean verifyRetention) {
        this.client = client;
        this.mapper = mapper;
        this.queues = Map.copyOf(queues);
        this.deadLetters = Map.copyOf(deadLetters);
        this.verifyRetention = verifyRetention;
    }

    public void validateTopology() {
        queues.forEach((consumer, url) -> {
            var source = client.getQueueAttributes(GetQueueAttributesRequest.builder().queueUrl(url).attributeNames(
                QueueAttributeName.REDRIVE_POLICY, QueueAttributeName.MESSAGE_RETENTION_PERIOD).build());
            var dlq = client.getQueueAttributes(GetQueueAttributesRequest.builder().queueUrl(deadLetters.get(consumer))
                .attributeNames(QueueAttributeName.QUEUE_ARN, QueueAttributeName.MESSAGE_RETENTION_PERIOD).build());
            var policy = mapper.readTree(source.attributes().getOrDefault(QueueAttributeName.REDRIVE_POLICY, "{}"));
            Assert.isTrue(dlq.attributes().get(QueueAttributeName.QUEUE_ARN).equals(policy.path("deadLetterTargetArn").asText())
                && policy.path("maxReceiveCount").asInt() == 5, "Queue redrive configuration mismatch");
            if (verifyRetention) {
                Assert.isTrue("1209600".equals(source.attributes().get(QueueAttributeName.MESSAGE_RETENTION_PERIOD))
                    && "1209600".equals(dlq.attributes().get(QueueAttributeName.MESSAGE_RETENTION_PERIOD)), "Queue retention must be 14 days");
            }
        });
    }

    @Override
    public void send(String consumer, EventEnvelope event) {
        String body = mapper.writeValueAsString(event);
        Assert.isTrue(body.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES, "Event envelope exceeds limit");
        client.sendMessage(SendMessageRequest.builder().queueUrl(queue(consumer)).messageBody(body)
            .overrideConfiguration(configuration -> configuration.apiCallTimeout(Duration.ofSeconds(10))).build());
    }

    @Override
    public List<Message> receive(String consumer) {
        return client.receiveMessage(request -> request.queueUrl(queue(consumer)).maxNumberOfMessages(1)
            .waitTimeSeconds(20).visibilityTimeout(60)).messages().stream()
            .map(message -> new Message(message.receiptHandle(), message.body())).toList();
    }

    @Override
    public void acknowledge(String consumer, String receipt) {
        client.deleteMessage(request -> request.queueUrl(queue(consumer)).receiptHandle(receipt));
    }

    @Override
    public void extend(String consumer, String receipt) {
        client.changeMessageVisibility(request -> request.queueUrl(queue(consumer)).receiptHandle(receipt).visibilityTimeout(60));
    }

    @Override
    public long deadLetterCount(String consumer) {
        var response = client.getQueueAttributes(request -> request.queueUrl(deadLetters.get(consumer))
            .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES));
        return Long.parseLong(response.attributes().getOrDefault(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES, "0"));
    }

    private String queue(String consumer) {
        String url = queues.get(consumer);
        Assert.notNull(url, "Unsupported queue consumer");
        return url;
    }
}
