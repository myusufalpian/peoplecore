package id.mydev.peoplecore.identity.domain.repository;

import id.mydev.peoplecore.notification.domain.NotificationModels.Destination;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface NotificationIdentityRepository {
    record VerificationOperation(String hash, UUID destinationId, long version) { }
    Optional<VerificationOperation> verificationOperation(String actor, String key);
    void recordVerification(String actor, String key, String hash, Destination destination, Instant now);
    Optional<Destination> currentDestination(UUID employeeId);
    Destination verifyDestination(UUID employeeId, String address, String evidence, String actor, Instant now);
    void storeInvitation(UUID invitationId, UUID employeeId, UUID destinationId, long destinationVersion,
                         String ciphertext, String keyId, Instant now);
    Optional<InvitationMaterial> lockInvitation(UUID invitationId, Instant now);
    void wipeInvitation(UUID invitationId);
    void cleanupInvalidInvitations(Instant now);
    boolean lockInvitationAcceptance(UUID invitationId, UUID employeeId, UUID destinationId, long destinationVersion);
    void markInvitationAccepted(UUID invitationId);
    void suppressDestination(UUID destinationId);
    record InvitationMaterial(UUID invitationId, UUID employeeId, Destination destination,
                              String ciphertext, String keyId) {
        @Override public String toString() { return "InvitationMaterial[invitationId=" + invitationId + "]"; }
    }
}
