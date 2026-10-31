package id.mydev.peoplecore.attendance.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalTime;
import java.util.Objects;

@Entity
@Table(name = "work_schedule_day")
public class WorkScheduleDay {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "revision_id", nullable = false)
    private Long revisionId;

    @Column(name = "weekday", nullable = false)
    private int weekday;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Column(name = "overnight_offset", nullable = false)
    private int overnightOffset;

    protected WorkScheduleDay() {
    }

    public WorkScheduleDay(Long revisionId, int weekday, LocalTime startTime,
                           LocalTime endTime, int overnightOffset) {
        this.revisionId = Objects.requireNonNull(revisionId, "revisionId must not be null");
        if (weekday < 1 || weekday > 7) {
            throw new IllegalArgumentException("weekday must be 1..7");
        }
        this.weekday = weekday;
        this.startTime = Objects.requireNonNull(startTime, "startTime must not be null");
        this.endTime = Objects.requireNonNull(endTime, "endTime must not be null");
        if (overnightOffset != 0 && overnightOffset != 1) {
            throw new IllegalArgumentException("overnightOffset must be 0 or 1");
        }
        if (overnightOffset == 0 && !startTime.isBefore(endTime)) {
            throw new IllegalArgumentException("startTime must be before endTime for a same-day occurrence");
        }
        if (overnightOffset == 1 && !endTime.isBefore(startTime)) {
            throw new IllegalArgumentException("endTime must be before startTime for an overnight occurrence");
        }
        this.overnightOffset = overnightOffset;
    }

    public Long getId() { return id; }

    public Long getRevisionId() { return revisionId; }

    public int getWeekday() { return weekday; }

    public LocalTime getStartTime() { return startTime; }

    public LocalTime getEndTime() { return endTime; }

    public int getOvernightOffset() { return overnightOffset; }
}
