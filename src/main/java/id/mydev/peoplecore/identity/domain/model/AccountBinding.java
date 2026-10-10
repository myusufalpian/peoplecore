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
@Table(name = "account_bindings")
public class AccountBinding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "employee_public_id", nullable = false)
    private UUID employeePublicId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "oidc_issuer", nullable = false, length = 255)
    private String oidcIssuer;

    @Column(name = "oidc_subject", nullable = false, length = 255)
    private String oidcSubject;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected AccountBinding() {
    }

    public AccountBinding(Long employeeId, UUID employeePublicId, Long accountId,
                          String oidcIssuer, String oidcSubject, Instant createdAt) {
        this.employeeId = Objects.requireNonNull(employeeId, "employeeId must not be null");
        this.employeePublicId = Objects.requireNonNull(employeePublicId, "employeePublicId must not be null");
        this.accountId = Objects.requireNonNull(accountId, "accountId must not be null");
        this.oidcIssuer = Objects.requireNonNull(oidcIssuer, "oidcIssuer must not be null");
        this.oidcSubject = Objects.requireNonNull(oidcSubject, "oidcSubject must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public boolean isActive() {
        return revokedAt == null;
    }

    public void revoke(Instant now) {
        this.revokedAt = Objects.requireNonNull(now, "now must not be null");
    }

    public Long getId() {
        return id;
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public UUID getEmployeePublicId() {
        return employeePublicId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public String getOidcIssuer() {
        return oidcIssuer;
    }

    public String getOidcSubject() {
        return oidcSubject;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }
}
