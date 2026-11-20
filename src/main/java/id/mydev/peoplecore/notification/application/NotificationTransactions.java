package id.mydev.peoplecore.notification.application;

import id.mydev.peoplecore.identity.application.service.IdentityNotificationAccess;
import id.mydev.peoplecore.common.command.AdmissionGuard;
import id.mydev.peoplecore.infrastructure.recovery.ServiceRecoveryGate;
import id.mydev.peoplecore.notification.domain.NotificationModels.Delivery;
import id.mydev.peoplecore.notification.domain.NotificationModels.Intent;
import id.mydev.peoplecore.notification.domain.NotificationModels.Message;
import id.mydev.peoplecore.notification.domain.NotificationResolver;
import id.mydev.peoplecore.notification.domain.repository.NotificationRepository;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name="peoplecore.notifications.enabled",havingValue="true")
public class NotificationTransactions {
    private final NotificationRepository repository;
    private final IdentityNotificationAccess identity;
    private final List<NotificationResolver> resolvers;
    private final Clock clock;
    private final AdmissionGuard admission;
    private final String applicationUrl;
    private final NotificationAcceptance acceptance;
    private ServiceRecoveryGate recoveryGate;
    public NotificationTransactions(NotificationRepository repository,IdentityNotificationAccess identity,
        List<NotificationResolver> resolvers,Clock clock,AdmissionGuard admission,NotificationAcceptance acceptance,@Value("${peoplecore.notifications.application-url:}") String applicationUrl) {
        URI url=URI.create(applicationUrl);
        if(!"https".equals(url.getScheme()) || url.getHost()==null || url.getUserInfo()!=null || url.getFragment()!=null)
            throw new IllegalArgumentException("An HTTPS application URL is required");
        this.repository=repository; this.identity=identity; this.resolvers=List.copyOf(resolvers); this.clock=clock; this.admission=admission;
        this.acceptance=acceptance;
        this.applicationUrl=applicationUrl.replaceAll("/+$", "");
    }
    @Autowired(required = false)
    public void configureRecoveryGate(ObjectProvider<ServiceRecoveryGate> recoveryGate) {
        this.recoveryGate = recoveryGate == null ? null : recoveryGate.getIfAvailable();
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public Optional<Delivery> claim() {
        if (recoveryGate != null && recoveryGate.isIsolated()) {
            return Optional.empty();
        }
        Instant now=clock.instant();
        identity.cleanup(); repository.expireLeases(now);
        if(!repository.reserve("send",now.truncatedTo(ChronoUnit.SECONDS),10)) return Optional.empty();
        return repository.claim(now);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public Optional<Message> prepare(Delivery claimed) {
        admission.acquire(AdmissionGuard.Mode.SHARED);
        Intent intent=intent(claimed);
        Optional<IdentityNotificationAccess.InvitationContent> invitation=Optional.empty();
        if("INVITATION".equals(claimed.template())) invitation=identity.invitation(claimed.aggregateId(),claimed.destinationId(),claimed.destinationVersion());
        Instant now=clock.instant();
        Delivery current=repository.find(claimed.id(),true).orElseThrow();
        if(!"SENDING".equals(current.state()) || current.generation()!=claimed.generation() || !now.isBefore(current.leaseEndsAt())) return Optional.empty();
        boolean eligible=identity.accountEligible(intent,now) && resolvers.stream().filter(r->r.supports(intent.eventType()))
            .anyMatch(r->r.eligible(intent,now));
        var destination=identity.destination(claimed.employeeId()).filter(d->d.id().equals(claimed.destinationId())&&d.version()==claimed.destinationVersion());
        boolean invitationValid=!"INVITATION".equals(claimed.template()) || invitation.isPresent()
            && identity.invitationEligible(claimed.aggregateId(),claimed.destinationId(),claimed.destinationVersion());
        if(!eligible || destination.isEmpty() || !invitationValid) {
            repository.outcome(claimed,"SUPPRESSED",null,"RECIPIENT_INELIGIBLE",now,now); return Optional.empty();
        }
        String body="A Peoplecore event requires your attention. Reference: "+claimed.aggregateId()+". Event: "+claimed.eventType()+". Event time: "+claimed.occurredAt()+". Open the authenticated application: "+applicationUrl;
        if(invitation.isPresent()) body="Activate your verified Peoplecore identity: "+applicationUrl+"/activate#invitationId="+claimed.aggregateId()+"&secret="+invitation.get().secret();
        return Optional.of(new Message(claimed.id(),repository.attemptId(claimed.id(),claimed.generation()).orElseThrow(),
            destination.get().address(),"Peoplecore "+claimed.template(),body));
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void finish(Delivery delivery,String state,String provider,String error,Instant next) {
        boolean invitationAccepted="ACCEPTED".equals(state)&&"INVITATION".equals(delivery.template());
        if(invitationAccepted) acceptance.lock(delivery.id());
        repository.outcome(delivery,state,provider,error,next,clock.instant());
        if(invitationAccepted) repository.attemptId(delivery.id(),delivery.generation())
            .ifPresent(attempt->acceptance.accepted(delivery,attempt));
    }
    public static Intent intent(Delivery d) {
        return new Intent(d.eventId(),d.eventType(),d.aggregateId(),d.occurredAt(),d.employeeId(),d.accountId(),
            d.template(),d.eligibilityType(),d.assignmentId());
    }
}
