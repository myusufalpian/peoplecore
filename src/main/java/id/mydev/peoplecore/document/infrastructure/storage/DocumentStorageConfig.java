package id.mydev.peoplecore.document.infrastructure.storage;

import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "peoplecore.documents.storage", name = {
    "endpoint", "bucket", "region", "api-timeout", "attempt-timeout"
})
public class DocumentStorageConfig {
    @Bean(destroyMethod = "close")
    S3Client documentS3Client(@Value("${peoplecore.documents.storage.endpoint}") URI endpoint,
                              @Value("${peoplecore.documents.storage.region}") String region,
                              @Value("${peoplecore.documents.storage.api-timeout}") String apiTimeoutValue,
                              @Value("${peoplecore.documents.storage.attempt-timeout}") String attemptTimeoutValue) {
        Duration apiTimeout = Duration.parse(apiTimeoutValue);
        Duration attemptTimeout = Duration.parse(attemptTimeoutValue);
        requireTimeouts(apiTimeout, attemptTimeout);
        requireEndpoint(endpoint);
        return S3Client.builder().endpointOverride(endpoint).region(Region.of(region))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .overrideConfiguration(ClientOverrideConfiguration.builder()
                .apiCallTimeout(apiTimeout).apiCallAttemptTimeout(attemptTimeout)
                .retryStrategy(AwsRetryStrategy.doNotRetry()).build())
            .build();
    }

    @Bean(destroyMethod = "close")
    S3Presigner documentS3Presigner(@Value("${peoplecore.documents.storage.endpoint}") URI endpoint,
                                    @Value("${peoplecore.documents.storage.region}") String region) {
        requireEndpoint(endpoint);
        return S3Presigner.builder().endpointOverride(endpoint).region(Region.of(region))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).build();
    }

    @Bean
    PrivateDocumentStorage privateDocumentStorage(@Qualifier("documentS3Client") S3Client client,
                                                   @Qualifier("documentS3Presigner") S3Presigner presigner,
                                                   @Value("${peoplecore.documents.storage.bucket}") String bucket,
                                                   JdbcTemplate jdbc, PlatformTransactionManager manager) {
        return new PrivateDocumentStorage(client, presigner, bucket, new DocumentCopyClaim(jdbc, manager));
    }

    private static void requireTimeouts(Duration api, Duration attempt) {
        if (api.isNegative() || api.isZero() || attempt.isNegative() || attempt.isZero() || attempt.compareTo(api) > 0) {
            throw new IllegalArgumentException("Storage timeouts must be positive and attempt timeout cannot exceed API timeout");
        }
    }

    private static void requireEndpoint(URI endpoint) {
        String host = endpoint.getHost();
        boolean loopback = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host)
            || "::1".equals(host);
        if (host == null || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null
            || !("https".equalsIgnoreCase(endpoint.getScheme())
                || (loopback && "http".equalsIgnoreCase(endpoint.getScheme())))) {
            throw new IllegalArgumentException("Document storage endpoint must use HTTPS or a local loopback endpoint");
        }
    }
}
