package id.mydev.peoplecore.notification.infrastructure.provider;

import id.mydev.peoplecore.notification.application.SesFeedbackService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SesFeedbackWorkerTest {
    private static final String TOPIC="arn:aws:sns:ap-southeast-1:123456789012:feedback";
    private final SqsClient client=mock(SqsClient.class);
    private final SesFeedbackService service=mock(SesFeedbackService.class);
    private final MockEnvironment environment=new MockEnvironment();
    private final ObjectMapper mapper=new ObjectMapper();

    @Test void feedbackQueueRequiresExactTopicAccountAndDlqInsteadOfTrustingArbitrarySqsInput() {
        try(var worker=worker()) {
            attributes(policy("sns.amazonaws.com","sqs:SendMessage",TOPIC,"123456789012"),true);
            assertDoesNotThrow(worker::validatePolicy);
            attributes(policy("sns.amazonaws.com","sqs:SendMessage",TOPIC,"000000000000"),true);
            assertThrows(IllegalArgumentException.class,worker::validatePolicy);
            attributes(policy("sns.amazonaws.com","sqs:SendMessage","arn:aws:sns:ap-southeast-1:123456789012:other","123456789012"),true);
            assertThrows(IllegalArgumentException.class,worker::validatePolicy);
            attributes(policy("attacker.example","sqs:SendMessage",TOPIC,"123456789012"),true);
            assertThrows(IllegalArgumentException.class,worker::validatePolicy);
            attributes(policy("sns.amazonaws.com","sqs:*",TOPIC,"123456789012"),true);
            assertThrows(IllegalArgumentException.class,worker::validatePolicy);
            attributes(policy("sns.amazonaws.com","sqs:SendMessage",TOPIC,"123456789012"),false);
            assertThrows(IllegalArgumentException.class,worker::validatePolicy);
            attributes("{\"Statement\":[]}",true);
            assertThrows(IllegalArgumentException.class,worker::validatePolicy);
        }
    }
    @Test void onlyCommittedValidFeedbackIsAcknowledgedAndInvalidEvidenceRemainsForDlq() {
        try(var worker=worker()) {
            when(client.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(ReceiveMessageResponse.builder().messages(
                Message.builder().body("valid").receiptHandle("accepted-receipt").build(),
                Message.builder().body("untrusted").receiptHandle("retain-receipt").build()).build());
            when(service.process("valid")).thenReturn(true);
            doThrow(new IllegalArgumentException("sensitive-provider-body")).when(service).process("untrusted");
            worker.poll();
            var request=ArgumentCaptor.forClass(DeleteMessageRequest.class); verify(client).deleteMessage(request.capture());
            assertEquals("accepted-receipt",request.getValue().receiptHandle());
        }
    }
    @Test void recoveryIsolationDoesNotReadOrAcknowledgeFeedback() {
        try(var worker=worker()) {
            environment.setProperty("peoplecore.recovery.isolated","true"); worker.poll();
            verify(client,never()).receiveMessage(any(ReceiveMessageRequest.class));
            verify(client,never()).deleteMessage(any(DeleteMessageRequest.class));
        }
    }
    @Test void transportOutageDoesNotCrashPollingOrClaimSuccessfulProcessing() {
        try(var worker=worker()) {
            when(client.receiveMessage(any(ReceiveMessageRequest.class))).thenThrow(new IllegalStateException("private-provider-details"));
            assertDoesNotThrow(worker::poll);
            verify(service,never()).process(any());
        }
    }
    private SesFeedbackWorker worker() { return new SesFeedbackWorker(client,service,"https://sqs.example.test/feedback",TOPIC,environment,mapper); }
    private void attributes(String policy,boolean dlq) {
        var attrs=dlq?Map.of(QueueAttributeName.POLICY,policy,QueueAttributeName.REDRIVE_POLICY,"{\"maxReceiveCount\":5}"):
            Map.of(QueueAttributeName.POLICY,policy);
        when(client.getQueueAttributes(any(GetQueueAttributesRequest.class))).thenReturn(GetQueueAttributesResponse.builder().attributes(attrs).build());
    }
    private String policy(String principal,String action,String source,String account) {
        return mapper.writeValueAsString(Map.of("Statement",List.of(Map.of("Effect","Allow","Principal",Map.of("Service",principal),
            "Action",action,"Condition",Map.of("ArnEquals",Map.of("aws:SourceArn",source),"StringEquals",Map.of("aws:SourceAccount",account))))));
    }
}
