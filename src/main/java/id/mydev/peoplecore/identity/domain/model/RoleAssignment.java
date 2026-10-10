package id.mydev.peoplecore.identity.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "role_assignments")
public class RoleAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "role", nullable = false, length = 32)
    private String role;

    @Column(name = "scope", length = 128)
    private String scope;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    @Column(name = "effective_to")
    private Instant effectiveTo;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RoleAssignment() {
    }

    public RoleAssignment(Long accountId, String role, String scope, Instant effectiveFrom, Instant createdAt) {
        this.accountId = Objects.requireNonNull(accountId, "accountId must not be null");
        this.role = Objects.requireNonNull(role, "role must not be null");
        this.scope = scope;
        this.effectiveFrom = Objects.requireNonNull(effectiveFrom, "effectiveFrom must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public boolean isEffectiveAt(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return !effectiveFrom.isAfter(now) && (effectiveTo == null || now.isBefore(effectiveTo));
    }

    public void endAt(Instant effectiveTo) {
        this.effectiveTo = Objects.requireNonNull(effectiveTo, "effectiveTo must not be null");
    }

    public Long getId() {
        return id;
    }

    public Long getAccountId() {
        return accountId;
    }

    public String getRole() {
        return role;
    }

    public String getScope() {
        return scope;
    }

    public Instant getEffectiveFrom() {
        return effectiveFrom;
    }

    public Instant getEffectiveTo() {
        return effectiveTo;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
