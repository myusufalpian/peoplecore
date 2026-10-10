package id.mydev.peoplecore.organization.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "employees")
public class Employee {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_INACTIVE = "INACTIVE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true)
    private UUID publicId;

    @Column(name = "employee_number", nullable = false, unique = true, length = 64)
    private String employeeNumber;

    @Column(name = "user_account_id")
    private Long userAccountId;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "employment_start_date")
    private LocalDate employmentStartDate;

    @Column(name = "employment_end_date")
    private LocalDate employmentEndDate;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Employee() {
    }

    public Employee(UUID publicId, String employeeNumber, Instant createdAt) {
        this.publicId = Objects.requireNonNull(publicId, "publicId must not be null");
        this.employeeNumber = Objects.requireNonNull(employeeNumber, "employeeNumber must not be null");
        this.status = STATUS_ACTIVE;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public void linkAccount(Long accountId) {
        this.userAccountId = Objects.requireNonNull(accountId, "accountId must not be null");
    }

    public void changeEmployeeNumber(String employeeNumber) {
        if (employeeNumber == null || employeeNumber.isBlank()) {
            throw new IllegalArgumentException("employeeNumber must not be blank");
        }
        this.employeeNumber = employeeNumber;
    }

    public void deactivate() {
        this.status = STATUS_INACTIVE;
    }

    public void changeEmploymentDates(LocalDate startDate, LocalDate endDate) {
        Objects.requireNonNull(startDate, "employment start date is required");
        if (endDate != null && endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("Employment end date must not precede start date");
        }
        employmentStartDate = startDate;
        employmentEndDate = endDate;
    }

    public LocalDate getEmploymentStartDate() { return employmentStartDate; }

    public LocalDate getEmploymentEndDate() { return employmentEndDate; }

    public void reactivate() {
        this.status = STATUS_ACTIVE;
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

    public String getEmployeeNumber() {
        return employeeNumber;
    }

    public Long getUserAccountId() {
        return userAccountId;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
