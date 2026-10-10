package id.mydev.peoplecore.identity.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "user_accounts")
public class UserAccount {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_INACTIVE = "INACTIVE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true)
    private UUID publicId;

    @Column(name = "oidc_issuer", nullable = false, length = 255)
    private String oidcIssuer;

    @Column(name = "oidc_subject", nullable = false, length = 255)
    private String oidcSubject;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "access_ends_at")
    private Instant accessEndsAt;

    @Column(name = "cutoff_time")
    private LocalTime cutoffTime;

    @Column(name = "cutoff_timezone", length = 64)
    private String cutoffTimezone;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected UserAccount() {
    }

    public UserAccount(UUID publicId, String oidcIssuer, String oidcSubject, Instant createdAt) {
        this.publicId = Objects.requireNonNull(publicId, "publicId must not be null");
        this.oidcIssuer = Objects.requireNonNull(oidcIssuer, "oidcIssuer must not be null");
        this.oidcSubject = Objects.requireNonNull(oidcSubject, "oidcSubject must not be null");
        this.status = STATUS_ACTIVE;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public boolean isActiveAt(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return STATUS_ACTIVE.equals(status) && (accessEndsAt == null || now.isBefore(accessEndsAt));
    }

    public void applyCutoff(Instant accessEndsAt, LocalTime cutoffTime, String cutoffTimezone, Instant now) {
        this.accessEndsAt = Objects.requireNonNull(accessEndsAt, "accessEndsAt must not be null");
        this.cutoffTime = cutoffTime;
        this.cutoffTimezone = cutoffTimezone;
        if (!isActiveAt(Objects.requireNonNull(now, "now must not be null"))) {
            this.status = STATUS_INACTIVE;
        }
    }

    public void deactivate() {
        this.status = STATUS_INACTIVE;
    }

    public void reactivate() {
        this.status = STATUS_ACTIVE;
        this.accessEndsAt = null;
    }

    public void recordLogin(Instant now) {
        this.lastLoginAt = Objects.requireNonNull(now, "now must not be null");
    }

    public Long getId() {
        return id;
    }

    public UUID getPublicId() {
        return publicId;
    }

    public String getOidcIssuer() {
        return oidcIssuer;
    }

    public String getOidcSubject() {
        return oidcSubject;
    }

    public String getStatus() {
        return status;
    }

    public Instant getAccessEndsAt() {
        return accessEndsAt;
    }

    public LocalTime getCutoffTime() {
        return cutoffTime;
    }

    public String getCutoffTimezone() {
        return cutoffTimezone;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
