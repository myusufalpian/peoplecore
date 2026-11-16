package id.mydev.peoplecore.identity.application.service;

import id.mydev.peoplecore.identity.domain.InvitationCipher;
import id.mydev.peoplecore.identity.domain.repository.NotificationIdentityRepository;
import id.mydev.peoplecore.notification.domain.NotificationModels.Destination;
import java.time.Clock;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name="peoplecore.notifications.enabled", havingValue="true")
public class InvitationDeliveryService {
    private final NotificationIdentityRepository repository;
    private final InvitationCipher cipher;
    private final Clock clock;
    public InvitationDeliveryService(NotificationIdentityRepository repository, InvitationCipher cipher, Clock clock) {
        this.repository = repository; this.cipher = cipher; this.clock = clock;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void store(UUID invitationId, UUID employeeId, String secret) {
        Destination destination = repository.currentDestination(employeeId)
            .filter(row -> "VERIFIED".equals(row.status()))
            .orElseThrow(() -> new IllegalArgumentException("A verified notification destination is required"));
        repository.storeInvitation(invitationId, employeeId, destination.id(), destination.version(),
            cipher.encrypt(invitationId, destination.id(), secret), cipher.keyId(), clock.instant());
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void wipe(UUID invitationId) { repository.wipeInvitation(invitationId); }
}
