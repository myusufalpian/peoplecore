package id.mydev.peoplecore.notification.infrastructure.provider;

import id.mydev.peoplecore.notification.domain.EmailProvider;
import id.mydev.peoplecore.notification.domain.NotificationModels;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.MessageTag;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SesV2Exception;

public class SesEmailProvider implements EmailProvider {
    private final SesV2Client client;
    private final String sender;
    private final String configurationSet;
    public SesEmailProvider(SesV2Client client,String sender,String configurationSet) {
        if(sender==null||sender.isBlank()||configurationSet==null||configurationSet.isBlank())
            throw new IllegalArgumentException("SES sender and configuration set are required");
        this.client=client; this.sender=sender; this.configurationSet=configurationSet;
    }
    @Override public String send(NotificationModels.Message message) {
        var content=Message.builder()
            .subject(Content.builder().data(message.subject()).charset("UTF-8").build())
            .body(Body.builder().text(Content.builder().data(message.body()).charset("UTF-8").build()).build()).build();
        try {
            var response=client.sendEmail(SendEmailRequest.builder().fromEmailAddress(sender)
                .configurationSetName(configurationSet).destination(Destination.builder().toAddresses(message.address()).build())
                .content(EmailContent.builder().simple(content).build())
                .emailTags(MessageTag.builder().name("delivery_id").value(message.deliveryId().toString()).build(),
                    MessageTag.builder().name("attempt_id").value(message.attemptId().toString()).build()).build());
            if(response.messageId()==null||response.messageId().isBlank()) throw new IllegalStateException("Provider outcome is uncertain");
            return response.messageId();
        } catch(SesV2Exception exception) {
            if(exception.statusCode()>=400&&exception.statusCode()<500) {
                throw new DefinitelyUnsentException(exception.statusCode()==429);
            }
            throw new IllegalStateException("Provider outcome is uncertain");
        }
    }
}
