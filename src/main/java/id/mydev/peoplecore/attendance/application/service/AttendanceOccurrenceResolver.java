package id.mydev.peoplecore.attendance.application.service;

import id.mydev.peoplecore.attendance.domain.exception.WorkContextException;
import id.mydev.peoplecore.attendance.domain.model.AttendanceOccurrence;
import id.mydev.peoplecore.attendance.domain.model.EmployeeCalendarAssignment;
import id.mydev.peoplecore.attendance.domain.model.EmployeeScheduleAssignment;
import id.mydev.peoplecore.attendance.domain.model.HolidayCalendarRevision;
import id.mydev.peoplecore.attendance.domain.model.HolidayDate;
import id.mydev.peoplecore.attendance.domain.model.WorkScheduleDay;
import id.mydev.peoplecore.attendance.domain.model.WorkScheduleRevision;
import id.mydev.peoplecore.attendance.domain.repository.AttendanceOccurrenceRepository;
import id.mydev.peoplecore.attendance.domain.repository.EmployeeCalendarAssignmentRepository;
import id.mydev.peoplecore.attendance.domain.repository.EmployeeScheduleAssignmentRepository;
import id.mydev.peoplecore.attendance.domain.repository.HolidayCalendarRevisionRepository;
import id.mydev.peoplecore.attendance.domain.repository.HolidayDateRepository;
import id.mydev.peoplecore.attendance.domain.repository.WorkScheduleDayRepository;
import id.mydev.peoplecore.attendance.domain.repository.WorkScheduleRevisionRepository;
import jakarta.persistence.EntityManager;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class AttendanceOccurrenceResolver {

    private final AttendanceOccurrenceRepository occurrences;
    private final AttendanceAccountGuard accountGuard;
    private final EmployeeScheduleAssignmentRepository scheduleAssignments;
    private final EmployeeCalendarAssignmentRepository calendarAssignments;
    private final WorkScheduleRevisionRepository scheduleRevisions;
    private final HolidayCalendarRevisionRepository calendarRevisions;
    private final WorkScheduleDayRepository scheduleDays;
    private final HolidayDateRepository holidayDates;
    private final JdbcTemplate jdbc;
    private final EntityManager entities;
    private final Clock clock;
    private final boolean postgres;

    public AttendanceOccurrenceResolver(
        AttendanceAccountGuard accountGuard,
        AttendanceOccurrenceRepository occurrences,
        EmployeeScheduleAssignmentRepository scheduleAssignments,
        EmployeeCalendarAssignmentRepository calendarAssignments,
        WorkScheduleRevisionRepository scheduleRevisions,
        HolidayCalendarRevisionRepository calendarRevisions,
        WorkScheduleDayRepository scheduleDays,
        HolidayDateRepository holidayDates,
        JdbcTemplate jdbc,
        EntityManager entities,
        Clock clock
    ) {
        this.accountGuard = accountGuard;
        this.occurrences = occurrences;
        this.scheduleAssignments = scheduleAssignments;
        this.calendarAssignments = calendarAssignments;
        this.scheduleRevisions = scheduleRevisions;
        this.calendarRevisions = calendarRevisions;
        this.scheduleDays = scheduleDays;
        this.holidayDates = holidayDates;
        this.jdbc = jdbc;
        this.entities = entities;
        this.clock = clock;
        this.postgres = detectPostgres(jdbc);
    }

    public record EffectiveOccurrence(Instant startAt, Instant endAt, String timezone,
                                      Long scheduleRevisionId, Long calendarRevisionId,
                                      Long scheduleAssignmentId, Long calendarAssignmentId,
                                      String holidayName) {
    }

    public record OccurrenceInterval(Instant startAt, Instant endAt) {
    }

    public static OccurrenceInterval intervalFor(ZoneId zone, LocalDate workDate, WorkScheduleDay day) {
        Objects.requireNonNull(zone, "zone must not be null");
        Objects.requireNonNull(workDate, "workDate must not be null");
        Objects.requireNonNull(day, "day must not be null");
        Instant startAt = workDate.atTime(day.getStartTime()).atZone(zone).toInstant();
        Instant endAt = workDate.plusDays(day.getOvernightOffset())
            .atTime(day.getEndTime()).atZone(zone).toInstant();
        return new OccurrenceInterval(startAt, endAt);
    }

    public static boolean intervalsOverlap(Instant firstStart, Instant firstEnd,
                                           Instant secondStart, Instant secondEnd) {
        return firstStart.isBefore(secondEnd) && secondStart.isBefore(firstEnd);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AttendanceOccurrence resolveOrPin(Long employeeId, LocalDate workDate) {
        Objects.requireNonNull(employeeId, "employeeId must not be null");
        Objects.requireNonNull(workDate, "workDate must not be null");
        Optional<AttendanceOccurrence> pinned = occurrences.findByEmployeeIdAndWorkDate(employeeId, workDate);
        if (pinned.isPresent()) {
            return pinned.get();
        }
        accountGuard.ensureLocked(employeeId);
        pinned = occurrences.findByEmployeeIdAndWorkDate(employeeId, workDate);
        if (pinned.isPresent()) {
            return pinned.get();
        }
        EffectiveOccurrence effective = computeEffective(employeeId, workDate);
        insertConflictSafe(employeeId, workDate, effective);
        return occurrences.findByEmployeeIdAndWorkDate(employeeId, workDate)
            .orElseThrow(() -> new WorkContextException(WorkContextException.MISSING_SCHEDULE,
                "Attendance occurrence was not established"));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<AttendanceOccurrence> findPinned(Long employeeId, LocalDate workDate) {
        Objects.requireNonNull(employeeId, "employeeId must not be null");
        Objects.requireNonNull(workDate, "workDate must not be null");
        return occurrences.findByEmployeeIdAndWorkDate(employeeId, workDate);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public EffectiveOccurrence computeEffective(Long employeeId, LocalDate workDate) {
        Objects.requireNonNull(employeeId, "employeeId must not be null");
        Objects.requireNonNull(workDate, "workDate must not be null");
        ResolvedContext context = selectContext(employeeId, workDate);
        return buildEffective(employeeId, workDate, context);
    }

    public record ContextDate(LocalDate date, AttendanceOccurrence pinned,
                              EffectiveOccurrence effective, String blockedReason) { }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<ContextDate> queryRange(Long employeeId, LocalDate from, LocalDate to) {
        if (!from.isBefore(to) || from.plusDays(366).isBefore(to)) {
            throw new IllegalArgumentException("Query range must be 1..366 days");
        }
        var scheduleTimeline = scheduleAssignments.findOverlapping(employeeId, from, to);
        var calendarTimeline = calendarAssignments.findOverlapping(employeeId, from, to);
        var schedules = new java.util.HashMap<Long, WorkScheduleRevision>();
        var calendars = new java.util.HashMap<Long, HolidayCalendarRevision>();
        var days = new java.util.HashMap<Long, List<WorkScheduleDay>>();
        var holidays = new java.util.HashMap<Long, java.util.Map<LocalDate, String>>();
        var scheduleIds = scheduleTimeline.stream().map(EmployeeScheduleAssignment::getRevisionId).distinct().toList();
        var calendarIds = calendarTimeline.stream().map(EmployeeCalendarAssignment::getRevisionId).distinct().toList();
        scheduleRevisions.findAllById(scheduleIds).forEach(r -> schedules.put(r.getId(), r));
        calendarRevisions.findAllById(calendarIds).forEach(r -> calendars.put(r.getId(), r));
        if (!scheduleIds.isEmpty()) {
            for (var day : scheduleDays.findByRevisionIdIn(scheduleIds)) {
                days.computeIfAbsent(day.getRevisionId(), ignored -> new ArrayList<>()).add(day);
            }
        }
        if (!calendarIds.isEmpty()) {
            for (var day : holidayDates.findByRevisionIdInAndHolidayDayBetween(calendarIds, from, to.minusDays(1))) {
                holidays.computeIfAbsent(day.getRevisionId(), ignored -> new java.util.HashMap<>())
                    .put(day.getHolidayDay(), day.getHolidayName());
            }
        }
        var pins = new java.util.HashMap<LocalDate, AttendanceOccurrence>();
        occurrences.findByEmployeeIdAndWorkDateBetweenOrderByWorkDateAsc(employeeId, from, to.minusDays(1))
            .forEach(pin -> pins.put(pin.getWorkDate(), pin));
        List<ContextDate> result = new ArrayList<>();
        for (LocalDate date = from; date.isBefore(to); date = date.plusDays(1)) {
            if (pins.containsKey(date)) {
                result.add(new ContextDate(date, pins.get(date), null, null));
                continue;
            }
            try {
                var context = selectContext(date, scheduleTimeline, calendarTimeline,
                    id -> Optional.ofNullable(schedules.get(id)), id -> Optional.ofNullable(calendars.get(id)));
                var effective = buildEffective(date, context, days.getOrDefault(context.schedule().getId(), List.of()),
                    holidays.getOrDefault(context.calendar().getId(), java.util.Map.of()).get(date));
                result.add(new ContextDate(date, null, effective, null));
            } catch (WorkContextException ex) {
                result.add(new ContextDate(date, null, null, ex.getReason()));
            }
        }
        return List.copyOf(result);
    }

    private void insertConflictSafe(Long employeeId, LocalDate workDate, EffectiveOccurrence effective) {
        UUID publicId = UUID.randomUUID();
        java.sql.Timestamp createdAt = java.sql.Timestamp.from(Instant.now(clock));
        if (postgres) {
            jdbc.update("INSERT INTO attendance_occurrence(public_id, employee_id, work_date, "
                + "start_at, end_at, timezone, schedule_revision_id, calendar_revision_id, "
                + "schedule_assignment_id, calendar_assignment_id, holiday_name, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (employee_id, work_date) DO NOTHING",
                publicId, employeeId, workDate,
                java.sql.Timestamp.from(effective.startAt()), java.sql.Timestamp.from(effective.endAt()),
                effective.timezone(), effective.scheduleRevisionId(), effective.calendarRevisionId(),
                effective.scheduleAssignmentId(), effective.calendarAssignmentId(),
                effective.holidayName(), createdAt);
            entities.flush();
            entities.clear();
            return;
        }
        try {
            occurrences.saveAndFlush(new AttendanceOccurrence(publicId, employeeId, workDate,
                effective.startAt(), effective.endAt(), effective.timezone(),
                effective.scheduleRevisionId(), effective.calendarRevisionId(),
                effective.scheduleAssignmentId(), effective.calendarAssignmentId(),
                effective.holidayName(), createdAt.toInstant()));
        } catch (DataAccessException ex) {
            entities.clear();
        }
    }

    private ResolvedContext selectContext(Long employeeId, LocalDate workDate) {
        return selectContext(workDate, scheduleAssignments.findByEmployeeIdAndSupersededFalse(employeeId),
            calendarAssignments.findByEmployeeIdAndSupersededFalse(employeeId),
            scheduleRevisions::findById, calendarRevisions::findById);
    }

    private ResolvedContext selectContext(LocalDate workDate,
        List<EmployeeScheduleAssignment> scheduleTimeline, List<EmployeeCalendarAssignment> calendarTimeline,
        java.util.function.Function<Long, Optional<WorkScheduleRevision>> scheduleLookup,
        java.util.function.Function<Long, Optional<HolidayCalendarRevision>> calendarLookup) {
        var schedules = scheduleTimeline.stream().filter(a -> a.covers(workDate)).toList();
        if (schedules.isEmpty()) {
            throw new WorkContextException(WorkContextException.MISSING_SCHEDULE,
                "No schedule assignment covers the work date");
        }
        if (schedules.size() > 1) {
            throw new WorkContextException(WorkContextException.OVERLAPPING_SCHEDULE,
                "Multiple schedule assignments cover the work date");
        }
        var calendars = calendarTimeline.stream().filter(a -> a.covers(workDate)).toList();
        if (calendars.isEmpty()) {
            throw new WorkContextException(WorkContextException.MISSING_CALENDAR,
                "No calendar assignment covers the work date");
        }
        if (calendars.size() > 1) {
            throw new WorkContextException(WorkContextException.OVERLAPPING_CALENDAR,
                "Multiple calendar assignments cover the work date");
        }
        EmployeeScheduleAssignment scheduleAssignment = schedules.get(0);
        WorkScheduleRevision schedule = scheduleLookup.apply(scheduleAssignment.getRevisionId())
            .orElseThrow(() -> new WorkContextException(WorkContextException.MISSING_SCHEDULE,
                "Schedule revision is missing"));
        if (!schedule.isPublished()) {
            throw new WorkContextException(WorkContextException.UNPUBLISHED_REVISION,
                "Schedule revision is not published");
        }
        EmployeeCalendarAssignment calendarAssignment = calendars.get(0);
        HolidayCalendarRevision calendar = calendarLookup.apply(calendarAssignment.getRevisionId())
            .orElseThrow(() -> new WorkContextException(WorkContextException.MISSING_CALENDAR,
                "Calendar revision is missing"));
        if (!calendar.isPublished()) {
            throw new WorkContextException(WorkContextException.UNPUBLISHED_REVISION,
                "Calendar revision is not published");
        }
        return new ResolvedContext(scheduleAssignment, calendarAssignment, schedule, calendar);
    }

    private EffectiveOccurrence buildEffective(Long employeeId, LocalDate workDate, ResolvedContext context) {
        return buildEffective(workDate, context, scheduleDays.findByRevisionId(context.schedule().getId()),
            holidayName(context.calendar().getId(), workDate));
    }

    private EffectiveOccurrence buildEffective(LocalDate workDate, ResolvedContext context,
                                               List<WorkScheduleDay> days, String holidayName) {
        ZoneId zone;
        try {
            zone = ZoneId.of(context.schedule().getTimezone());
        } catch (RuntimeException ex) {
            throw new WorkContextException(WorkContextException.MISSING_SCHEDULE,
                "Schedule timezone is not a valid IANA zone");
        }
        int weekday = workDate.getDayOfWeek().getValue();
        WorkScheduleDay matched = null;
        for (WorkScheduleDay day : days) {
            if (day.getWeekday() == weekday) {
                matched = day;
                break;
            }
        }
        if (matched == null) {
            throw new WorkContextException(WorkContextException.NON_WORKING_DATE,
                "Work date is not a scheduled working date");
        }
        OccurrenceInterval interval = intervalFor(zone, workDate, matched);
        return new EffectiveOccurrence(interval.startAt(), interval.endAt(),
            context.schedule().getTimezone(), context.schedule().getId(), context.calendar().getId(),
            context.scheduleAssignment().getId(), context.calendarAssignment().getId(), holidayName);
    }

    private String holidayName(Long calendarRevisionId, LocalDate workDate) {
        Optional<HolidayDate> holiday =
            holidayDates.findByRevisionIdAndHolidayDay(calendarRevisionId, workDate);
        return holiday.map(HolidayDate::getHolidayName).orElse(null);
    }

    private static boolean detectPostgres(JdbcTemplate jdbc) {
        try {
            String product = jdbc.execute((Connection connection) ->
                connection.getMetaData().getDatabaseProductName());
            return "PostgreSQL".equals(product);
        } catch (DataAccessException ex) {
            return false;
        }
    }

    private record ResolvedContext(EmployeeScheduleAssignment scheduleAssignment,
                                   EmployeeCalendarAssignment calendarAssignment,
                                   WorkScheduleRevision schedule,
                                   HolidayCalendarRevision calendar) {
    }
}
