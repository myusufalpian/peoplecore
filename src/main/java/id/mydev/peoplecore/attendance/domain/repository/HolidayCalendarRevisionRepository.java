package id.mydev.peoplecore.attendance.domain.repository;

import id.mydev.peoplecore.attendance.domain.model.HolidayCalendarRevision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface HolidayCalendarRevisionRepository extends JpaRepository<HolidayCalendarRevision, Long> {
    Page<HolidayCalendarRevision> findByStatus(String status, Pageable pageable);

    Optional<HolidayCalendarRevision> findByPublicId(UUID publicId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from HolidayCalendarRevision r where r.id = :id")
    Optional<HolidayCalendarRevision> lockById(Long id);
}
