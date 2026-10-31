package id.mydev.peoplecore.attendance.domain.repository;

import id.mydev.peoplecore.attendance.domain.model.HolidayDate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface HolidayDateRepository extends JpaRepository<HolidayDate, Long> {
    List<HolidayDate> findByRevisionIdInAndHolidayDayBetween(List<Long> revisionIds, LocalDate from, LocalDate to);

    List<HolidayDate> findByRevisionId(Long revisionId);

    Optional<HolidayDate> findByRevisionIdAndHolidayDay(Long revisionId, LocalDate holidayDay);
}
