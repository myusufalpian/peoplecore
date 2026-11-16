package id.mydev.peoplecore.identity.infrastructure.delivery;

import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.kms.model.DecryptResponse;
import software.amazon.awssdk.services.kms.model.EncryptRequest;
import software.amazon.awssdk.services.kms.model.EncryptResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KmsInvitationCipherTest {
    @Test void managedKeyEncryptionAndDecryptionBindInvitationAndDestinationAsAuthenticatedContext() {
        KmsClient client=mock(KmsClient.class); var cipher=new KmsInvitationCipher(client,"managed-key-v2");
        UUID invitation=UUID.randomUUID(); UUID destination=UUID.randomUUID();
        when(client.encrypt(any(EncryptRequest.class))).thenReturn(EncryptResponse.builder().ciphertextBlob(SdkBytes.fromUtf8String("opaque-provider-ciphertext")).build());
        when(client.decrypt(any(DecryptRequest.class))).thenReturn(DecryptResponse.builder().plaintext(SdkBytes.fromUtf8String("activation-secret")).build());
        String encrypted=cipher.encrypt(invitation,destination,"activation-secret");
        assertEquals("opaque-provider-ciphertext",new String(Base64.getDecoder().decode(encrypted)));
        assertEquals("activation-secret",cipher.decrypt(invitation,destination,encrypted,"managed-key-v1"));
        var encrypt=ArgumentCaptor.forClass(EncryptRequest.class); verify(client).encrypt(encrypt.capture());
        var decrypt=ArgumentCaptor.forClass(DecryptRequest.class); verify(client).decrypt(decrypt.capture());
        assertEquals(invitation.toString(),encrypt.getValue().encryptionContext().get("invitation"));
        assertEquals(destination.toString(),encrypt.getValue().encryptionContext().get("destination"));
        assertEquals(encrypt.getValue().encryptionContext(),decrypt.getValue().encryptionContext());
        assertEquals("managed-key-v1",decrypt.getValue().keyId());
        assertEquals("managed-key-v2",cipher.keyId());
    }
    @Test void missingManagedKeyIsRejected() {
        KmsClient client=mock(KmsClient.class);
        assertThrows(IllegalArgumentException.class,()->new KmsInvitationCipher(client,null));
        assertThrows(IllegalArgumentException.class,()->new KmsInvitationCipher(client," "));
    }
}
