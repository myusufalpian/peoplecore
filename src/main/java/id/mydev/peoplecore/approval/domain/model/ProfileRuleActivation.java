package id.mydev.peoplecore.approval.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "profile_rule_activations")
public class ProfileRuleActivation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rule_set_id", nullable = false)
    private Long ruleSetId;

    @Column(name = "activated_at", nullable = false)
    private Instant activatedAt;

    @Column(name = "activated_by", nullable = false, length = 128)
    private String activatedBy;

    protected ProfileRuleActivation() {
    }

    public ProfileRuleActivation(Long ruleSetId, Instant activatedAt, String activatedBy) {
        this.ruleSetId = Objects.requireNonNull(ruleSetId, "ruleSetId must not be null");
        this.activatedAt = Objects.requireNonNull(activatedAt, "activatedAt must not be null");
        if (activatedBy == null || activatedBy.isBlank()) {
            throw new IllegalArgumentException("activatedBy must not be blank");
        }
        this.activatedBy = activatedBy;
    }

    public Long getId() {
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
