package id.mydev.peoplecore.document.infrastructure.scanner;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Verifies fresh evidence supplied by a separately trusted scanner supervisor. */
public final class SignedScannerReadiness implements ClamAvScanner.ScannerReadiness {
    private static final String DOMAIN = "peoplecore-scanner-readiness-v1";
    private final EvidenceSource source;
    private final PublicKey trustKey;
    private final String audience;
    private final UUID pod;
    private final Duration maxEvidenceAge;
    private final Duration maxDefinitionAge;
    private final Clock clock;

    public SignedScannerReadiness(EvidenceSource source, PublicKey trustKey, String audience, UUID pod,
                                  Duration maxEvidenceAge, Duration maxDefinitionAge, Clock clock) {
        this.source = Objects.requireNonNull(source, "source");
        this.trustKey = Objects.requireNonNull(trustKey, "trustKey");
        this.audience = Objects.requireNonNull(audience, "audience");
        this.pod = Objects.requireNonNull(pod, "pod");
        this.maxEvidenceAge = positive(maxEvidenceAge);
        this.maxDefinitionAge = positive(maxDefinitionAge);
        this.clock = Objects.requireNonNull(clock, "clock");
        if (!hasText(audience) || !("Ed25519".equalsIgnoreCase(trustKey.getAlgorithm())
            || "EdDSA".equalsIgnoreCase(trustKey.getAlgorithm()))) {
            throw new IllegalArgumentException("Invalid scanner trust configuration");
        }
    }

    @Override
    public ClamAvScanner.ScannerStatus current() throws IOException {
        UUID challenge = UUID.randomUUID();
        SignedEvidence response = source.current(challenge);
        if (response == null || response.evidence() == null || response.signature() == null) {
            throw unavailable();
        }
        Evidence evidence = response.evidence();
        Instant now = clock.instant();
        if (!challenge.equals(evidence.challenge()) || !audience.equals(evidence.audience())
            || !pod.equals(evidence.pod()) || evidence.generation() == null
            || !hasText(evidence.engine()) || !hasText(evidence.definitions())
            || !digest(evidence.snapshotDigest()) || !digest(evidence.configDigest())
            || evidence.issuedAt() == null || evidence.expiresAt() == null || evidence.definitionsAt() == null
            || evidence.issuedAt().isAfter(now) || evidence.definitionsAt().isAfter(now)
            || !now.isBefore(evidence.expiresAt()) || !evidence.issuedAt().isBefore(evidence.expiresAt())
            || Duration.between(evidence.issuedAt(), now).compareTo(maxEvidenceAge) > 0
            || Duration.between(evidence.issuedAt(), evidence.expiresAt()).compareTo(maxEvidenceAge) > 0
            || Duration.between(evidence.definitionsAt(), now).compareTo(maxDefinitionAge) > 0
            || !evidence.encryptedAlert() || !evidence.limitAlert()) {
            throw unavailable();
        }
        verify(evidence, response.signature());
        Instant definitionExpiry = evidence.definitionsAt().plus(maxDefinitionAge);
        Instant validUntil = evidence.expiresAt().isBefore(definitionExpiry) ? evidence.expiresAt() : definitionExpiry;
        if (!now.isBefore(validUntil)) {
            throw unavailable();
        }
        return new ClamAvScanner.ScannerStatus(evidence.generation(), evidence.configDigest(), evidence.snapshotDigest(),
            evidence.engine(), evidence.definitions(),
            validUntil, true, true);
    }

    private void verify(Evidence evidence, byte[] signature) throws IOException {
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(trustKey);
            verifier.update(canonical(evidence));
            if (!verifier.verify(signature)) {
                throw unavailable();
            }
        } catch (GeneralSecurityException exception) {
            throw new IOException("Scanner evidence signature is invalid", exception);
        }
    }

    static byte[] canonical(Evidence e) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            for (String value : new String[]{DOMAIN, e.challenge().toString(), e.audience(), e.pod().toString(),
                e.generation().toString(), e.engine(), e.definitions(), e.snapshotDigest(), e.configDigest(),
                e.issuedAt().toString(), e.expiresAt().toString(), e.definitionsAt().toString()}) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                out.writeInt(bytes.length);
                out.write(bytes);
            }
            out.writeBoolean(e.encryptedAlert());
            out.writeBoolean(e.limitAlert());
        }
        return buffer.toByteArray();
    }

    private static boolean digest(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank() && value.length() <= 255;
    }

    private static Duration positive(Duration value) {
        Objects.requireNonNull(value, "duration");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("Evidence duration must be positive");
        }
        return value;
    }

    private static IOException unavailable() {
        return new IOException("Scanner evidence is unavailable or invalid");
    }

    @FunctionalInterface
    public interface EvidenceSource {
        SignedEvidence current(UUID challenge) throws IOException;
    }

    public record Evidence(UUID challenge, String audience, UUID pod, UUID generation, String engine,
                           String definitions, String snapshotDigest, String configDigest, Instant issuedAt,
                           Instant expiresAt, Instant definitionsAt, boolean encryptedAlert, boolean limitAlert) {
    }

    public record SignedEvidence(Evidence evidence, byte[] signature) {
        public SignedEvidence {
            signature = signature == null ? null : signature.clone();
        }

        @Override
        public byte[] signature() {
            return signature == null ? null : signature.clone();
        }
    }
}
