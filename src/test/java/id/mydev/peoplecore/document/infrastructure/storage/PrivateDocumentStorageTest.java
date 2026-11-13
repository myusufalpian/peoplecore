package id.mydev.peoplecore.document.infrastructure.storage;

import id.mydev.peoplecore.document.application.service.DocumentContentInspector;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrivateDocumentStorageTest {
    private static final String BUCKET = "documents";
    private final S3Client client = mock(S3Client.class);
    private final S3Presigner presigner = mock(S3Presigner.class);
    private final DocumentCopyClaim claim = mock(DocumentCopyClaim.class);
    private final PrivateDocumentStorage storage = new PrivateDocumentStorage(client, presigner, BUCKET, claim);

    @Test
    void signsOnlyTheStagingKeyForABoundedTime() {
        UUID upload = UUID.randomUUID();
        PutObjectPresignRequest expected = presignRequest(upload, Duration.ofMinutes(5));

        storage.presignUpload(upload, "application/pdf", Duration.ofMinutes(5));

        verify(presigner).presignPutObject(expected);
        assertThrows(IllegalArgumentException.class,
            () -> storage.presignUpload(upload, "application/pdf", Duration.ofMinutes(6)));
    }

    @Test
    void deletesOnlyTheRejectedCandidateObject() {
        UUID attempt = UUID.randomUUID();

        storage.deleteCandidate(attempt);

        verify(client).deleteObject(DeleteObjectRequest.builder().bucket(BUCKET)
            .key("candidates/" + attempt).build());
    }

    @Test
    void copiesOnlyTheInspectedStagingObjectIntoTheAttemptCandidate() {
        UUID upload = UUID.randomUUID();
        UUID attempt = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        when(client.headObject(headRequest("staging/" + upload))).thenReturn(HeadObjectResponse.builder()
            .contentLength(12L).eTag("\"etag\"").build());
        when(claim.start(upload, attempt, token, 12)).thenReturn("\"etag\"");

        assertEquals("candidates/" + attempt, storage.copyToCandidate(upload, attempt, token, 12));
        verify(claim).start(upload, attempt, token, 12);
        verify(claim).complete(attempt, token);
        verify(client).copyObject(copyRequest("staging/" + upload, "candidates/" + attempt, "\"etag\""));
    }

    @Test
    void refusesToCopyMismatchedOrOversizedContent() {
        UUID upload = UUID.randomUUID();
        UUID attempt = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        when(client.headObject(headRequest("staging/" + upload))).thenReturn(HeadObjectResponse.builder()
            .contentLength(20L).eTag("etag").build());

        assertThrows(IllegalArgumentException.class,
            () -> storage.copyToCandidate(upload, attempt, token, 12));
        verify(client, never()).copyObject(copyRequest("staging/" + upload, "candidates/" + attempt, "etag"));
    }

    @Test
    void refusesStagingReplacementAfterFinalization() {
        UUID upload = UUID.randomUUID();
        UUID attempt = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        when(claim.start(upload, attempt, token, 12)).thenReturn("original");
        when(client.headObject(headRequest("staging/" + upload))).thenReturn(HeadObjectResponse.builder()
            .contentLength(12L).eTag("replacement").build());

        assertThrows(IllegalStateException.class, () -> storage.copyToCandidate(upload, attempt, token, 12));
        verify(client, never()).copyObject(copyRequest("staging/" + upload, "candidates/" + attempt, "original"));
        verify(claim, never()).complete(attempt, token);
    }

    @Test
    void closingCandidateAbortsTheUpstreamTransfer() throws Exception {
        UUID attempt = UUID.randomUUID();
        @SuppressWarnings("unchecked")
        ResponseInputStream<GetObjectResponse> response = mock(ResponseInputStream.class);
        when(client.getObject(GetObjectRequest.builder().bucket(BUCKET).key("candidates/" + attempt).build()))
            .thenReturn(response);

        storage.openCandidate(attempt).close();

        verify(response).abort();
    }

    @Test
    void rejectsMissingBucketAndOutOfRangeUploadValidity() {
        assertThrows(IllegalArgumentException.class, () -> new PrivateDocumentStorage(client, presigner, null, claim));
        assertThrows(IllegalArgumentException.class, () -> new PrivateDocumentStorage(client, presigner, " ", claim));
        UUID upload = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,
            () -> storage.presignUpload(upload, "application/pdf", Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
            () -> storage.presignUpload(upload, "application/pdf", Duration.ofSeconds(-1)));
    }

    @Test
    void rejectsStagingObjectsWithoutUsableSizeOrEtag() {
        UUID upload = UUID.randomUUID();
        HeadObjectRequest request = headRequest("staging/" + upload);
        when(client.headObject(request)).thenReturn(HeadObjectResponse.builder()
            .contentLength((Long) null).eTag("etag").build());
        assertThrows(IllegalArgumentException.class, () -> storage.headStaging(upload, 12));
        when(client.headObject(request)).thenReturn(HeadObjectResponse.builder()
            .contentLength(0L).eTag("etag").build());
        assertThrows(IllegalArgumentException.class, () -> storage.headStaging(upload, 0));
        long oversized = DocumentContentInspector.MAX_BYTES + 1;
        when(client.headObject(request)).thenReturn(HeadObjectResponse.builder()
            .contentLength(oversized).eTag("etag").build());
        assertThrows(IllegalArgumentException.class, () -> storage.headStaging(upload, oversized));
        when(client.headObject(request)).thenReturn(HeadObjectResponse.builder()
            .contentLength(12L).eTag(" ").build());
        assertThrows(IllegalArgumentException.class, () -> storage.headStaging(upload, 12));
    }

    @Test
    void rejectsObjectKeysOutsideTheCandidatePrefix() {
        UUID attempt = UUID.randomUUID();
        String tooLong = "candidates/" + "a".repeat(510);

        assertThrows(IllegalArgumentException.class, () -> storage.deleteObject(null));
        assertThrows(IllegalArgumentException.class, () -> storage.deleteObject("staging/" + attempt));
        assertThrows(IllegalArgumentException.class, () -> storage.deleteObject("candidates/../secrets"));
        assertThrows(IllegalArgumentException.class, () -> storage.deleteObject(tooLong));
    }

    private static PutObjectPresignRequest presignRequest(UUID upload, Duration validity) {
        return PutObjectPresignRequest.builder().signatureDuration(validity)
            .putObjectRequest(PutObjectRequest.builder().bucket(BUCKET).key("staging/" + upload)
                .contentType("application/pdf").build())
            .build();
    }

    private static HeadObjectRequest headRequest(String key) {
        return HeadObjectRequest.builder().bucket(BUCKET).key(key).build();
    }

    private static CopyObjectRequest copyRequest(String source, String destination, String etag) {
        return CopyObjectRequest.builder().sourceBucket(BUCKET).sourceKey(source)
            .destinationBucket(BUCKET).destinationKey(destination).copySourceIfMatch(etag).build();
    }
}
