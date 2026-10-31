package id.mydev.peoplecore.attendance.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "attendance_account")
public class AttendanceAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_id", nullable = false, unique = true)
    private Long employeeId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AttendanceAccount() {
    }

    public AttendanceAccount(Long employeeId, Instant createdAt) {
        this.employeeId = Objects.requireNonNull(employeeId, "employeeId must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public Long getId() { return id; }

    public Long getEmployeeId() { return employeeId; }

    public Instant getCreatedAt() { return createdAt; }
}
