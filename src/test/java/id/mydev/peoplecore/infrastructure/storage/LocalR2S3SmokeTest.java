package id.mydev.peoplecore.infrastructure.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@EnabledIfEnvironmentVariable(named = "PEOPLECORE_R2_LOCAL_S3_ENDPOINT", matches = ".+")
class LocalR2S3SmokeTest {

	private static final String ACCESS_KEY_ID = "peoplecore-local-access-key";
	private static final String BUCKET = "peoplecore-local-documents";
	private static final String CONTENT = "peoplecore-local-r2-smoke";
	private static final String ENDPOINT_ENVIRONMENT_VARIABLE = "PEOPLECORE_R2_LOCAL_S3_ENDPOINT";
	private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);
	private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
	private static final String SECRET_ACCESS_KEY = "peoplecore-local-secret-key";

	@Test
	void uploadsCopiesAndDownloadsWithPresignedUrls() throws IOException, InterruptedException {
		String objectPrefix = "smoke/" + UUID.randomUUID();
		String sourceKey = objectPrefix + "/source.txt";
		String targetKey = objectPrefix + "/target.txt";

		try (S3Client client = createS3Client(); S3Presigner presigner = createS3Presigner()) {
			PresignedPutObjectRequest upload = presigner.presignPutObject(PutObjectPresignRequest.builder()
					.signatureDuration(Duration.ofMinutes(1))
					.putObjectRequest(putRequest(sourceKey))
					.build());
			assertSuccessful(send(upload, HttpRequest.BodyPublishers.ofString(CONTENT)));
			assertEquals(CONTENT, client.getObjectAsBytes(getRequest(sourceKey)).asUtf8String());

			client.copyObject(CopyObjectRequest.builder()
					.sourceBucket(BUCKET)
					.sourceKey(sourceKey)
					.destinationBucket(BUCKET)
					.destinationKey(targetKey)
					.build());
			assertEquals(CONTENT, client.getObjectAsBytes(getRequest(targetKey)).asUtf8String());

			PresignedGetObjectRequest download = presigner.presignGetObject(GetObjectPresignRequest.builder()
					.signatureDuration(Duration.ofMinutes(1))
					.getObjectRequest(getRequest(targetKey))
					.build());
			HttpResponse<String> response = send(download);
			assertSuccessful(response);
			assertEquals(CONTENT, response.body());

			client.deleteObject(deleteRequest(sourceKey));
			client.deleteObject(deleteRequest(targetKey));
		}
	}

	private HttpResponse<String> send(PresignedPutObjectRequest request, HttpRequest.BodyPublisher body)
			throws IOException, InterruptedException {
		return HTTP_CLIENT.send(HttpRequest.newBuilder(uri(request.url().toString())).timeout(HTTP_TIMEOUT).PUT(body).build(),
				HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> send(PresignedGetObjectRequest request)
			throws IOException, InterruptedException {
		return HTTP_CLIENT.send(HttpRequest.newBuilder(uri(request.url().toString())).timeout(HTTP_TIMEOUT).GET().build(),
				HttpResponse.BodyHandlers.ofString());
	}

	private void assertSuccessful(HttpResponse<String> response) {
		assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
				() -> "Unexpected status: " + response.statusCode() + ", response: " + response.body());
	}

	private S3Client createS3Client() {
		return S3Client.builder()
				.endpointOverride(localEndpoint())
				.region(Region.of("auto"))
				.credentialsProvider(localCredentials())
				.serviceConfiguration(localS3Configuration())
				.build();
	}

	private S3Presigner createS3Presigner() {
		return S3Presigner.builder()
				.endpointOverride(localEndpoint())
				.region(Region.of("auto"))
				.credentialsProvider(localCredentials())
				.serviceConfiguration(localS3Configuration())
				.build();
	}

	private StaticCredentialsProvider localCredentials() {
		return StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY_ID, SECRET_ACCESS_KEY));
	}

	private S3Configuration localS3Configuration() {
		return S3Configuration.builder().pathStyleAccessEnabled(true).build();
	}

	private URI localEndpoint() {
		return uri(System.getenv(ENDPOINT_ENVIRONMENT_VARIABLE));
	}

	private URI uri(String value) {
		return URI.create(value);
	}

	private PutObjectRequest putRequest(String key) {
		return PutObjectRequest.builder().bucket(BUCKET).key(key).build();
	}

	private GetObjectRequest getRequest(String key) {
		return GetObjectRequest.builder().bucket(BUCKET).key(key).build();
	}

	private DeleteObjectRequest deleteRequest(String key) {
		return DeleteObjectRequest.builder().bucket(BUCKET).key(key).build();
	}
}
