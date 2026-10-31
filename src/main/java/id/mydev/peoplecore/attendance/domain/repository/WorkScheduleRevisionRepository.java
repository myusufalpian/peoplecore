package id.mydev.peoplecore.attendance.domain.repository;

import id.mydev.peoplecore.attendance.domain.model.WorkScheduleRevision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;

import java.util.Optional;
import java.util.UUID;

public interface WorkScheduleRevisionRepository extends JpaRepository<WorkScheduleRevision, Long> {
    org.springframework.data.domain.Page<WorkScheduleRevision> findByStatus(String status, org.springframework.data.domain.Pageable pageable);

    Optional<WorkScheduleRevision> findByPublicId(UUID publicId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from WorkScheduleRevision r where r.id = :id")
    Optional<WorkScheduleRevision> lockById(Long id);
}
