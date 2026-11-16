package id.mydev.peoplecore.infrastructure.queue;

import id.mydev.peoplecore.common.outbox.EventEnvelope;
import java.time.Instant;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import tools.jackson.databind.ObjectMapper;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SqsEventTransportTest {
    @Test
    void productionTopologyRequiresMatchingDeadLetterPolicyAndFourteenDayRetention() {
        var client=mock(SqsClient.class);
        var source=new HashMap<QueueAttributeName,String>();
        source.put(QueueAttributeName.REDRIVE_POLICY,"{\"deadLetterTargetArn\":\"expected\",\"maxReceiveCount\":5}");
        source.put(QueueAttributeName.MESSAGE_RETENTION_PERIOD,"1209600");
        var dead=new HashMap<QueueAttributeName,String>();
        dead.put(QueueAttributeName.QUEUE_ARN,"expected");
        dead.put(QueueAttributeName.MESSAGE_RETENTION_PERIOD,"1209600");
        when(client.getQueueAttributes(any(GetQueueAttributesRequest.class))).thenAnswer(call->{
            GetQueueAttributesRequest request=call.getArgument(0);
            return GetQueueAttributesResponse.builder().attributes("source".equals(request.queueUrl())?source:dead).build();
        });
        var transport=new SqsEventTransport(client,new ObjectMapper(),Map.of("notifications","source"),Map.of("notifications","dead"));
        transport.validateTopology();
        source.put(QueueAttributeName.MESSAGE_RETENTION_PERIOD,"345600");
        assertThrows(IllegalArgumentException.class,transport::validateTopology);
        source.put(QueueAttributeName.MESSAGE_RETENTION_PERIOD,"1209600");
        dead.put(QueueAttributeName.MESSAGE_RETENTION_PERIOD,"345600");
        assertThrows(IllegalArgumentException.class,transport::validateTopology);
        dead.put(QueueAttributeName.MESSAGE_RETENTION_PERIOD,"1209600");
        source.put(QueueAttributeName.REDRIVE_POLICY,"{\"deadLetterTargetArn\":\"expected\",\"maxReceiveCount\":2}");
        assertThrows(IllegalArgumentException.class,transport::validateTopology);
    }
    @Test
    void topologyRejectsWrongDeadLetterPolicyAndRetention() {
        var client = mock(SqsClient.class);
        when(client.getQueueAttributes(any(GetQueueAttributesRequest.class))).thenReturn(
            GetQueueAttributesResponse.builder().attributes(Map.of(QueueAttributeName.REDRIVE_POLICY,
                "{\"deadLetterTargetArn\":\"other\",\"maxReceiveCount\":5}", QueueAttributeName.MESSAGE_RETENTION_PERIOD, "1209600")).build(),
            GetQueueAttributesResponse.builder().attributes(Map.of(QueueAttributeName.QUEUE_ARN, "expected", QueueAttributeName.MESSAGE_RETENTION_PERIOD, "1209600")).build());
        var transport = new SqsEventTransport(client, new ObjectMapper(), Map.of("notifications", "source"), Map.of("notifications", "dead"));
        assertThrows(IllegalArgumentException.class, transport::validateTopology);
    }

    @Test
    void sendUsesOnlyTheSafeEnvelopeAndTenSecondBudget() {
        var client = mock(SqsClient.class);
        var mapper = new ObjectMapper();
        var event = new EventEnvelope(UUID.randomUUID(), "PAYSLIP_PUBLISHED", 1, "PAYSLIP_PUBLICATION", UUID.randomUUID(), Instant.now(), null, Map.of());
        var transport = new SqsEventTransport(client, mapper, Map.of("notifications", "source"), Map.of("notifications", "dead"));
        transport.send("notifications", event);
        var request = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(client).sendMessage(request.capture());
        assertEquals(mapper.writeValueAsString(event), request.getValue().messageBody());
        assertEquals(Duration.ofSeconds(10), request.getValue().overrideConfiguration().orElseThrow().apiCallTimeout().orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> transport.send("unknown", event));
    }
}
