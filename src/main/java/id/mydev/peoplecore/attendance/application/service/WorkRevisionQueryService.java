package id.mydev.peoplecore.attendance.application.service;

import id.mydev.peoplecore.attendance.domain.repository.WorkScheduleRevisionRepository;
import id.mydev.peoplecore.attendance.domain.repository.HolidayCalendarRevisionRepository;
import id.mydev.peoplecore.attendance.domain.repository.WorkScheduleDayRepository;
import id.mydev.peoplecore.attendance.domain.repository.HolidayDateRepository;
import id.mydev.peoplecore.identity.application.service.AccountAccessService;
import id.mydev.peoplecore.common.api.ApiData;
import id.mydev.peoplecore.common.api.PageMetadata;
import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.common.command.AdmissionGuard;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class WorkRevisionQueryService {
    private final WorkScheduleRevisionRepository schedules;
    private final HolidayCalendarRevisionRepository calendars;
    private final WorkScheduleDayRepository days;
    private final HolidayDateRepository holidays;
    private final AccountAccessService access;
    private final AdmissionGuard admission;
    private final Clock clock;
    private final jakarta.persistence.EntityManager entities;

    public WorkRevisionQueryService(WorkScheduleRevisionRepository schedules,
        HolidayCalendarRevisionRepository calendars, WorkScheduleDayRepository days,
        HolidayDateRepository holidays, AccountAccessService access, AdmissionGuard admission, Clock clock,
        jakarta.persistence.EntityManager entities) {
        this.schedules = schedules;
        this.calendars = calendars;
        this.days = days;
        this.holidays = holidays;
        this.access = access;
        this.admission = admission;
        this.clock = clock;
        this.entities = entities;
    }

    public record Summary(UUID id, String status, Instant publishedAt) { }
    public record Day(int weekday, LocalTime startTime, LocalTime endTime, int overnightOffset) { }
    public record Holiday(LocalDate holidayDay, String holidayName) { }
    public record Schedule(UUID id, String timezone, String status, Instant publishedAt, List<Day> days) { }
    public record Calendar(UUID id, String location, String status, Instant publishedAt, List<Holiday> holidays) { }

    private void authorize(Authentication caller) {
        access.requireHrOperation(caller, Instant.now(clock));
    }

    private PageRequest begin(Authentication caller, int page, int size) {
        PageMetadata.validate(page, size, 0);
        admission.acquire(AdmissionGuard.Mode.SHARED);
        entities.clear();
        authorize(caller);
        return PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "publicId"));
    }

    public ApiData<List<Summary>> schedules(Authentication caller, int page, int size) {
        var result = schedules.findByStatus("PUBLISHED", begin(caller, page, size));
        var data = result.map(WorkRevisionQueryMapper::summary).getContent();
        authorize(caller);
        return new ApiData<>(data, PageMetadata.of(page, size, result.getTotalElements()));
    }

    public ApiData<List<Summary>> calendars(Authentication caller, int page, int size) {
        var result = calendars.findByStatus("PUBLISHED", begin(caller, page, size));
        var data = result.map(WorkRevisionQueryMapper::summary).getContent();
        authorize(caller);
        return new ApiData<>(data, PageMetadata.of(page, size, result.getTotalElements()));
    }

    public Schedule schedule(Authentication caller, UUID id) {
        begin(caller, 0, 1);
        var r = schedules.findByPublicId(id).filter(v -> v.isPublished()).orElseThrow(ResourceNotFoundException::new);
        var content = days.findByRevisionId(r.getId()).stream()
            .sorted(java.util.Comparator.comparingInt(d -> d.getWeekday()))
            .map(WorkRevisionQueryMapper::day).toList();
        authorize(caller);
        return new Schedule(r.getPublicId(), r.getTimezone(), r.getStatus(), r.getPublishedAt(), content);
    }

    public Calendar calendar(Authentication caller, UUID id) {
        begin(caller, 0, 1);
        var r = calendars.findByPublicId(id).filter(v -> v.isPublished()).orElseThrow(ResourceNotFoundException::new);
        var content = holidays.findByRevisionId(r.getId()).stream()
            .sorted(java.util.Comparator.comparing(d -> d.getHolidayDay()))
            .map(WorkRevisionQueryMapper::holiday).toList();
        authorize(caller);
        return new Calendar(r.getPublicId(), r.getLocation(), r.getStatus(), r.getPublishedAt(), content);
    }
}
