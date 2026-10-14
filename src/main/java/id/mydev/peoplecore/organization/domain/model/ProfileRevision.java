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
@Table(name = "profile_revision")
public class ProfileRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true)
    private UUID publicId;

    @Column(name = "proposal_id", nullable = false)
    private Long proposalId;

    @Column(name = "revision_number", nullable = false)
    private int revisionNumber;

    @Column(name = "rule_set_public_id")
    private UUID ruleSetPublicId;

    @Column(name = "evaluated_at")
    private Instant evaluatedAt;

    @Column(name = "evaluated_org_unit", length = 128)
    private String evaluatedOrgUnit;

    @Column(name = "evaluated_job_level", length = 64)
    private String evaluatedJobLevel;

    @Column(name = "route_reviewer_account_public_id")
    private UUID routeReviewerAccountPublicId;

    @Column(name = "blocked_reason", length = 64)
    private String blockedReason;

    @Column(name = "org_assignment_public_id")
    private UUID orgAssignmentPublicId;

    @Column(name = "org_assignment_predecessor_public_id")
    private UUID orgAssignmentPredecessorPublicId;

    @Column(name = "sealed", nullable = false)
    private boolean sealed;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ProfileRevision() {
    }

    public ProfileRevision(UUID publicId, Long proposalId, int revisionNumber, UUID ruleSetPublicId,
                           Instant evaluatedAt, String evaluatedOrgUnit, String evaluatedJobLevel,
                           UUID routeReviewerAccountPublicId, String blockedReason, Instant createdAt) {
        this.publicId = Objects.requireNonNull(publicId, "publicId must not be null");
        this.proposalId = Objects.requireNonNull(proposalId, "proposalId must not be null");
        this.revisionNumber = revisionNumber;
        this.ruleSetPublicId = ruleSetPublicId;
        this.evaluatedAt = evaluatedAt;
        this.evaluatedOrgUnit = evaluatedOrgUnit;
        this.evaluatedJobLevel = evaluatedJobLevel;
        this.routeReviewerAccountPublicId = routeReviewerAccountPublicId;
        this.blockedReason = blockedReason;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
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

    public int getRevisionNumber() {
        return revisionNumber;
    }

    public UUID getRuleSetPublicId() {
        return ruleSetPublicId;
    }

    public Instant getEvaluatedAt() {
        return evaluatedAt;
    }

    public String getEvaluatedOrgUnit() {
        return evaluatedOrgUnit;
    }

    public String getEvaluatedJobLevel() {
        return evaluatedJobLevel;
    }

    public UUID getRouteReviewerAccountPublicId() {
        return routeReviewerAccountPublicId;
    }

    public String getBlockedReason() {
        return blockedReason;
    }

    public boolean isSealed() {
        return sealed;
    }

    public UUID getOrgAssignmentPublicId() { return orgAssignmentPublicId; }

    public UUID getOrgAssignmentPredecessorPublicId() { return orgAssignmentPredecessorPublicId; }

    public void pinOrganizationContext(UUID assignmentPublicId, UUID predecessorPublicId) {
        if (sealed) {
            throw new IllegalStateException("Sealed revision context is immutable");
        }
        this.orgAssignmentPublicId = assignmentPublicId;
        this.orgAssignmentPredecessorPublicId = predecessorPublicId;
    }

    public void seal() {
        this.sealed = true;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
