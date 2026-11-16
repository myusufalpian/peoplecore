package id.mydev.peoplecore.identity.application.service;

import id.mydev.peoplecore.identity.domain.InvitationCipher;
import id.mydev.peoplecore.identity.domain.repository.NotificationIdentityRepository;
import id.mydev.peoplecore.notification.domain.NotificationModels.Destination;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InvitationDeliveryServiceTest {
    private static final Instant NOW=Instant.parse("2026-11-13T10:00:00Z");
    private final NotificationIdentityRepository repository=mock(NotificationIdentityRepository.class);
    private final InvitationCipher cipher=mock(InvitationCipher.class);
    private final InvitationDeliveryService service=new InvitationDeliveryService(repository,cipher,Clock.fixed(NOW,ZoneOffset.UTC));
    @Test void storesOnlyCiphertextBoundToInvitationAndVerifiedDestinationVersion() {
        UUID invitation=UUID.randomUUID(); UUID employee=UUID.randomUUID(); UUID destination=UUID.randomUUID();
        when(repository.currentDestination(employee)).thenReturn(Optional.of(new Destination(destination,employee,"verified@example.test",3,"VERIFIED")));
        when(cipher.encrypt(invitation,destination,"one-time-secret")).thenReturn("authenticated-ciphertext"); when(cipher.keyId()).thenReturn("managed-key-v2");
        service.store(invitation,employee,"one-time-secret");
        verify(repository).storeInvitation(invitation,employee,destination,3,"authenticated-ciphertext","managed-key-v2",NOW);
        service.wipe(invitation); verify(repository).wipeInvitation(invitation);
    }
    @Test void missingOrSuppressedDestinationCannotCreateRecoverableInvitationMaterial() {
        UUID employee=UUID.randomUUID(); UUID invitation=UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,()->service.store(invitation,employee,"one-time-secret"));
        when(repository.currentDestination(employee)).thenReturn(Optional.of(new Destination(UUID.randomUUID(),employee,"verified@example.test",0,"SUPPRESSED")));
        assertThrows(IllegalArgumentException.class,()->service.store(invitation,employee,"one-time-secret"));
        verify(cipher,never()).encrypt(any(),any(),anyString());
    }
}
