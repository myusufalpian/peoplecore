package id.mydev.peoplecore.reportexport.infrastructure.storage;

import java.io.FilterInputStream;
import java.io.InputStream;
import java.util.Objects;
import java.util.UUID;
import java.util.Map;
import java.util.HexFormat;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

public class ExportArtifactStorage {
    private final S3Client client;
    private final String bucket;

    public ExportArtifactStorage(S3Client client, String bucket) {
        this.client = Objects.requireNonNull(client, "client");
        if (bucket == null || bucket.isBlank()) throw new IllegalArgumentException("Private export bucket is required");
        this.bucket = bucket;
    }

    public void upload(UUID candidate, byte[] content) {
        client.putObject(PutObjectRequest.builder().bucket(bucket).key(key(candidate))
            .contentType("text/csv; charset=utf-8").metadata(Map.of("sha256",digest(content))).ifNoneMatch("*").build(), RequestBody.fromBytes(content));
    }

    public boolean confirmed(UUID candidate,long byteSize,String digest) {
        try {
            var object=client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key(candidate)).build());
            return Objects.equals(object.contentLength(),byteSize) && digest.equals(object.metadata().get("sha256"));
        } catch(S3Exception exception) {
            if(exception.statusCode()==404) return false;
            throw exception;
        }
    }

    public InputStream open(UUID candidate) {
        var response = client.getObject(GetObjectRequest.builder().bucket(bucket).key(key(candidate)).build());
        return new FilterInputStream(response) {
            @Override
            public void close() { response.abort(); }
        };
    }

    public void delete(UUID candidate) {
        client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key(candidate)).build());
    }

    private static String key(UUID candidate) { return "exports/candidates/" + Objects.requireNonNull(candidate, "candidate"); }

    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch(NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 unavailable",exception); }
    }
}
