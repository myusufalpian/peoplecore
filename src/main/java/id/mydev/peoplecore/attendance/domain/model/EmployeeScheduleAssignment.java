package id.mydev.peoplecore.attendance.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "employee_schedule_assignment")
public class EmployeeScheduleAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true)
    private UUID publicId;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "revision_id", nullable = false)
    private Long revisionId;

    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;

    @Column(name = "to_date")
    private LocalDate toDate;

    @Column(name = "superseded", nullable = false)
    private boolean superseded;

    @Column(name = "supersedes_public_id")
    private UUID supersedesPublicId;

    @Column(name = "backdate_reason", length = 1000)
    private String backdateReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected EmployeeScheduleAssignment() {
    }

    public EmployeeScheduleAssignment(UUID publicId, Long employeeId, Long revisionId,
                                      LocalDate fromDate, LocalDate toDate, Instant createdAt,
                                      UUID supersedesPublicId, String backdateReason) {
        this.publicId = Objects.requireNonNull(publicId, "publicId must not be null");
        this.employeeId = Objects.requireNonNull(employeeId, "employeeId must not be null");
        this.revisionId = Objects.requireNonNull(revisionId, "revisionId must not be null");
        this.fromDate = Objects.requireNonNull(fromDate, "fromDate must not be null");
        if (toDate != null && !fromDate.isBefore(toDate)) {
            throw new IllegalArgumentException("fromDate must be before toDate");
        }
        this.toDate = toDate;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.supersedesPublicId = supersedesPublicId;
        this.backdateReason = backdateReason;
    }

    public boolean covers(LocalDate date) {
        Objects.requireNonNull(date, "date must not be null");
        if (superseded) {
            return false;
        }
        if (date.isBefore(fromDate)) {
            return false;
        }
        return toDate == null || date.isBefore(toDate);
    }

    public boolean overlaps(LocalDate otherFrom, LocalDate otherTo) {
        Objects.requireNonNull(otherFrom, "otherFrom must not be null");
        if (superseded) {
            return false;
        }
        if (otherTo != null && !otherFrom.isBefore(otherTo)) {
            throw new IllegalArgumentException("otherFrom must be before otherTo");
        }
        LocalDate end = toDate;
        if (end != null && !otherFrom.isBefore(end)) {
            return false;
        }
        return otherTo == null || fromDate.isBefore(otherTo);
    }

    public void supersede(String backdateReason) {
        if (superseded) {
            throw new IllegalStateException("Assignment has already been superseded");
        }
        this.superseded = true;
        this.backdateReason = backdateReason;
    }

    public Long getId() { return id; }

    public UUID getPublicId() { return publicId; }

    public Long getEmployeeId() { return employeeId; }

    public Long getRevisionId() { return revisionId; }

    public LocalDate getFromDate() { return fromDate; }

    public LocalDate getToDate() { return toDate; }

    public boolean isSuperseded() { return superseded; }

    public UUID getSupersedesPublicId() { return supersedesPublicId; }

    public String getBackdateReason() { return backdateReason; }

    public Instant getCreatedAt() { return createdAt; }
}
