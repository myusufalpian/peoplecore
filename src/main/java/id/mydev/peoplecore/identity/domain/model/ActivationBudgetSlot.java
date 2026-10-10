package id.mydev.peoplecore.identity.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "activation_budget_slots",
    uniqueConstraints = @UniqueConstraint(name = "uk_activation_budget_slots",
        columnNames = {"scope", "ref_a", "ref_b"}))
public class ActivationBudgetSlot {

    public static final String SCOPE_PRINCIPAL = "PRINCIPAL";
    public static final String SCOPE_INVITATION = "INVITATION";
    public static final String SCOPE_INVITATION_PRINCIPAL = "INVITATION_PRINCIPAL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "scope", nullable = false, length = 32)
    private String scope;

    @Column(name = "ref_a", nullable = false, length = 320)
    private String refA;

    @Column(name = "ref_b", nullable = false, length = 320)
    private String refB;

    @Column(name = "window_start", nullable = false)
    private Instant windowStart;

    @Column(name = "used", nullable = false)
    private int used;

    protected ActivationBudgetSlot() {
    }

    public ActivationBudgetSlot(String scope, String refA, String refB, Instant windowStart, int used) {
        this.scope = Objects.requireNonNull(scope, "scope must not be null");
        this.refA = Objects.requireNonNull(refA, "refA must not be null");
        this.refB = Objects.requireNonNull(refB, "refB must not be null");
        this.windowStart = Objects.requireNonNull(windowStart, "windowStart must not be null");
        this.used = used;
    }

    public void reset(Instant windowStart) {
        this.windowStart = Objects.requireNonNull(windowStart, "windowStart must not be null");
        this.used = 0;
    }

    public void use() {
        this.used++;
    }

    public Long getId() {
        return id;
    }

    public String getScope() {
        return scope;
    }

    public String getRefA() {
        return refA;
    }

    public String getRefB() {
        return refB;
    }

    public Instant getWindowStart() {
        return windowStart;
    }

    public int getUsed() {
        return used;
    }
}
