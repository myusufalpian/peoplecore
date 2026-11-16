package id.mydev.peoplecore.identity.infrastructure.delivery;

import id.mydev.peoplecore.identity.domain.InvitationCipher;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.kms.model.EncryptRequest;

public class KmsInvitationCipher implements InvitationCipher {
    private final KmsClient kms;
    private final String keyId;
    public KmsInvitationCipher(KmsClient kms, String keyId) {
        if (keyId == null || keyId.isBlank()) throw new IllegalArgumentException("Invitation key is required");
        this.kms = kms; this.keyId = keyId;
    }
    @Override public String keyId() { return keyId; }
    @Override public String encrypt(UUID invitationId, UUID destinationId, String secret) {
        var response = kms.encrypt(EncryptRequest.builder().keyId(keyId)
            .encryptionContext(context(invitationId, destinationId))
            .plaintext(SdkBytes.fromUtf8String(secret)).build());
        return Base64.getEncoder().encodeToString(response.ciphertextBlob().asByteArray());
    }
    @Override public String decrypt(UUID invitationId, UUID destinationId, String ciphertext, String storedKeyId) {
        var response = kms.decrypt(DecryptRequest.builder().keyId(storedKeyId)
            .encryptionContext(context(invitationId, destinationId))
            .ciphertextBlob(SdkBytes.fromByteArray(Base64.getDecoder().decode(ciphertext))).build());
        return response.plaintext().asString(StandardCharsets.UTF_8);
    }
    private Map<String, String> context(UUID invitation, UUID destination) {
        return Map.of("purpose", "peoplecore-invitation", "invitation", invitation.toString(),
            "destination", destination.toString());
    }
}
