package id.mydev.peoplecore.attendance.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.Objects;

@Entity
@Table(name = "holiday_date")
public class HolidayDate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "revision_id", nullable = false)
    private Long revisionId;

    @Column(name = "holiday_day", nullable = false)
    private LocalDate holidayDay;

    @Column(name = "holiday_name", nullable = false, length = 256)
    private String holidayName;

    protected HolidayDate() {
    }

    public HolidayDate(Long revisionId, LocalDate holidayDay, String holidayName) {
        this.revisionId = Objects.requireNonNull(revisionId, "revisionId must not be null");
        this.holidayDay = Objects.requireNonNull(holidayDay, "holidayDay must not be null");
        if (holidayName == null || holidayName.isBlank()) {
            throw new IllegalArgumentException("holidayName must not be blank");
        }
        this.holidayName = holidayName;
    }

    public Long getId() { return id; }

    public Long getRevisionId() { return revisionId; }

    public LocalDate getHolidayDay() { return holidayDay; }

    public String getHolidayName() { return holidayName; }
}
