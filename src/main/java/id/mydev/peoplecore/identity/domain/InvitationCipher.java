package id.mydev.peoplecore.identity.domain;

import java.util.UUID;

public interface InvitationCipher {
    String encrypt(UUID invitationId, UUID destinationId, String secret);
    String decrypt(UUID invitationId, UUID destinationId, String ciphertext, String keyId);
    String keyId();
}
