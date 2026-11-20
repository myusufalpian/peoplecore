package id.mydev.peoplecore.notification.application;

import id.mydev.peoplecore.identity.application.service.IdentityNotificationAccess;
import id.mydev.peoplecore.infrastructure.recovery.ServiceRecoveryGate;
import id.mydev.peoplecore.notification.domain.repository.NotificationRepository;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@ConditionalOnProperty(name="peoplecore.notifications.feedback.enabled",havingValue="true")
public class SesFeedbackService {
    private final NotificationRepository repository;
    private final IdentityNotificationAccess identity;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final String topicArn;
    private final NotificationAcceptance acceptance;
    private ServiceRecoveryGate recoveryGate;
    public SesFeedbackService(NotificationRepository repository,IdentityNotificationAccess identity,NotificationAcceptance acceptance,ObjectMapper mapper,
        Clock clock,@Value("${peoplecore.notifications.feedback.topic-arn}") String topicArn) {
        if(!topicArn.matches("arn:aws[a-z-]*:sns:[a-z0-9-]+:[0-9]{12}:[A-Za-z0-9_-]+"))
            throw new IllegalArgumentException("A trusted feedback topic is required");
        this.repository=repository; this.identity=identity; this.mapper=mapper; this.clock=clock; this.topicArn=topicArn; this.acceptance=acceptance;
    }
    @Autowired(required = false)
    public void configureRecoveryGate(ObjectProvider<ServiceRecoveryGate> recoveryGate) {
        this.recoveryGate = recoveryGate == null ? null : recoveryGate.getIfAvailable();
    }
    @Transactional
    public boolean process(String body) {
        if (recoveryGate != null && recoveryGate.isIsolated()) {
            throw new IllegalStateException("Recovery isolation blocks feedback processing");
        }
        if(body==null||body.length()>65536) throw new IllegalArgumentException("Invalid feedback body");
        JsonNode envelope=mapper.readTree(body);
        if(!"Notification".equals(envelope.path("Type").asText())||!topicArn.equals(envelope.path("TopicArn").asText()))
            throw new IllegalArgumentException("Feedback source is not trusted");
        JsonNode event=mapper.readTree(envelope.path("Message").asText());
        String type=event.path("eventType").asText();
        if(!Set.of("Send","Delivery","Bounce","Complaint","Reject","Rendering Failure").contains(type)) return false;
        JsonNode mail=event.path("mail");
        UUID delivery=UUID.fromString(mail.path("tags").path("delivery_id").path(0).asText());
        UUID attempt=UUID.fromString(mail.path("tags").path("attempt_id").path(0).asText());
        String providerId=mail.path("messageId").asText();
        String source=envelope.path("MessageId").asText();
        if(providerId.isBlank()||providerId.length()>255||source.isBlank()||source.length()>200)
            throw new IllegalArgumentException("Invalid provider evidence reference");
        String state="Delivery".equals(type)?"DELIVERED":"Send".equals(type)?"ACCEPTED":"FAILED";
        var current=acceptance.lock(delivery);
        boolean accepted=repository.feedback(delivery,attempt,providerId,state,source,clock.instant());
        if(accepted) acceptance.accepted(current,attempt);
        if(accepted&&Set.of("Bounce","Complaint").contains(type))
            repository.find(delivery,false).ifPresent(row->identity.suppress(row.destinationId()));
        return accepted;
    }
}
