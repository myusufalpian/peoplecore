package id.mydev.peoplecore.organization.domain.model;

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
@Table(name = "profile_decision")
public class ProfileDecision {

    public static final String ACTION_APPROVE = "APPROVE";
    public static final String ACTION_REJECT = "REJECT";
    public static final String ACTION_RETURN = "RETURN";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "proposal_id", nullable = false)
    private Long proposalId;

    @Column(name = "revision_id", nullable = false)
    private Long revisionId;

    @Column(name = "assignment_id")
    private Long assignmentId;

    @Column(name = "action", nullable = false, length = 32)
    private String action;

    @Column(name = "actor_account_public_id", nullable = false)
    private UUID actorAccountPublicId;

    @Column(name = "reason", length = 1000)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ProfileDecision() {
    }

    public ProfileDecision(Long proposalId, Long revisionId, Long assignmentId, String action,
                           UUID actorAccountPublicId, String reason, Instant createdAt) {
        this.proposalId = Objects.requireNonNull(proposalId, "proposalId must not be null");
        this.revisionId = Objects.requireNonNull(revisionId, "revisionId must not be null");
        this.assignmentId = assignmentId;
        if (!ACTION_APPROVE.equals(action) && !ACTION_REJECT.equals(action) && !ACTION_RETURN.equals(action)) {
            throw new IllegalArgumentException("Unknown decision action: " + action);
        }
        this.action = action;
        this.actorAccountPublicId = Objects.requireNonNull(
            actorAccountPublicId, "actorAccountPublicId must not be null");
        this.reason = reason;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public Long getId() {
        return id;
    }

    public Long getProposalId() {
        return proposalId;
    }

    public Long getRevisionId() {
        return revisionId;
    }

    public Long getAssignmentId() {
        return assignmentId;
    }

    public String getAction() {
        return action;
    }

    public UUID getActorAccountPublicId() {
        return actorAccountPublicId;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
