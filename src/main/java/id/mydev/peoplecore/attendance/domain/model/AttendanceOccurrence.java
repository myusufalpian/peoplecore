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
@Table(name = "attendance_occurrence")
public class AttendanceOccurrence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true)
    private UUID publicId;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    @Column(name = "timezone", nullable = false, length = 64)
    private String timezone;

    @Column(name = "schedule_revision_id", nullable = false)
    private Long scheduleRevisionId;

    @Column(name = "calendar_revision_id")
    private Long calendarRevisionId;

    @Column(name = "schedule_assignment_id", nullable = false)
    private Long scheduleAssignmentId;

    @Column(name = "calendar_assignment_id")
    private Long calendarAssignmentId;

    @Column(name = "holiday_name", length = 256)
    private String holidayName;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AttendanceOccurrence() {
    }

    public AttendanceOccurrence(UUID publicId, Long employeeId, LocalDate workDate,
                               Instant startAt, Instant endAt, String timezone,
                               Long scheduleRevisionId, Long calendarRevisionId,
                               Long scheduleAssignmentId, Long calendarAssignmentId,
                               String holidayName, Instant createdAt) {
        this.publicId = Objects.requireNonNull(publicId, "publicId must not be null");
        this.employeeId = Objects.requireNonNull(employeeId, "employeeId must not be null");
        this.workDate = Objects.requireNonNull(workDate, "workDate must not be null");
        this.startAt = Objects.requireNonNull(startAt, "startAt must not be null");
        this.endAt = Objects.requireNonNull(endAt, "endAt must not be null");
        if (!startAt.isBefore(endAt)) {
            throw new IllegalArgumentException("startAt must be before endAt");
        }
        if (timezone == null || timezone.isBlank()) {
            throw new IllegalArgumentException("timezone must not be blank");
        }
        this.timezone = timezone;
        this.scheduleRevisionId = Objects.requireNonNull(scheduleRevisionId, "scheduleRevisionId must not be null");
        this.calendarRevisionId = calendarRevisionId;
        this.scheduleAssignmentId = Objects.requireNonNull(scheduleAssignmentId, "scheduleAssignmentId must not be null");
        this.calendarAssignmentId = calendarAssignmentId;
        this.holidayName = holidayName;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public boolean isHoliday() {
        return holidayName != null;
    }

    public Long getId() { return id; }

    public UUID getPublicId() { return publicId; }

    public Long getEmployeeId() { return employeeId; }

    public LocalDate getWorkDate() { return workDate; }

    public Instant getStartAt() { return startAt; }

    public Instant getEndAt() { return endAt; }

    public String getTimezone() { return timezone; }

    public Long getScheduleRevisionId() { return scheduleRevisionId; }

    public Long getCalendarRevisionId() { return calendarRevisionId; }

    public Long getScheduleAssignmentId() { return scheduleAssignmentId; }

    public Long getCalendarAssignmentId() { return calendarAssignmentId; }

    public String getHolidayName() { return holidayName; }

    public Instant getCreatedAt() { return createdAt; }
}
