package id.mydev.peoplecore.reportexport.infrastructure.storage;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

class ExportArtifactStorageTest {
    @Test
    void uploadsArePrivateImmutableAndBoundToAnUnpredictableCandidateKey() {
        S3Client client=mock(S3Client.class);
        ExportArtifactStorage storage=new ExportArtifactStorage(client,"private-reports");
        UUID candidate=UUID.randomUUID();
        storage.upload(candidate,"\"status\"\r\n".getBytes(StandardCharsets.UTF_8));
        ArgumentCaptor<PutObjectRequest> request=ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(request.capture(),any(RequestBody.class));
        assertEquals("private-reports",request.getValue().bucket());
        assertEquals("exports/candidates/"+candidate,request.getValue().key());
        assertEquals("*",request.getValue().ifNoneMatch());
        assertEquals("text/csv; charset=utf-8",request.getValue().contentType());
        assertEquals(null,request.getValue().acl());
        storage.delete(candidate);
        verify(client).deleteObject(DeleteObjectRequest.builder().bucket("private-reports").key("exports/candidates/"+candidate).build());
    }

    @Test
    void missingBucketAndCandidateAreRejectedBeforeProviderIo() {
        S3Client client=mock(S3Client.class);
        assertThrows(IllegalArgumentException.class,()->new ExportArtifactStorage(client," "));
        ExportArtifactStorage storage=new ExportArtifactStorage(client,"private-reports");
        assertThrows(NullPointerException.class,()->storage.delete(null));
    }

    @Test
    void uncertainUploadNeedsMatchingPositiveProviderEvidence() {
        S3Client client=mock(S3Client.class);
        UUID candidate=UUID.randomUUID();
        ExportArtifactStorage storage=new ExportArtifactStorage(client,"private-reports");
        String digest="a".repeat(64);
        HeadObjectRequest request=HeadObjectRequest.builder().bucket("private-reports").key("exports/candidates/"+candidate).build();
        when(client.headObject(request)).thenReturn(HeadObjectResponse.builder().contentLength(5L).metadata(Map.of("sha256",digest)).build());
        assertTrue(storage.confirmed(candidate,5,digest));
        assertFalse(storage.confirmed(candidate,6,digest));
        assertFalse(storage.confirmed(candidate,5,"b".repeat(64)));
        doThrow(S3Exception.builder().statusCode(404).build()).when(client).headObject(request);
        assertFalse(storage.confirmed(candidate,5,digest));
        doThrow(S3Exception.builder().statusCode(503).build()).when(client).headObject(request);
        assertThrows(S3Exception.class,()->storage.confirmed(candidate,5,digest));
    }
}
