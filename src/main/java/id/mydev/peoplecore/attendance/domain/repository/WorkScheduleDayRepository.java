package id.mydev.peoplecore.attendance.domain.repository;

import id.mydev.peoplecore.attendance.domain.model.WorkScheduleDay;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkScheduleDayRepository extends JpaRepository<WorkScheduleDay, Long> {
    List<WorkScheduleDay> findByRevisionIdIn(List<Long> revisionIds);

    List<WorkScheduleDay> findByRevisionId(Long revisionId);
}
