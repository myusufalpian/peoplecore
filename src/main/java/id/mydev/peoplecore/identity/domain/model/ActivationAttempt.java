package id.mydev.peoplecore.identity.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "activation_attempts")
public class ActivationAttempt {

    public static final String OUTCOME_REJECTED = "REJECTED";
    public static final String OUTCOME_THROTTLED = "THROTTLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "invitation_public_id", nullable = false)
    private UUID invitationPublicId;

    @Column(name = "principal_issuer", nullable = false, length = 255)
    private String principalIssuer;

    @Column(name = "principal_subject", nullable = false, length = 255)
    private String principalSubject;

    @Column(name = "outcome", nullable = false, length = 32)
    private String outcome;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ActivationAttempt() {
    }

    public ActivationAttempt(UUID invitationPublicId, String principalIssuer, String principalSubject,
                             String outcome, Instant createdAt) {
        this.invitationPublicId = Objects.requireNonNull(invitationPublicId, "invitationPublicId must not be null");
        this.principalIssuer = Objects.requireNonNull(principalIssuer, "principalIssuer must not be null");
        this.principalSubject = Objects.requireNonNull(principalSubject, "principalSubject must not be null");
        this.outcome = Objects.requireNonNull(outcome, "outcome must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public Long getId() {
        return id;
    }

    public UUID getInvitationPublicId() {
        return invitationPublicId;
    }

    public String getPrincipalIssuer() {
        return principalIssuer;
    }

    public String getPrincipalSubject() {
        return principalSubject;
    }

    public String getOutcome() {
        return outcome;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
