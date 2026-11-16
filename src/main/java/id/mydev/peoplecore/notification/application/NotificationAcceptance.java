package id.mydev.peoplecore.notification.application;

import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.common.command.AdmissionGuard;
import id.mydev.peoplecore.identity.application.service.IdentityNotificationAccess;
import id.mydev.peoplecore.notification.domain.NotificationModels.Delivery;
import id.mydev.peoplecore.notification.domain.repository.NotificationRepository;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name="peoplecore.notifications.enabled",havingValue="true")
public class NotificationAcceptance {
    private final NotificationRepository repository;
    private final IdentityNotificationAccess identity;
    private final AdmissionGuard admission;
    public NotificationAcceptance(NotificationRepository repository,IdentityNotificationAccess identity,AdmissionGuard admission) {
        this.repository=repository; this.identity=identity; this.admission=admission;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public Delivery lock(UUID deliveryId) {
        admission.acquire(AdmissionGuard.Mode.SHARED);
        Delivery preview=repository.find(deliveryId,false).orElseThrow(ResourceNotFoundException::new);
        if("INVITATION".equals(preview.template()) && !identity.lockAcceptance(preview.aggregateId(),preview.employeeId(),
                preview.destinationId(),preview.destinationVersion())) throw new ResourceNotFoundException();
        return repository.find(deliveryId,true).orElseThrow(ResourceNotFoundException::new);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void accepted(Delivery expected,UUID attemptId) {
        if(!"INVITATION".equals(expected.template())) return;
        Delivery current=repository.find(expected.id(),false).orElseThrow(ResourceNotFoundException::new);
        if(current.generation()!=expected.generation() || !Set.of("ACCEPTED","DELIVERED").contains(current.state())) return;
        if(repository.attemptId(current.id(),current.generation()).filter(attemptId::equals).isEmpty()) return;
        identity.accepted(current.aggregateId());
    }
}
