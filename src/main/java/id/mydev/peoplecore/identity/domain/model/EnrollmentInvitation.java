package id.mydev.peoplecore.identity.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "enrollment_invitations")
public class EnrollmentInvitation {

    public static final String STATUS_ISSUED = "ISSUED";
    public static final String STATUS_CONSUMED = "CONSUMED";
    public static final String STATUS_REVOKED = "REVOKED";

    public static final String DELIVERY_PENDING = "PENDING";
    public static final String DELIVERY_SENT = "SENT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true)
    private UUID publicId;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "intended_identity_ref", nullable = false, length = 255)
    private String intendedIdentityRef;

    @Column(name = "expected_issuer", nullable = false, length = 255)
    private String expectedIssuer;

    @Column(name = "expected_subject", nullable = false, length = 255)
    private String expectedSubject;

    @Column(name = "secret_hash", nullable = false, length = 64)
    private String secretHash;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "delivery_status", nullable = false, length = 32)
    private String deliveryStatus;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected EnrollmentInvitation() {
    }

    public EnrollmentInvitation(UUID publicId, Long employeeId, String intendedIdentityRef,
                                String expectedIssuer, String expectedSubject,
                                String secretHash, Instant expiresAt, Instant issuedAt) {
        this.publicId = Objects.requireNonNull(publicId, "publicId must not be null");
        this.employeeId = Objects.requireNonNull(employeeId, "employeeId must not be null");
        this.intendedIdentityRef = Objects.requireNonNull(intendedIdentityRef, "intendedIdentityRef must not be null");
        if (expectedIssuer == null || expectedIssuer.isBlank()) {
            throw new IllegalArgumentException("expectedIssuer must not be blank");
        }
        if (expectedSubject == null || expectedSubject.isBlank()) {
            throw new IllegalArgumentException("expectedSubject must not be blank");
        }
        this.expectedIssuer = expectedIssuer;
        this.expectedSubject = expectedSubject;
        this.secretHash = Objects.requireNonNull(secretHash, "secretHash must not be null");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        this.issuedAt = Objects.requireNonNull(issuedAt, "issuedAt must not be null");
        this.status = STATUS_ISSUED;
        this.deliveryStatus = DELIVERY_PENDING;
    }

    public boolean isUsableAt(Instant now) {
        return STATUS_ISSUED.equals(status) && now.isBefore(expiresAt);
    }

    public void consume(Instant now) {
        this.status = STATUS_CONSUMED;
        this.consumedAt = Objects.requireNonNull(now, "now must not be null");
    }

    public void revoke(Instant now) {
        if (STATUS_ISSUED.equals(status)) {
            this.revokedAt = Objects.requireNonNull(now, "now must not be null");
            this.status = STATUS_REVOKED;
        }
    }

    public void markDeliverySent() {
        this.deliveryStatus = DELIVERY_SENT;
    }

    public Long getId() {
        return id;
    }

    public UUID getPublicId() {
        return publicId;
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public String getIntendedIdentityRef() {
        return intendedIdentityRef;
    }

    public String getExpectedIssuer() {
        return expectedIssuer;
    }

    public String getExpectedSubject() {
        return expectedSubject;
    }

    public String getSecretHash() {
        return secretHash;
    }

    public String getStatus() {
        return status;
    }

    public String getDeliveryStatus() {
        return deliveryStatus;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }

    public Instant getRevokedAt() { return revokedAt; }
}
