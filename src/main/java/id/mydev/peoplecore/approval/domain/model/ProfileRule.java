package id.mydev.peoplecore.approval.domain.model;

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
@Table(name = "profile_rules")
public class ProfileRule {

    public static final int MAX_RULE_ID_LENGTH = 64;
    public static final int MAX_ORG_UNIT_LENGTH = 128;
    public static final int MAX_JOB_LEVEL_LENGTH = 64;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rule_set_id", nullable = false)
    private Long ruleSetId;

    @Column(name = "rule_id", nullable = false, length = 64)
    private String ruleId;

    @Column(name = "priority", nullable = false)
    private int priority;

    @Column(name = "org_unit", length = 128)
    private String orgUnit;

    @Column(name = "job_level", length = 64)
    private String jobLevel;

    @Column(name = "reviewer_account_public_id", nullable = false)
    private UUID reviewerAccountPublicId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ProfileRule() {
    }

    public ProfileRule(Long ruleSetId, String ruleId, int priority, String orgUnit,
                       String jobLevel, UUID reviewerAccountPublicId, Instant createdAt) {
        this.ruleSetId = Objects.requireNonNull(ruleSetId, "ruleSetId must not be null");
        this.ruleId = requireRuleId(ruleId);
        this.priority = priority;
        this.orgUnit = normalizePredicate(orgUnit, "orgUnit", MAX_ORG_UNIT_LENGTH);
        this.jobLevel = normalizePredicate(jobLevel, "jobLevel", MAX_JOB_LEVEL_LENGTH);
        this.reviewerAccountPublicId = Objects.requireNonNull(
            reviewerAccountPublicId, "reviewerAccountPublicId must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    private static String requireRuleId(String ruleId) {
        if (ruleId == null || ruleId.isBlank()) {
            throw new IllegalArgumentException("ruleId must not be blank");
        }
        if (ruleId.length() > MAX_RULE_ID_LENGTH) {
            throw new IllegalArgumentException("ruleId must not exceed 64 characters");
        }
        return ruleId;
    }

    private static String normalizePredicate(String value, String field, int maxLength) {
        if (value == null) {
            return null;
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " predicate must be null or non-blank");
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(field + " predicate exceeds maximum length");
        }
        return value;
    }

    public boolean matches(String unit, String level) {
        if (orgUnit != null && !orgUnit.equals(unit)) {
            return false;
        }
        return jobLevel == null || jobLevel.equals(level);
    }

    public Long getId() {
        return id;
    }

    public Long getRuleSetId() {
        return ruleSetId;
    }

    public String getRuleId() {
        return ruleId;
    }

    public int getPriority() {
        return priority;
    }

    public String getOrgUnit() {
        return orgUnit;
    }

    public String getJobLevel() {
        return jobLevel;
    }

    public UUID getReviewerAccountPublicId() {
        return reviewerAccountPublicId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
