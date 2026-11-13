package id.mydev.peoplecore.attendance.domain.repository;

import id.mydev.peoplecore.attendance.domain.model.WorkScheduleRevision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface WorkScheduleRevisionRepository extends JpaRepository<WorkScheduleRevision, Long> {
    Page<WorkScheduleRevision> findByStatus(String status, Pageable pageable);

    Optional<WorkScheduleRevision> findByPublicId(UUID publicId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from WorkScheduleRevision r where r.id = :id")
    Optional<WorkScheduleRevision> lockById(Long id);
}
