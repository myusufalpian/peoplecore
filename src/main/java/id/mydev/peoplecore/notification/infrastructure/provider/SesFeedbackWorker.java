package id.mydev.peoplecore.notification.infrastructure.provider;

import id.mydev.peoplecore.notification.application.SesFeedbackService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public class SesFeedbackWorker implements AutoCloseable {
    private static final Logger LOG=LoggerFactory.getLogger(SesFeedbackWorker.class);
    private final SqsClient client;
    private final SesFeedbackService service;
    private final String queueUrl;
    private final String topicArn;
    private final Environment environment;
    private final ObjectMapper mapper;
    private final ScheduledExecutorService executor=Executors.newSingleThreadScheduledExecutor();
    public SesFeedbackWorker(SqsClient client,SesFeedbackService service,String queueUrl,String topicArn,Environment environment,ObjectMapper mapper) {
        this.client=client; this.service=service; this.queueUrl=queueUrl; this.topicArn=topicArn; this.environment=environment; this.mapper=mapper;
    }
    public void start() {
        validatePolicy();
        executor.scheduleWithFixedDelay(this::poll,0,1,TimeUnit.SECONDS);
    }
    public void validatePolicy() {
        var attributes=client.getQueueAttributes(GetQueueAttributesRequest.builder().queueUrl(queueUrl)
            .attributeNames(QueueAttributeName.POLICY,QueueAttributeName.REDRIVE_POLICY).build()).attributes();
        String policy=attributes.get(QueueAttributeName.POLICY);
        String redrive=attributes.get(QueueAttributeName.REDRIVE_POLICY);
        if(policy==null||redrive==null) throw new IllegalArgumentException("Feedback queue requires source policy and DLQ");
        JsonNode statements=mapper.readTree(policy).path("Statement");
        boolean trusted=false;
        String account=topicArn.split(":")[4];
        for(JsonNode statement:statements) {
            if(!"Allow".equals(statement.path("Effect").asText())) continue;
            String principal=statement.path("Principal").path("Service").asText();
            String action=statement.path("Action").asText();
            if(!"sns.amazonaws.com".equals(principal)||!"sqs:SendMessage".equalsIgnoreCase(action))
                throw new IllegalArgumentException("Feedback queue has an unexpected allow grant");
            JsonNode condition=statement.path("Condition");
            String source=condition.path("ArnEquals").path("aws:SourceArn").asText();
            if(source.isBlank()) source=condition.path("ArnLike").path("aws:SourceArn").asText();
            String sourceAccount=condition.path("StringEquals").path("aws:SourceAccount").asText();
            if(!topicArn.equals(source)||!account.equals(sourceAccount))
                throw new IllegalArgumentException("Feedback queue source restrictions are missing");
            trusted=true;
        }
        if(!trusted) throw new IllegalArgumentException("Feedback queue does not trust the configured topic");
    }
    public void poll() {
        if(environment.getProperty("peoplecore.recovery.isolated",Boolean.class,false)) return;
        try {
            var messages=client.receiveMessage(ReceiveMessageRequest.builder().queueUrl(queueUrl).maxNumberOfMessages(10)
                .visibilityTimeout(60).waitTimeSeconds(20).build()).messages();
            for(var message:messages) {
                try {
                    service.process(message.body());
                    client.deleteMessage(DeleteMessageRequest.builder().queueUrl(queueUrl).receiptHandle(message.receiptHandle()).build());
                } catch(RuntimeException exception) {
                    LOG.warn("notification_feedback_rejected errorClass={}",exception.getClass().getSimpleName());
                }
            }
        } catch(RuntimeException exception) { LOG.warn("notification_feedback_failed errorClass={}",exception.getClass().getSimpleName()); }
    }
    @Override public void close() { executor.shutdownNow(); }
}
