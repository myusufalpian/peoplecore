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
@Table(name = "employee_assignments")
public class EmployeeAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true)
    private UUID publicId;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "org_unit", nullable = false, length = 128)
    private String orgUnit;

    @Column(name = "manager_employee_id")
    private Long managerEmployeeId;

    @Column(name = "job_level", nullable = false, length = 64)
    private String jobLevel;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_to")
    private Instant validTo;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "superseded_at")
    private Instant supersededAt;

    @Column(name = "supersedes_id")
    private UUID supersedesId;

    protected EmployeeAssignment() {
    }

    public EmployeeAssignment(UUID publicId, Long employeeId, String orgUnit, Long managerEmployeeId,
                              String jobLevel, Instant validFrom, Instant validTo, Instant createdAt) {
        this.publicId = Objects.requireNonNull(publicId, "publicId must not be null");
        this.employeeId = Objects.requireNonNull(employeeId, "employeeId must not be null");
        this.orgUnit = Objects.requireNonNull(orgUnit, "orgUnit must not be null");
        this.managerEmployeeId = managerEmployeeId;
        this.jobLevel = Objects.requireNonNull(jobLevel, "jobLevel must not be null");
        this.validFrom = Objects.requireNonNull(validFrom, "validFrom must not be null");
        if (validTo != null && !validFrom.isBefore(validTo)) {
            throw new IllegalArgumentException("validFrom must be before validTo");
        }
        this.validTo = validTo;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public boolean overlaps(Instant otherFrom, Instant otherTo) {
        Objects.requireNonNull(otherFrom, "otherFrom must not be null");
        Instant end = validTo;
        if (end != null && !otherFrom.isBefore(end)) {
            return false;
        }
        return otherTo == null || validFrom.isBefore(otherTo);
    }

    public void supersede(Instant now) {
        if (supersededAt != null) {
            throw new IllegalStateException("Assignment has already been superseded");
        }
        supersededAt = Objects.requireNonNull(now, "now must not be null");
    }

    public void recordPredecessor(UUID predecessor) {
        if (supersedesId != null) {
            throw new IllegalStateException("Predecessor is already recorded");
        }
        supersedesId = Objects.requireNonNull(predecessor, "predecessor must not be null");
    }

    public Instant getSupersededAt() { return supersededAt; }

    public UUID getSupersedesId() { return supersedesId; }

    public Long getId() {
        return id;
    }

    public UUID getPublicId() {
        return publicId;
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public String getOrgUnit() {
        return orgUnit;
    }

    public Long getManagerEmployeeId() {
        return managerEmployeeId;
    }

    public String getJobLevel() {
        return jobLevel;
    }

    public Instant getValidFrom() {
        return validFrom;
    }

    public Instant getValidTo() {
        return validTo;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
