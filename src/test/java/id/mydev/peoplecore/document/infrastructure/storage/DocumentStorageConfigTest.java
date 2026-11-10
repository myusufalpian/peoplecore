package id.mydev.peoplecore.document.infrastructure.storage;

import java.net.URI;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DocumentStorageConfigTest {
    private final DocumentStorageConfig config = new DocumentStorageConfig();

    @Test
    void rejectsInsecureRemoteEndpointsBeforeCreatingAClient() {
        assertThrows(IllegalArgumentException.class, () -> config.documentS3Client(
            URI.create("http://storage.example"), "auto", "PT5S", "PT1S"));
        assertThrows(IllegalArgumentException.class, () -> config.documentS3Presigner(
            URI.create("https://user:password@storage.example"), "auto"));
    }

    @Test
    void rejectsUnboundedOrInvertedTimeouts() {
        URI endpoint = URI.create("https://storage.example");
        assertThrows(IllegalArgumentException.class, () -> config.documentS3Client(
            endpoint, "auto", "PT0S", "PT1S"));
        assertThrows(IllegalArgumentException.class, () -> config.documentS3Client(
            endpoint, "auto", "PT1S", "PT2S"));
    }

    @Test
    void rejectsEndpointsWithCredentialsOrAdditionalUrlComponents() {
        for (String endpoint : new String[]{"https://storage.example?token=value", "https://storage.example#fragment",
            "ftp://localhost:9000", "https:///missing-host"}) {
            assertThrows(IllegalArgumentException.class,
                () -> config.documentS3Presigner(URI.create(endpoint), "auto"));
        }
    }

    @Test
    void rejectsNonPositiveAttemptTimeout() {
        URI endpoint = URI.create("https://storage.example");
        assertThrows(IllegalArgumentException.class,
            () -> config.documentS3Client(endpoint, "auto", "PT5S", "PT0S"));
        assertThrows(IllegalArgumentException.class,
            () -> config.documentS3Client(endpoint, "auto", "PT5S", "-PT1S"));
    }

    @Test
    void storageClientDoesNotRetryACopyWithAnUncertainOutcome() {
        try (var client = config.documentS3Client(URI.create("http://localhost:9000"), "auto", "PT5S", "PT1S")) {
            assertEquals(1, client.serviceClientConfiguration().overrideConfiguration().retryStrategy().orElseThrow().maxAttempts());
        }
    }
}
