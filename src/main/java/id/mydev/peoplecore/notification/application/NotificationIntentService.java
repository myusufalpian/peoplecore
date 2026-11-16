package id.mydev.peoplecore.notification.application;

import id.mydev.peoplecore.common.outbox.EventEnvelope;
import id.mydev.peoplecore.common.outbox.EventIngestionHandler;
import id.mydev.peoplecore.common.outbox.OutboxEvent;
import id.mydev.peoplecore.common.outbox.OutboxEventObserver;
import id.mydev.peoplecore.identity.application.service.IdentityNotificationAccess;
import id.mydev.peoplecore.notification.domain.NotificationModels.Intent;
import id.mydev.peoplecore.notification.domain.NotificationResolver;
import id.mydev.peoplecore.notification.domain.repository.NotificationRepository;
import java.time.Clock;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Comparator;
import id.mydev.peoplecore.notification.domain.NotificationModels.Recipient;
import java.util.UUID;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name="peoplecore.notifications.enabled",havingValue="true")
public class NotificationIntentService implements OutboxEventObserver, EventIngestionHandler {
    private final List<NotificationResolver> resolvers;
    private final NotificationRepository repository;
    private final IdentityNotificationAccess identity;
    private final Clock clock;
    private final Optional<UUID> recoveryAccount;
    public NotificationIntentService(List<NotificationResolver> resolvers,NotificationRepository repository,
                                     IdentityNotificationAccess identity,Clock clock,@Value("${peoplecore.notifications.hr-recovery-account-id:}") String recoveryAccount) {
        this.resolvers=List.copyOf(resolvers); this.repository=repository; this.identity=identity; this.clock=clock; this.recoveryAccount=recoveryAccount.isBlank()?Optional.empty():Optional.of(UUID.fromString(recoveryAccount));
    }
    @Override public boolean supports(String type) { return resolvers.stream().anyMatch(r->r.supports(type)); }
    @Override
    @Transactional(propagation=Propagation.MANDATORY)
    public void created(OutboxEvent event) {
        List<Recipient> candidates=new ArrayList<>();
        for(var resolver:resolvers) {
            if(!resolver.supports(event.getEventType())) continue;
            var recipients=resolver.resolve(event.getEventType(),event.getAggregatePublicId(),event.getCreatedAt());
            if(recipients.isEmpty()) repository.attention(event.getEventId(),null,"RECIPIENT_UNAVAILABLE",clock.instant());
            candidates.addAll(recipients);
            for(var recipient:recipients) {
                if(!"REVIEW_BLOCKED".equals(recipient.template())||!"OWNER".equals(recipient.eligibilityType())) continue;
                var recovery=recoveryAccount.flatMap(id->identity.recoveryRecipient(recipient.employeeId(),id,clock.instant()));
                if(recovery.isEmpty()) repository.attention(event.getEventId(),null,"HR_RECOVERY_UNAVAILABLE",clock.instant());
                recovery.ifPresent(candidates::add);
            }
        }
        var recipients=new LinkedHashMap<UUID,Recipient>();
        candidates.stream().sorted(Comparator.comparingInt((Recipient row)->priority(row.template()))
                .thenComparing(Recipient::template).thenComparing(row->String.valueOf(row.assignmentId())))
            .forEach(recipient->recipients.putIfAbsent(recipient.employeeId(),recipient));
        for(var recipient:recipients.values()) {
            var intent=new Intent(event.getEventId(),event.getEventType(),event.getAggregatePublicId(),event.getCreatedAt(),
                recipient.employeeId(),recipient.accountId(),recipient.template(),recipient.eligibilityType(),recipient.assignmentId());
            repository.storeIntent(intent,identity.destination(recipient.employeeId()),clock.instant());
        }
    }
    private static int priority(String template) {
        return switch(template) {
            case "REVIEW_BLOCKED" -> 0;
            case "REVIEW_REASSIGNED" -> 1;
            case "REVIEW_REQUIRED" -> 2;
            case "REQUEST_CLOSED" -> 3;
            default -> 4;
        };
    }
    @Override
    @Transactional(propagation=Propagation.MANDATORY)
    public void ingest(EventEnvelope event) {
        int inserted=repository.materialize(event.eventId(),clock.instant());
        if(inserted==0) repository.attention(event.eventId(),null,"NO_NEW_DELIVERY_OR_LEGACY_EVENT",clock.instant());
    }
}
