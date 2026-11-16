package id.mydev.peoplecore.notification.infrastructure.provider;

import id.mydev.peoplecore.notification.domain.EmailProvider;
import id.mydev.peoplecore.notification.domain.NotificationModels.Message;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;
import software.amazon.awssdk.services.sesv2.model.SesV2Exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SesEmailProviderTest {
    @Test void sendsOneRecipientAndOpaqueCorrelationTags() {
        SesV2Client client=mock(SesV2Client.class);
        when(client.sendEmail(any(SendEmailRequest.class))).thenReturn(SendEmailResponse.builder().messageId("accepted").build());
        var provider=new SesEmailProvider(client,"sender@example.test","audited");
        var message=new Message(UUID.randomUUID(),UUID.randomUUID(),"verified@example.test","Generic","Safe content");
        assertEquals("accepted",provider.send(message));
        var sent=ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(client).sendEmail(sent.capture());
        assertEquals(1,sent.getValue().destination().toAddresses().size());
        assertEquals("verified@example.test",sent.getValue().destination().toAddresses().getFirst());
        assertEquals(message.deliveryId().toString(),sent.getValue().emailTags().getFirst().value());
        assertEquals("audited",sent.getValue().configurationSetName());
        assertFalse(message.toString().contains("verified@example.test"));
        assertFalse(message.toString().contains("Safe content"));
    }
    @Test void explicitThrottleIsDefinitelyUnsentButServerErrorIsUncertain() {
        SesV2Client client=mock(SesV2Client.class);
        var provider=new SesEmailProvider(client,"sender@example.test","audited");
        var message=new Message(UUID.randomUUID(),UUID.randomUUID(),"verified@example.test","Generic","Safe");
        when(client.sendEmail(any(SendEmailRequest.class))).thenThrow(SesV2Exception.builder().statusCode(429).build());
        assertTrue(assertThrows(EmailProvider.DefinitelyUnsentException.class,()->provider.send(message)).retryable());
        when(client.sendEmail(any(SendEmailRequest.class))).thenThrow(SesV2Exception.builder().statusCode(503).build());
        assertThrows(IllegalStateException.class,()->provider.send(message));
    }
}
