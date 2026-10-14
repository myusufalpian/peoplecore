package id.mydev.peoplecore.approval.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "profile_rule_current_activation")
public class ProfileRuleCurrentActivation {

    public static final int SINGLETON_ID = 1;

    @Id
    @Column(name = "id", nullable = false)
    private Integer id;

    @Column(name = "rule_set_id", nullable = false)
    private Long ruleSetId;

    @Column(name = "activated_at", nullable = false)
    private Instant activatedAt;

    @Column(name = "activated_by", nullable = false, length = 128)
    private String activatedBy;

    protected ProfileRuleCurrentActivation() {
    }

    public ProfileRuleCurrentActivation(Long ruleSetId, Instant activatedAt, String activatedBy) {
        this.id = SINGLETON_ID;
        this.ruleSetId = Objects.requireNonNull(ruleSetId, "ruleSetId must not be null");
        this.activatedAt = Objects.requireNonNull(activatedAt, "activatedAt must not be null");
        if (activatedBy == null || activatedBy.isBlank()) {
            throw new IllegalArgumentException("activatedBy must not be blank");
        }
        this.activatedBy = activatedBy;
    }

    public void moveTo(Long ruleSetId, Instant activatedAt, String activatedBy) {
        this.ruleSetId = Objects.requireNonNull(ruleSetId, "ruleSetId must not be null");
        this.activatedAt = Objects.requireNonNull(activatedAt, "activatedAt must not be null");
        if (activatedBy == null || activatedBy.isBlank()) {
            throw new IllegalArgumentException("activatedBy must not be blank");
        }
        this.activatedBy = activatedBy;
    }

    public Integer getId() {
        return id;
    }

    public Long getRuleSetId() {
        return ruleSetId;
    }

    public Instant getActivatedAt() {
        return activatedAt;
    }

    public String getActivatedBy() {
        return activatedBy;
    }
}
