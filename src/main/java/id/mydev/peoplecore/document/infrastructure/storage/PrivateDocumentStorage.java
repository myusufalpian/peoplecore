package id.mydev.peoplecore.document.infrastructure.storage;

import id.mydev.peoplecore.document.application.service.DocumentContentInspector;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.io.FilterInputStream;
import java.io.InputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

public final class PrivateDocumentStorage {
    private static final Duration MAX_UPLOAD_VALIDITY = Duration.ofMinutes(5);
    private final S3Client client;
    private final S3Presigner presigner;
    private final String bucket;
    private final DocumentCopyClaim copyClaim;

    public PrivateDocumentStorage(S3Client client, S3Presigner presigner, String bucket, DocumentCopyClaim copyClaim) {
        this.client = Objects.requireNonNull(client, "client");
        this.presigner = Objects.requireNonNull(presigner, "presigner");
        this.copyClaim = Objects.requireNonNull(copyClaim, "copyClaim");
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("Bucket is required");
        }
        this.bucket = bucket;
    }

    public PresignedPutObjectRequest presignUpload(UUID uploadId, String mediaType, Duration validity) {
        Objects.requireNonNull(uploadId, "uploadId");
        Objects.requireNonNull(validity, "validity");
        if (validity.isNegative() || validity.isZero() || validity.compareTo(MAX_UPLOAD_VALIDITY) > 0) {
            throw new IllegalArgumentException("Upload validity must be between zero and five minutes");
        }
        PutObjectRequest request = PutObjectRequest.builder()
            .bucket(bucket)
            .key(stagingKey(uploadId))
            .contentType(Objects.requireNonNull(mediaType, "mediaType"))
            .build();
        return presigner.presignPutObject(PutObjectPresignRequest.builder()
            .signatureDuration(validity).putObjectRequest(request).build());
    }

    public String copyToCandidate(UUID uploadId, UUID attemptId, UUID leaseToken, long declaredSize) {
        String source = stagingKey(Objects.requireNonNull(uploadId, "uploadId"));
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(leaseToken, "leaseToken");
        String expectedEtag = copyClaim.start(uploadId, attemptId, leaseToken, declaredSize);
        StagingObject object = headStaging(uploadId, declaredSize);
        if (!object.etag().equals(expectedEtag)) {
            throw new IllegalStateException("Uploaded content changed after finalization");
        }
        String candidate = "candidates/" + attemptId;
        client.copyObject(CopyObjectRequest.builder()
            .sourceBucket(bucket).sourceKey(source)
            .destinationBucket(bucket).destinationKey(candidate)
            .copySourceIfMatch(object.etag()).build());
        copyClaim.complete(attemptId, leaseToken);
        return candidate;
    }

    public StagingObject headStaging(UUID uploadId, long declaredSize) {
        String source = stagingKey(Objects.requireNonNull(uploadId, "uploadId"));
        HeadObjectResponse object = client.headObject(HeadObjectRequest.builder().bucket(bucket).key(source).build());
        Long size = object.contentLength();
        String etag = object.eTag();
        if (size == null || size < 1 || size > DocumentContentInspector.MAX_BYTES || size != declaredSize
            || etag == null || etag.isBlank()) {
            throw new IllegalArgumentException("Uploaded content does not match the declared size");
        }
        return new StagingObject(size, etag);
    }

    public InputStream openCandidate(UUID attemptId) {
        var response = client.getObject(GetObjectRequest.builder().bucket(bucket)
            .key("candidates/" + Objects.requireNonNull(attemptId, "attemptId")).build());
        return new FilterInputStream(response) {
            @Override
            public void close() {
                response.abort();
            }
        };
    }

    public void deleteCandidate(UUID attemptId) {
        deleteObject("candidates/" + Objects.requireNonNull(attemptId, "attemptId"));
    }

    public void deleteObject(String key) {
        if (key == null || key.length() > 512 || !key.startsWith("candidates/") || key.contains("..")) {
            throw new IllegalArgumentException("Document object key is outside the candidate prefix");
        }
        client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    private static String stagingKey(UUID uploadId) {
        return "staging/" + uploadId;
    }

    public record StagingObject(long byteSize, String etag) {
    }
}
