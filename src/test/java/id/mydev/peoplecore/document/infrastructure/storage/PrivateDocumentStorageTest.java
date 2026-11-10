package id.mydev.peoplecore.document.infrastructure.storage;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrivateDocumentStorageTest {
    private final S3Client client = mock(S3Client.class);
    private final S3Presigner presigner = mock(S3Presigner.class);
    private final DocumentCopyClaim claim = mock(DocumentCopyClaim.class);
    private final PrivateDocumentStorage storage = new PrivateDocumentStorage(client, presigner, "documents", claim);

    @Test
    void signsOnlyTheStagingKeyForABoundedTime() {
        UUID upload = UUID.randomUUID();
        storage.presignUpload(upload, "application/pdf", Duration.ofMinutes(5));

        ArgumentCaptor<PutObjectPresignRequest> request = ArgumentCaptor.forClass(PutObjectPresignRequest.class);
        verify(presigner).presignPutObject(request.capture());
        assertEquals("staging/" + upload, request.getValue().putObjectRequest().key());
        assertEquals(Duration.ofMinutes(5), request.getValue().signatureDuration());
        assertThrows(IllegalArgumentException.class, () -> storage.presignUpload(upload, "application/pdf", Duration.ofMinutes(6)));
    }

    @Test
    void copiesOnlyTheInspectedStagingObjectIntoTheAttemptCandidate() {
        UUID upload = UUID.randomUUID();
        UUID attempt = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        when(client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
            .contentLength(12L).eTag("\"etag\"").build());
        when(claim.start(upload, attempt, token, 12)).thenReturn("\"etag\"");

        assertEquals("candidates/" + attempt, storage.copyToCandidate(upload, attempt, token, 12));
        verify(claim).start(upload, attempt, token, 12);
        verify(claim).complete(attempt, token);
        ArgumentCaptor<CopyObjectRequest> request = ArgumentCaptor.forClass(CopyObjectRequest.class);
        verify(client).copyObject(request.capture());
        assertEquals("staging/" + upload, request.getValue().sourceKey());
        assertEquals("candidates/" + attempt, request.getValue().destinationKey());
        assertEquals("\"etag\"", request.getValue().copySourceIfMatch());
    }

    @Test
    void refusesToCopyMismatchedOrOversizedContent() {
        when(client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
            .contentLength(20L).eTag("etag").build());
        assertThrows(IllegalArgumentException.class, () -> storage.copyToCandidate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 12));
        verify(client, never()).copyObject(any(CopyObjectRequest.class));
    }

    @Test
    void refusesStagingReplacementAfterFinalization() {
        UUID upload = UUID.randomUUID();
        UUID attempt = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        when(claim.start(upload, attempt, token, 12)).thenReturn("original");
        when(client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
            .contentLength(12L).eTag("replacement").build());

        assertThrows(IllegalStateException.class, () -> storage.copyToCandidate(upload, attempt, token, 12));
        verify(client, never()).copyObject(any(CopyObjectRequest.class));
        verify(claim, never()).complete(attempt, token);
    }

    @Test
    void closingCandidateAbortsTheUpstreamTransfer() throws Exception {
        @SuppressWarnings("unchecked")
        ResponseInputStream<GetObjectResponse> response = mock(ResponseInputStream.class);
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(response);

        storage.openCandidate(UUID.randomUUID()).close();

        verify(response).abort();
    }
}
