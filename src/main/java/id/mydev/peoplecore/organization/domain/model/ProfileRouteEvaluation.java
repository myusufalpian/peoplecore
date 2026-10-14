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
@Table(name = "profile_route_evaluation")
public class ProfileRouteEvaluation {

    public static final String RESULT_MATCHED = "MATCHED";
    public static final String RESULT_BLOCKED = "BLOCKED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "proposal_id", nullable = false)
    private Long proposalId;

    @Column(name = "revision_id")
    private Long revisionId;

    @Column(name = "evaluated_at", nullable = false)
    private Instant evaluatedAt;

    @Column(name = "rule_set_public_id")
    private UUID ruleSetPublicId;

    @Column(name = "org_unit", length = 128)
    private String orgUnit;

    @Column(name = "job_level", length = 64)
    private String jobLevel;

    @Column(name = "result", nullable = false, length = 16)
    private String result;

    @Column(name = "rule_id", length = 64)
    private String ruleId;

    @Column(name = "reviewer_account_public_id")
    private UUID reviewerAccountPublicId;

    @Column(name = "blocked_reason", length = 64)
    private String blockedReason;

    protected ProfileRouteEvaluation() {
    }

    public ProfileRouteEvaluation(Long proposalId, Long revisionId, Instant evaluatedAt,
                                  UUID ruleSetPublicId, String orgUnit, String jobLevel,
                                  String result, String ruleId, UUID reviewerAccountPublicId,
                                  String blockedReason) {
        this.proposalId = Objects.requireNonNull(proposalId, "proposalId must not be null");
        this.revisionId = revisionId;
        this.evaluatedAt = Objects.requireNonNull(evaluatedAt, "evaluatedAt must not be null");
        this.ruleSetPublicId = ruleSetPublicId;
        this.orgUnit = orgUnit;
        this.jobLevel = jobLevel;
        if (!RESULT_MATCHED.equals(result) && !RESULT_BLOCKED.equals(result)) {
            throw new IllegalArgumentException("Unknown evaluation result: " + result);
        }
        this.result = result;
        this.ruleId = ruleId;
        this.reviewerAccountPublicId = reviewerAccountPublicId;
        this.blockedReason = blockedReason;
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

    public Instant getEvaluatedAt() {
        return evaluatedAt;
    }

    public UUID getRuleSetPublicId() {
        return ruleSetPublicId;
    }

    public String getOrgUnit() {
        return orgUnit;
    }

    public String getJobLevel() {
        return jobLevel;
    }

    public String getResult() {
        return result;
    }

    public String getRuleId() {
        return ruleId;
    }

    public UUID getReviewerAccountPublicId() {
        return reviewerAccountPublicId;
    }

    public String getBlockedReason() {
        return blockedReason;
    }
}
