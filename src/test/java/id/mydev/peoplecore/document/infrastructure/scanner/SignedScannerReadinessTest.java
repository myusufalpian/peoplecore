package id.mydev.peoplecore.document.infrastructure.scanner;

import java.io.IOException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SignedScannerReadinessTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final UUID POD = UUID.randomUUID();
    private static final UUID GENERATION = UUID.randomUUID();
    private static final String DIGEST = "a".repeat(64);

    @Test
    void acceptsFreshEvidenceSignedForThisPodAndChallenge() throws Exception {
        KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var readiness = readiness(keys, challenge -> sign(keys, evidence(challenge, POD, NOW.minusSeconds(1), true)));
        assertEquals(GENERATION, readiness.current().generation());
    }

    @Test
    void rejectsEvidenceForAnotherPodOrStaleDefinitionsOrUnsafeConfiguration() throws Exception {
        KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        assertThrows(IOException.class, () -> readiness(keys,
            challenge -> sign(keys, evidence(challenge, UUID.randomUUID(), NOW.minusSeconds(1), true))).current());
        assertThrows(IOException.class, () -> readiness(keys,
            challenge -> sign(keys, evidence(challenge, POD, NOW.minusSeconds(3601), true))).current());
        assertThrows(IOException.class, () -> readiness(keys,
            challenge -> sign(keys, evidence(challenge, POD, NOW.minusSeconds(1), false))).current());
    }

    @Test
    void rejectsReplayAndSignatureFromAnotherIssuer() throws Exception {
        KeyPair trusted = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair attacker = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var replay = sign(trusted, evidence(UUID.randomUUID(), POD, NOW.minusSeconds(1), true));
        assertThrows(IOException.class, () -> readiness(trusted, challenge -> replay).current());
        assertThrows(IOException.class, () -> readiness(trusted,
            challenge -> sign(attacker, evidence(challenge, POD, NOW.minusSeconds(1), true))).current());
    }

    @Test
    void rejectsExpiredFutureDatedAndOverlongAssertions() throws Exception {
        KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        for (Instant[] times : new Instant[][] {
            {NOW.minusSeconds(10), NOW},
            {NOW.plusSeconds(1), NOW.plusSeconds(10)},
            {NOW.minusSeconds(31), NOW.plusSeconds(1)},
            {NOW.minusSeconds(1), NOW.plusSeconds(31)}
        }) {
            assertThrows(IOException.class, () -> readiness(keys, challenge -> sign(keys,
                new SignedScannerReadiness.Evidence(challenge, "document-worker", POD, GENERATION, "clamav", "test-db",
                    DIGEST, DIGEST, times[0], times[1], NOW.minusSeconds(1), true, true))).current());
        }
    }

    @Test
    void rejectsWrongAudienceMissingGenerationAndChangedDigests() throws Exception {
        KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        assertThrows(IOException.class, () -> readiness(keys, challenge -> sign(keys,
            new SignedScannerReadiness.Evidence(challenge, "another-service", POD, GENERATION, "clamav", "test-db",
                DIGEST, DIGEST, NOW.minusSeconds(1), NOW.plusSeconds(10), NOW.minusSeconds(1), true, true))).current());
        assertThrows(IOException.class, () -> readiness(keys, challenge ->
            new SignedScannerReadiness.SignedEvidence(new SignedScannerReadiness.Evidence(challenge, "document-worker", POD,
                null, "clamav", "test-db", DIGEST, DIGEST, NOW.minusSeconds(1), NOW.plusSeconds(10), NOW.minusSeconds(1),
                true, true), new byte[64])).current());
        assertThrows(IOException.class, () -> readiness(keys, challenge -> {
            var original = evidence(challenge, POD, NOW.minusSeconds(1), true);
            var signed = sign(keys, original);
            var changed = new SignedScannerReadiness.Evidence(challenge, "document-worker", POD, GENERATION, "clamav",
                "test-db", "b".repeat(64), DIGEST, original.issuedAt(), original.expiresAt(), original.definitionsAt(), true, true);
            return new SignedScannerReadiness.SignedEvidence(changed, signed.signature());
        }).current());
    }

    @Test
    void rejectsUnavailableSupervisorAndInvalidConfiguration() throws Exception {
        KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        assertThrows(IOException.class, () -> readiness(keys, challenge -> null).current());
        assertThrows(IOException.class, () -> readiness(keys, challenge -> {
            throw new IOException("Supervisor unavailable");
        }).current());
        assertThrows(IllegalArgumentException.class, () -> new SignedScannerReadiness(challenge -> null, keys.getPublic(),
            "document-worker", POD, Duration.ZERO, Duration.ofHours(1), Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    private static SignedScannerReadiness readiness(KeyPair keys, SignedScannerReadiness.EvidenceSource source) {
        return new SignedScannerReadiness(source, keys.getPublic(), "document-worker", POD,
            Duration.ofSeconds(30), Duration.ofHours(1), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static SignedScannerReadiness.Evidence evidence(UUID challenge, UUID pod, Instant definitionsAt,
                                                              boolean alert) {
        return new SignedScannerReadiness.Evidence(challenge, "document-worker", pod, GENERATION, "clamav",
            "test-db", DIGEST, DIGEST, NOW.minusSeconds(1), NOW.plusSeconds(10), definitionsAt, alert, true);
    }

    private static SignedScannerReadiness.SignedEvidence sign(KeyPair keys, SignedScannerReadiness.Evidence evidence)
        throws IOException {
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(keys.getPrivate());
            signer.update(SignedScannerReadiness.canonical(evidence));
            return new SignedScannerReadiness.SignedEvidence(evidence, signer.sign());
        } catch (java.security.GeneralSecurityException exception) {
            throw new IOException(exception);
        }
    }
}
