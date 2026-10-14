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
@Table(name = "profile_assignment")
public class ProfileAssignment {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";
    public static final String STATUS_CLOSED = "CLOSED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true)
    private UUID publicId;

    @Column(name = "proposal_id", nullable = false)
    private Long proposalId;

    @Column(name = "revision_id", nullable = false)
    private Long revisionId;

    @Column(name = "assignee_account_public_id", nullable = false)
    private UUID assigneeAccountPublicId;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "reason", length = 1000)
    private String reason;

    @Column(name = "actor", length = 128)
    private String actor;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    protected ProfileAssignment() {
    }

    public ProfileAssignment(UUID publicId, Long proposalId, Long revisionId,
                             UUID assigneeAccountPublicId, Instant createdAt) {
        this.publicId = Objects.requireNonNull(publicId, "publicId must not be null");
        this.proposalId = Objects.requireNonNull(proposalId, "proposalId must not be null");
        this.revisionId = Objects.requireNonNull(revisionId, "revisionId must not be null");
        this.assigneeAccountPublicId = Objects.requireNonNull(
            assigneeAccountPublicId, "assigneeAccountPublicId must not be null");
        this.status = STATUS_ACTIVE;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public void supersede(String reason, String actor, Instant now) {
        requireActive();
        this.status = STATUS_SUPERSEDED;
        this.reason = reason;
        this.actor = actor;
        this.closedAt = Objects.requireNonNull(now, "now must not be null");
    }

    public void close(Instant now) {
        requireActive();
        this.status = STATUS_CLOSED;
        this.closedAt = Objects.requireNonNull(now, "now must not be null");
    }

    private void requireActive() {
        if (!STATUS_ACTIVE.equals(status)) {
            throw new IllegalStateException("Only an active assignment can transition");
        }
    }

    public boolean isActive() {
        return STATUS_ACTIVE.equals(status);
    }

    public Long getId() {
        return id;
    }

    public UUID getPublicId() {
        return publicId;
    }

    public Long getProposalId() {
        return proposalId;
    }

    public Long getRevisionId() {
        return revisionId;
    }

    public UUID getAssigneeAccountPublicId() {
        return assigneeAccountPublicId;
    }

    public String getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }

    public String getActor() {
        return actor;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }
}
