package id.mydev.peoplecore.attendance.application.service;

import id.mydev.peoplecore.attendance.application.command.WorkCalendarPayloads.AssignmentPayload;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarPayloads.HolidayPayload;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarPayloads.PreviewPayload;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarPayloads.PublishCalendarPayload;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarPayloads.PublishSchedulePayload;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarPayloads.ScheduleDayPayload;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarResults.AssignmentResult;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarResults.ImpactPreview;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarResults.OccurrenceListResult;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarResults.OccurrenceView;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarResults.RevisionResult;
import id.mydev.peoplecore.attendance.domain.exception.ContextChangedException;
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
import id.mydev.peoplecore.attendance.application.service.AttendanceOccurrenceResolver.EffectiveOccurrence;
import id.mydev.peoplecore.attendance.application.service.AttendanceOccurrenceResolver.OccurrenceInterval;
import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.common.command.AdmissionGuard;
import id.mydev.peoplecore.identity.application.service.AccountAccessService;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.organization.application.service.EmployeeService;
import id.mydev.peoplecore.organization.domain.model.Employee;
import jakarta.persistence.EntityManager;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

@Service
public class WorkCalendarService {

    private static final int MAX_PREVIEW_EMPLOYEES = 500;
    private static final long MAX_RANGE_DAYS = 366L;
    private static final LocalDate PATTERN_ANCHOR_MONDAY = LocalDate.of(2026, 10, 12);
    private static final ZoneId PILOT_ZONE = ZoneId.of("Asia/Jakarta");

    private final WorkScheduleRevisionRepository scheduleRevisions;
    private final WorkScheduleDayRepository scheduleDays;
    private final HolidayCalendarRevisionRepository calendarRevisions;
    private final HolidayDateRepository holidayDates;
    private final EmployeeScheduleAssignmentRepository scheduleAssignments;
    private final EmployeeCalendarAssignmentRepository calendarAssignments;
    private final AttendanceOccurrenceRepository occurrences;
    private final AttendanceAccountGuard accountGuard;
    private final AttendanceOccurrenceResolver resolver;
    private final EmployeeService employees;
    private final AccountAccessService access;
    private final AdmissionGuard admission;
    private final EntityManager entities;
    private final Clock clock;

    public WorkCalendarService(
        WorkScheduleRevisionRepository scheduleRevisions,
        WorkScheduleDayRepository scheduleDays,
        HolidayCalendarRevisionRepository calendarRevisions,
        HolidayDateRepository holidayDates,
        EmployeeScheduleAssignmentRepository scheduleAssignments,
        EmployeeCalendarAssignmentRepository calendarAssignments,
        AttendanceOccurrenceRepository occurrences,
        AttendanceAccountGuard accountGuard,
        AttendanceOccurrenceResolver resolver,
        EmployeeService employees,
        AccountAccessService access,
        AdmissionGuard admission,
        EntityManager entities,
        Clock clock
    ) {
        this.scheduleRevisions = scheduleRevisions;
        this.scheduleDays = scheduleDays;
        this.calendarRevisions = calendarRevisions;
        this.holidayDates = holidayDates;
        this.scheduleAssignments = scheduleAssignments;
        this.calendarAssignments = calendarAssignments;
        this.occurrences = occurrences;
        this.accountGuard = accountGuard;
        this.resolver = resolver;
        this.employees = employees;
        this.access = access;
        this.admission = admission;
        this.entities = entities;
        this.clock = clock;
    }

    @Transactional
    public RevisionResult publishSchedule(PublishSchedulePayload payload, Authentication caller) {
        Objects.requireNonNull(payload, "payload must not be null");
        admission.acquire(AdmissionGuard.Mode.EXCLUSIVE);
        entities.flush();
        entities.clear();
        requireOrganizationWideHr(caller, Instant.now(clock));
        validateSchedulePattern(payload.timezone(), payload.days());
        if (scheduleRevisions.findByPublicId(payload.publicId()).isPresent()) {
            throw new WorkContextException(WorkContextException.UNPUBLISHED_REVISION,
                "Schedule revision identifier is already used");
        }
        WorkScheduleRevision revision =
            new WorkScheduleRevision(payload.publicId(), payload.timezone(), Instant.now(clock));
        scheduleRevisions.saveAndFlush(revision);
        scheduleDays.saveAll(buildScheduleDays(revision.getId(), payload.days()));
        entities.flush();
        entities.clear();
        WorkScheduleRevision locked = scheduleRevisions.lockById(revision.getId())
            .orElseThrow(ResourceNotFoundException::new);
        requireOrganizationWideHr(caller, Instant.now(clock));
        locked.publish(Instant.now(clock));
        return new RevisionResult(locked.getPublicId(), locked.getStatus(), locked.getPublishedAt());
    }

    @Transactional
    public RevisionResult publishCalendar(PublishCalendarPayload payload, Authentication caller) {
        Objects.requireNonNull(payload, "payload must not be null");
        admission.acquire(AdmissionGuard.Mode.EXCLUSIVE);
        entities.flush();
        entities.clear();
        requireOrganizationWideHr(caller, Instant.now(clock));
        validateHolidays(payload.holidays());
        if (calendarRevisions.findByPublicId(payload.publicId()).isPresent()) {
            throw new WorkContextException(WorkContextException.UNPUBLISHED_REVISION,
                "Calendar revision identifier is already used");
        }
        HolidayCalendarRevision revision =
            new HolidayCalendarRevision(payload.publicId(), payload.location(), Instant.now(clock));
        calendarRevisions.saveAndFlush(revision);
        holidayDates.saveAll(buildHolidayDates(revision.getId(), payload.holidays()));
        entities.flush();
        entities.clear();
        HolidayCalendarRevision locked = calendarRevisions.lockById(revision.getId())
            .orElseThrow(ResourceNotFoundException::new);
        requireOrganizationWideHr(caller, Instant.now(clock));
        locked.publish(Instant.now(clock));
        return new RevisionResult(locked.getPublicId(), locked.getStatus(), locked.getPublishedAt());
    }

    @Transactional(readOnly = true)
    public ImpactPreview previewImpact(PreviewPayload payload, Authentication caller) {
        Objects.requireNonNull(payload, "payload must not be null");
        admission.acquire(AdmissionGuard.Mode.SHARED);
        entities.clear();
        requirePreviewScope(caller, payload, Instant.now(clock));
        String fingerprint = fingerprint(payload.scheduleRevisionPublicId(),
            payload.calendarRevisionPublicId(), payload.supersedesPublicId(),
            payload.employeePublicIds(), payload.fromDate(), payload.toDate());
        if (payload.fingerprint() != null && !payload.fingerprint().equals(fingerprint)) {
            throw new ContextChangedException("Work context changed since preview");
        }
        long pinned = countPinned(payload.employeePublicIds(), payload.fromDate(), payload.toDate());
        long affectedDates = countAffectedDates(payload.fromDate(), payload.toDate());
        requirePreviewScope(caller, payload, Instant.now(clock));
        return new ImpactPreview(List.copyOf(payload.employeePublicIds()), affectedDates, pinned, fingerprint);
    }

    private UserAccount requirePreviewScope(Authentication caller, PreviewPayload payload, Instant now) {
        UserAccount operator = access.requireHrOperation(caller, now);
        validatePreviewRange(payload);
        for (UUID employeePublicId : payload.employeePublicIds()) {
            Employee employee = employees.requireInternalByPublicId(employeePublicId);
            String orgUnit = employees.currentOrgUnit(employee.getId(), now).orElse(null);
            access.requireTargetScope(operator.getId(), orgUnit, now);
        }
        return operator;
    }

    @Transactional
    public AssignmentResult assignSchedule(AssignmentPayload payload, Authentication caller) {
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(payload.assignmentPublicId(), "assignmentPublicId must not be null");
        admission.acquire(AdmissionGuard.Mode.EXCLUSIVE);
        entities.flush();
        entities.clear();
        Employee employee = employees.requireInternalByPublicId(payload.employeePublicId());
        requireScopedHr(caller, employee.getId(), Instant.now(clock));
        validateAssignmentInterval(payload.fromDate(), payload.toDate(), payload.backdateReason());
        WorkScheduleRevision revision = scheduleRevisions.findByPublicId(payload.revisionPublicId())
            .orElseThrow(ResourceNotFoundException::new);
        if (!revision.isPublished()) {
            throw new WorkContextException(WorkContextException.UNPUBLISHED_REVISION,
                "Schedule revision is not published");
        }
        accountGuard.ensureLocked(employee.getId());
        requireScopedHr(caller, employee.getId(), Instant.now(clock));
        enforceFingerprint(payload, employee, payload.revisionPublicId(), null);
        validateScheduleBoundaries(employee.getId(), payload, revision);
        EmployeeScheduleAssignment assignment =
            insertOrReviseSchedule(payload, employee, revision, Instant.now(clock));
        return new AssignmentResult(assignment.getPublicId(), payload.employeePublicId(),
            payload.revisionPublicId(), assignment.getFromDate(), assignment.getToDate());
    }

    @Transactional
    public AssignmentResult assignCalendar(AssignmentPayload payload, Authentication caller) {
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(payload.assignmentPublicId(), "assignmentPublicId must not be null");
        admission.acquire(AdmissionGuard.Mode.EXCLUSIVE);
        entities.flush();
        entities.clear();
        Employee employee = employees.requireInternalByPublicId(payload.employeePublicId());
        requireScopedHr(caller, employee.getId(), Instant.now(clock));
        validateAssignmentInterval(payload.fromDate(), payload.toDate(), payload.backdateReason());
        HolidayCalendarRevision revision = calendarRevisions.findByPublicId(payload.revisionPublicId())
            .orElseThrow(ResourceNotFoundException::new);
        if (!revision.isPublished()) {
            throw new WorkContextException(WorkContextException.UNPUBLISHED_REVISION,
                "Calendar revision is not published");
        }
        accountGuard.ensureLocked(employee.getId());
        requireScopedHr(caller, employee.getId(), Instant.now(clock));
        enforceFingerprint(payload, employee, null, payload.revisionPublicId());
        EmployeeCalendarAssignment assignment =
            insertOrReviseCalendar(payload, employee, revision, Instant.now(clock));
        return new AssignmentResult(assignment.getPublicId(), payload.employeePublicId(),
            payload.revisionPublicId(), assignment.getFromDate(), assignment.getToDate());
    }

    @Transactional(readOnly = true)
    public OccurrenceListResult queryOccurrences(UUID employeePublicId, LocalDate fromDate,
                                                 LocalDate toDate, Authentication caller) {
        Objects.requireNonNull(employeePublicId, "employeePublicId must not be null");
        Objects.requireNonNull(fromDate, "fromDate must not be null");
        Objects.requireNonNull(toDate, "toDate must not be null");
        if (!fromDate.isBefore(toDate)) {
            throw new IllegalArgumentException("fromDate must be before toDate");
        }
        if (fromDate.plusDays(MAX_RANGE_DAYS).isBefore(toDate)) {
            throw new IllegalArgumentException("Query range must not exceed 366 days");
        }
        admission.acquire(AdmissionGuard.Mode.SHARED);
        entities.clear();
        Employee employee = employees.requireInternalByPublicId(employeePublicId);
        requireWorkContextReader(caller, employee, Instant.now(clock));
        List<OccurrenceView> views = new ArrayList<>();
        for (var day : resolver.queryRange(employee.getId(), fromDate, toDate)) {
            if (day.pinned() != null) {
                views.add(pinnedView(employee.getPublicId(), day.pinned()));
            } else if (day.effective() != null) {
                var effective = day.effective();
                views.add(new OccurrenceView(null, employee.getPublicId(), day.date(), effective.startAt(),
                    effective.endAt(), effective.timezone(), effective.holidayName() != null,
                    effective.holidayName(), false, null));
            } else {
                views.add(new OccurrenceView(null, employee.getPublicId(), day.date(),
                    null, null, null, false, null, false, day.blockedReason()));
            }
        }
        OccurrenceListResult result = new OccurrenceListResult(List.copyOf(views));
        requireWorkContextReader(caller, employee, Instant.now(clock));
        return result;
    }

    private UserAccount requireOrganizationWideHr(Authentication caller, Instant now) {
        UserAccount hr = access.requireHrOperation(caller, now);
        if (!access.hrAuthority(hr.getId(), now).organizationWide()) {
            throw new AccessDeniedException("Schedule publication requires organization-wide HR authority");
        }
        return hr;
    }

    private void requireScopedHr(Authentication caller, Long employeeId, Instant now) {
        String orgUnit = employees.currentOrgUnit(employeeId, now).orElse(null);
        access.requireHrScope(caller, orgUnit, now);
    }

    private void requireWorkContextReader(Authentication caller, Employee employee, Instant now) {
        UserAccount account = access.requireActive(caller, now);
        String orgUnit = employees.currentOrgUnit(employee.getId(), now).orElse(null);
        access.requireEmployeeRead(account, employee.getPublicId(), orgUnit, now);
    }

    private void validateSchedulePattern(String timezone, List<ScheduleDayPayload> days) {
        Objects.requireNonNull(timezone, "timezone must not be null");
        if (!WorkScheduleRevision.PILOT_TIMEZONE.equals(timezone)) {
            throw new IllegalArgumentException("Schedule timezone must be the pilot timezone");
        }
        ZoneId zone = ZoneId.of(timezone);
        Objects.requireNonNull(days, "days must not be null");
        if (days.isEmpty() || days.size() > 7) {
            throw new IllegalArgumentException("Schedule must define 1..7 weekdays");
        }
        Set<Integer> seen = new HashSet<>();
        List<ScheduleDayPayload> ordered = new ArrayList<>(days.size());
        for (ScheduleDayPayload day : days) {
            Objects.requireNonNull(day, "day must not be null");
            Objects.requireNonNull(day.startTime(), "startTime must not be null");
            Objects.requireNonNull(day.endTime(), "endTime must not be null");
            if (!seen.add(day.weekday())) {
                throw new IllegalArgumentException("Duplicate weekday in schedule");
            }
            new WorkScheduleDay(1L, day.weekday(), day.startTime(), day.endTime(), day.overnightOffset());
            ordered.add(day);
        }
        List<OccurrenceInterval> intervals = new ArrayList<>();
        for (ScheduleDayPayload day : ordered) {
            LocalDate date = PATTERN_ANCHOR_MONDAY.plusDays(day.weekday() - 1);
            intervals.add(AttendanceOccurrenceResolver.intervalFor(zone, date,
                new WorkScheduleDay(1L, day.weekday(), day.startTime(), day.endTime(),
                    day.overnightOffset())));
        }
        for (ScheduleDayPayload day : ordered) {
            if (day.weekday() == 1) {
                LocalDate date = PATTERN_ANCHOR_MONDAY.plusDays(7);
                intervals.add(AttendanceOccurrenceResolver.intervalFor(zone, date,
                    new WorkScheduleDay(1L, day.weekday(), day.startTime(), day.endTime(),
                        day.overnightOffset())));
            }
        }
        for (int first = 0; first < intervals.size(); first++) {
            for (int second = first + 1; second < intervals.size(); second++) {
                OccurrenceInterval left = intervals.get(first);
                OccurrenceInterval right = intervals.get(second);
                if (AttendanceOccurrenceResolver.intervalsOverlap(
                    left.startAt(), left.endAt(), right.startAt(), right.endAt())) {
                    throw new IllegalArgumentException("Schedule occurrences overlap in actual time");
                }
            }
        }
    }

    private List<WorkScheduleDay> buildScheduleDays(Long revisionId, List<ScheduleDayPayload> days) {
        List<WorkScheduleDay> rows = new ArrayList<>(days.size());
        for (ScheduleDayPayload day : days) {
            rows.add(new WorkScheduleDay(revisionId, day.weekday(), day.startTime(),
                day.endTime(), day.overnightOffset()));
        }
        return rows;
    }

    private void validateHolidays(List<HolidayPayload> holidays) {
        Objects.requireNonNull(holidays, "holidays must not be null");
        Set<LocalDate> seen = new HashSet<>();
        for (HolidayPayload holiday : holidays) {
            Objects.requireNonNull(holiday, "holiday must not be null");
            Objects.requireNonNull(holiday.holidayDay(), "holidayDay must not be null");
            if (!StringUtils.hasText(holiday.holidayName())) {
                throw new IllegalArgumentException("holidayName must not be blank");
            }
            if (!seen.add(holiday.holidayDay())) {
                throw new IllegalArgumentException("Duplicate holiday date in calendar");
            }
        }
    }

    private List<HolidayDate> buildHolidayDates(Long revisionId, List<HolidayPayload> holidays) {
        List<HolidayDate> rows = new ArrayList<>(holidays.size());
        for (HolidayPayload holiday : holidays) {
            rows.add(new HolidayDate(revisionId, holiday.holidayDay(), holiday.holidayName()));
        }
        return rows;
    }

    private void validatePreviewRange(PreviewPayload payload) {
        Objects.requireNonNull(payload.fromDate(), "fromDate must not be null");
        Objects.requireNonNull(payload.toDate(), "toDate must not be null");
        Objects.requireNonNull(payload.employeePublicIds(), "employeePublicIds must not be null");
        if (!payload.fromDate().isBefore(payload.toDate())) {
            throw new IllegalArgumentException("fromDate must be before toDate");
        }
        if (payload.fromDate().plusDays(MAX_RANGE_DAYS).isBefore(payload.toDate())) {
            throw new IllegalArgumentException("Preview range must not exceed 366 days");
        }
        if (payload.employeePublicIds().isEmpty()
            || payload.employeePublicIds().size() > MAX_PREVIEW_EMPLOYEES) {
            throw new IllegalArgumentException("Preview requires 1..500 employees");
        }
    }

    private String fingerprint(UUID scheduleRevisionPublicId, UUID calendarRevisionPublicId,
                               UUID supersedesPublicId, List<UUID> employeePublicIds,
                               LocalDate fromDate, LocalDate toDate) {
        StringBuilder canonical = new StringBuilder();
        canonical.append(scheduleRevisionPublicId).append('|')
            .append(calendarRevisionPublicId).append('|')
            .append(supersedesPublicId).append('|')
            .append(fromDate).append('|').append(toDate).append('|');
        List<UUID> sorted = new ArrayList<>(employeePublicIds);
        sorted.sort(UUID::compareTo);
        for (UUID employee : sorted) {
            canonical.append(employee).append(',');
        }
        canonical.append('|');
        canonical.append(revisionStatus(scheduleRevisionPublicId, true)).append('|');
        canonical.append(revisionStatus(calendarRevisionPublicId, false)).append('|');
        for (UUID employeePublicId : sorted) {
            canonical.append(employeePublicId).append('=')
                .append(rangeAssignmentKey(employeePublicId, fromDate, toDate)).append(';');
        }
        canonical.append('|').append(countPinned(employeePublicIds, fromDate, toDate));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm not available", ex);
        }
    }

    private String revisionStatus(UUID revisionPublicId, boolean schedule) {
        if (revisionPublicId == null) {
            return "none";
        }
        if (schedule) {
            return scheduleRevisions.findByPublicId(revisionPublicId)
                .map(revision -> revision.getPublicId() + "@" + revision.getStatus())
                .orElseThrow(ResourceNotFoundException::new);
        }
        return calendarRevisions.findByPublicId(revisionPublicId)
            .map(revision -> revision.getPublicId() + "@" + revision.getStatus())
            .orElseThrow(ResourceNotFoundException::new);
    }

    private String rangeAssignmentKey(UUID employeePublicId, LocalDate fromDate, LocalDate toDate) {
        Optional<Employee> employee = employees.findByPublicId(employeePublicId);
        if (employee.isEmpty()) {
            return "unknown";
        }
        Set<String> schedules = new TreeSet<>();
        for (EmployeeScheduleAssignment assignment
            : scheduleAssignments.findByEmployeeIdAndSupersededFalse(employee.get().getId())) {
            if (assignment.overlaps(fromDate, toDate)) {
                schedules.add(assignment.getPublicId().toString());
            }
        }
        Set<String> calendars = new TreeSet<>();
        for (EmployeeCalendarAssignment assignment
            : calendarAssignments.findByEmployeeIdAndSupersededFalse(employee.get().getId())) {
            if (assignment.overlaps(fromDate, toDate)) {
                calendars.add(assignment.getPublicId().toString());
            }
        }
        return String.join("+", schedules) + "/" + String.join("+", calendars);
    }

    private void enforceFingerprint(AssignmentPayload payload, Employee employee,
                                    UUID scheduleRevisionPublicId, UUID calendarRevisionPublicId) {
        if (!StringUtils.hasText(payload.expectedFingerprint())) {
            throw new IllegalArgumentException("expectedFingerprint is required for assignment changes");
        }
        LocalDate effectiveTo = payload.toDate() != null ? payload.toDate()
            : payload.fromDate().plusDays(MAX_RANGE_DAYS);
        String current = fingerprint(scheduleRevisionPublicId, calendarRevisionPublicId,
            payload.supersedesPublicId(), List.of(payload.employeePublicId()),
            payload.fromDate(), effectiveTo);
        if (!payload.expectedFingerprint().equals(current)) {
            throw new ContextChangedException("Work context changed since preview");
        }
    }

    private long countPinned(List<UUID> employeePublicIds, LocalDate fromDate, LocalDate toDate) {
        long pinned = 0L;
        for (UUID employeePublicId : employeePublicIds) {
            Optional<Employee> employee = employees.findByPublicId(employeePublicId);
            if (employee.isEmpty()) {
                continue;
            }
            pinned += occurrences.findByEmployeeIdAndWorkDateBetweenOrderByWorkDateAsc(
                employee.get().getId(), fromDate, toDate.minusDays(1)).size();
        }
        return pinned;
    }

    private long countAffectedDates(LocalDate fromDate, LocalDate toDate) {
        return fromDate.datesUntil(toDate).count();
    }

    private void validateAssignmentInterval(LocalDate fromDate, LocalDate toDate, String backdateReason) {
        Objects.requireNonNull(fromDate, "fromDate must not be null");
        if (toDate != null && !fromDate.isBefore(toDate)) {
            throw new IllegalArgumentException("fromDate must be before toDate");
        }
        LocalDate today = LocalDate.ofInstant(Instant.now(clock), PILOT_ZONE);
        if (fromDate.isBefore(today) && !StringUtils.hasText(backdateReason)) {
            throw new IllegalArgumentException("Backdated assignment requires a reason");
        }
    }

    private void validateScheduleBoundaries(Long employeeId, AssignmentPayload payload,
                                            WorkScheduleRevision revision) {
        ZoneId zone = ZoneId.of(revision.getTimezone());
        List<WorkScheduleDay> newDays = scheduleDays.findByRevisionId(revision.getId());
        for (EmployeeScheduleAssignment neighbor
            : scheduleAssignments.findByEmployeeIdAndSupersededFalse(employeeId)) {
            if (payload.supersedesPublicId() != null
                && neighbor.getPublicId().equals(payload.supersedesPublicId())) {
                continue;
            }
            validateNeighborBoundary(neighbor, payload.fromDate(), payload.toDate(), zone, newDays);
        }
        rejectPinnedOverlap(employeeId, payload, zone, newDays);
    }

    private void rejectPinnedOverlap(Long employeeId, AssignmentPayload payload,
                                     ZoneId zone, List<WorkScheduleDay> newDays) {
        LocalDate from = payload.fromDate();
        LocalDate to = payload.toDate();
        List<AttendanceOccurrence> pins = affectedPins(employeeId, from, to);
        Set<LocalDate> pinnedDates = new HashSet<>();
        for (AttendanceOccurrence pin : pins) {
            pinnedDates.add(pin.getWorkDate());
        }
        for (AttendanceOccurrence pin : pins) {
            rejectPinAgainstCoveredNeighbor(pin, pin.getWorkDate().minusDays(1), from, to,
                pinnedDates, zone, newDays);
            rejectPinAgainstCoveredNeighbor(pin, pin.getWorkDate().plusDays(1), from, to,
                pinnedDates, zone, newDays);
        }
    }

    private List<AttendanceOccurrence> affectedPins(Long employeeId, LocalDate from, LocalDate to) {
        LocalDate lower = from.minusDays(1);
        if (to != null) {
            return occurrences.findByEmployeeIdAndWorkDateBetweenOrderByWorkDateAsc(employeeId, lower, to);
        }
        // ponytail: open-ended assignments must see every pin from the start onward; bounded by pin count, not the date span.
        return occurrences.findByEmployeeIdAndWorkDateGreaterThanEqualOrderByWorkDateAsc(employeeId, lower);
    }

    private void rejectPinAgainstCoveredNeighbor(AttendanceOccurrence pin, LocalDate candidate,
                                                 LocalDate from, LocalDate to, Set<LocalDate> pinnedDates,
                                                 ZoneId zone, List<WorkScheduleDay> newDays) {
        if (candidate.isBefore(from) || (to != null && !candidate.isBefore(to))) {
            return;
        }
        if (pinnedDates.contains(candidate)) {
            return;
        }
        OccurrenceInterval newOccurrence = newInterval(zone, newDays, candidate);
        if (newOccurrence != null && AttendanceOccurrenceResolver.intervalsOverlap(
            pin.getStartAt(), pin.getEndAt(), newOccurrence.startAt(), newOccurrence.endAt())) {
            throw new WorkContextException(WorkContextException.OVERLAPPING_SCHEDULE,
                "Schedule overlaps a pinned occurrence");
        }
    }

    private void validateNeighborBoundary(EmployeeScheduleAssignment neighbor,
                                          LocalDate newFrom, LocalDate newTo,
                                          ZoneId newZone, List<WorkScheduleDay> newDays) {
        Long employeeId = neighbor.getEmployeeId();
        if (neighbor.getToDate() != null && neighbor.getToDate().equals(newFrom)) {
            LocalDate previousDate = newFrom.minusDays(1);
            OccurrenceInterval previous = canonicalInterval(employeeId, previousDate,
                neighborInterval(neighbor, previousDate));
            OccurrenceInterval current = canonicalInterval(employeeId, newFrom,
                newInterval(newZone, newDays, newFrom));
            rejectBoundaryOverlap(previous, current);
        }
        if (newTo != null && neighbor.getFromDate().equals(newTo)) {
            OccurrenceInterval current = canonicalInterval(employeeId, newTo.minusDays(1),
                newInterval(newZone, newDays, newTo.minusDays(1)));
            OccurrenceInterval next = canonicalInterval(employeeId, newTo,
                neighborInterval(neighbor, newTo));
            rejectBoundaryOverlap(current, next);
        }
    }

    private void validateReviseEdges(EmployeeScheduleAssignment original, LocalDate newFrom, LocalDate newTo,
                                     ZoneId newZone, List<WorkScheduleDay> newDays) {
        Long employeeId = original.getEmployeeId();
        if (original.getFromDate().isBefore(newFrom)) {
            OccurrenceInterval previous = canonicalInterval(employeeId, newFrom.minusDays(1),
                neighborInterval(original, newFrom.minusDays(1)));
            OccurrenceInterval current = canonicalInterval(employeeId, newFrom,
                newInterval(newZone, newDays, newFrom));
            rejectBoundaryOverlap(previous, current);
        }
        if (newTo != null && (original.getToDate() == null || newTo.isBefore(original.getToDate()))) {
            OccurrenceInterval lastNew = canonicalInterval(employeeId, newTo.minusDays(1),
                newInterval(newZone, newDays, newTo.minusDays(1)));
            OccurrenceInterval retained = canonicalInterval(employeeId, newTo,
                neighborInterval(original, newTo));
            rejectBoundaryOverlap(lastNew, retained);
        }
    }

    private OccurrenceInterval canonicalInterval(Long employeeId, LocalDate date,
                                                 OccurrenceInterval fallback) {
        Optional<AttendanceOccurrence> pinned = occurrences.findByEmployeeIdAndWorkDate(employeeId, date);
        if (pinned.isPresent()) {
            AttendanceOccurrence occurrence = pinned.get();
            return new OccurrenceInterval(occurrence.getStartAt(), occurrence.getEndAt());
        }
        return fallback;
    }

    private void rejectBoundaryOverlap(OccurrenceInterval first, OccurrenceInterval second) {
        if (first != null && second != null && AttendanceOccurrenceResolver.intervalsOverlap(
            first.startAt(), first.endAt(), second.startAt(), second.endAt())) {
            throw new IllegalArgumentException("Schedule occurrences overlap across the assignment boundary");
        }
    }

    private OccurrenceInterval neighborInterval(EmployeeScheduleAssignment neighbor, LocalDate date) {
        Optional<WorkScheduleRevision> revision = scheduleRevisions.findById(neighbor.getRevisionId());
        if (revision.isEmpty() || !revision.get().isPublished()) {
            return null;
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(revision.get().getTimezone());
        } catch (RuntimeException ex) {
            return null;
        }
        return newInterval(zone, scheduleDays.findByRevisionId(neighbor.getRevisionId()), date);
    }

    private OccurrenceInterval newInterval(ZoneId zone, List<WorkScheduleDay> days, LocalDate date) {
        for (WorkScheduleDay day : days) {
            if (day.getWeekday() == date.getDayOfWeek().getValue()) {
                return AttendanceOccurrenceResolver.intervalFor(zone, date, day);
            }
        }
        return null;
    }

    private EmployeeScheduleAssignment insertOrReviseSchedule(AssignmentPayload payload,
                                                              Employee employee,
                                                              WorkScheduleRevision revision,
                                                              Instant now) {
        if (payload.supersedesPublicId() == null) {
            rejectScheduleOverlap(employee.getId(), payload.fromDate(), payload.toDate(), null);
            return saveScheduleAssignment(payload.assignmentPublicId(), employee.getId(), revision.getId(),
                payload.fromDate(), payload.toDate(), now, null, payload.backdateReason());
        }
        EmployeeScheduleAssignment original = scheduleAssignments
            .findByPublicId(payload.supersedesPublicId())
            .filter(row -> row.getEmployeeId().equals(employee.getId()))
            .orElseThrow(ResourceNotFoundException::new);
        if (original.isSuperseded()) {
            throw new WorkContextException(WorkContextException.ASSIGNMENT_STALE,
                "Schedule assignment was already revised; reload its current revision");
        }
        requireRevisionStart(original, payload.fromDate(), payload.toDate());
        rejectScheduleOverlap(employee.getId(), payload.fromDate(), payload.toDate(), original.getId());
        EmployeeScheduleAssignment locked = scheduleAssignments.lockById(original.getId())
            .orElseThrow(ResourceNotFoundException::new);
        if (locked.isSuperseded()) {
            throw new WorkContextException(WorkContextException.ASSIGNMENT_STALE,
                "Schedule assignment was already revised; reload its current revision");
        }
        validateReviseEdges(locked, payload.fromDate(), payload.toDate(),
            ZoneId.of(revision.getTimezone()), scheduleDays.findByRevisionId(revision.getId()));
        locked.supersede(payload.backdateReason());
        scheduleAssignments.save(locked);
        retainSchedulePrefix(locked, payload.fromDate(), now, payload.backdateReason());
        EmployeeScheduleAssignment replacement = saveScheduleAssignment(payload.assignmentPublicId(),
            employee.getId(), revision.getId(), payload.fromDate(), payload.toDate(), now,
            locked.getPublicId(), payload.backdateReason());
        retainScheduleSuffix(locked, payload.toDate(), now, payload.backdateReason());
        return replacement;
    }

    private void requireRevisionStart(EmployeeScheduleAssignment original, LocalDate newFrom, LocalDate newTo) {
        if (newFrom.isBefore(original.getFromDate())
            || (original.getToDate() != null && !newFrom.isBefore(original.getToDate()))) {
            throw new IllegalArgumentException("Revision must start within the original interval");
        }
        if (newTo != null && !newFrom.isBefore(newTo)) {
            throw new IllegalArgumentException("fromDate must be before toDate");
        }
    }

    private void rejectScheduleOverlap(Long employeeId, LocalDate fromDate, LocalDate toDate, Long ignoredId) {
        for (EmployeeScheduleAssignment assignment
            : scheduleAssignments.findByEmployeeIdAndSupersededFalse(employeeId)) {
            if (ignoredId != null && assignment.getId().equals(ignoredId)) {
                continue;
            }
            if (assignment.overlaps(fromDate, toDate)) {
                throw new WorkContextException(WorkContextException.OVERLAPPING_SCHEDULE,
                    "Schedule assignment overlaps an active assignment");
            }
        }
    }

    private EmployeeScheduleAssignment saveScheduleAssignment(UUID publicId, Long employeeId, Long revisionId,
                                                              LocalDate fromDate, LocalDate toDate, Instant now,
                                                              UUID predecessor, String backdateReason) {
        EmployeeScheduleAssignment assignment = new EmployeeScheduleAssignment(publicId, employeeId,
            revisionId, fromDate, toDate, now, predecessor, backdateReason);
        scheduleAssignments.saveAndFlush(assignment);
        return assignment;
    }

    private void retainSchedulePrefix(EmployeeScheduleAssignment original, LocalDate boundary,
                                      Instant now, String backdateReason) {
        if (!original.getFromDate().isBefore(boundary)) {
            return;
        }
        scheduleAssignments.save(new EmployeeScheduleAssignment(UUID.randomUUID(), original.getEmployeeId(),
            original.getRevisionId(), original.getFromDate(), boundary, now,
            original.getPublicId(), backdateReason));
    }

    private void retainScheduleSuffix(EmployeeScheduleAssignment original, LocalDate newTo,
                                      Instant now, String backdateReason) {
        if (newTo == null) {
            return;
        }
        if (original.getToDate() != null && !newTo.isBefore(original.getToDate())) {
            return;
        }
        scheduleAssignments.save(new EmployeeScheduleAssignment(UUID.randomUUID(), original.getEmployeeId(),
            original.getRevisionId(), newTo, original.getToDate(), now,
            original.getPublicId(), backdateReason));
    }

    private EmployeeCalendarAssignment insertOrReviseCalendar(AssignmentPayload payload,
                                                              Employee employee,
                                                              HolidayCalendarRevision revision,
                                                              Instant now) {
        if (payload.supersedesPublicId() == null) {
            rejectCalendarOverlap(employee.getId(), payload.fromDate(), payload.toDate(), null);
            return saveCalendarAssignment(payload.assignmentPublicId(), employee.getId(), revision.getId(),
                payload.fromDate(), payload.toDate(), now, null, payload.backdateReason());
        }
        EmployeeCalendarAssignment original = calendarAssignments
            .findByPublicId(payload.supersedesPublicId())
            .filter(row -> row.getEmployeeId().equals(employee.getId()))
            .orElseThrow(ResourceNotFoundException::new);
        if (original.isSuperseded()) {
            throw new WorkContextException(WorkContextException.ASSIGNMENT_STALE,
                "Calendar assignment was already revised; reload its current revision");
        }
        if (payload.fromDate().isBefore(original.getFromDate())
            || (original.getToDate() != null && !payload.fromDate().isBefore(original.getToDate()))) {
            throw new IllegalArgumentException("Revision must start within the original interval");
        }
        rejectCalendarOverlap(employee.getId(), payload.fromDate(), payload.toDate(), original.getId());
        EmployeeCalendarAssignment locked = calendarAssignments.lockById(original.getId())
            .orElseThrow(ResourceNotFoundException::new);
        if (locked.isSuperseded()) {
            throw new WorkContextException(WorkContextException.ASSIGNMENT_STALE,
                "Calendar assignment was already revised; reload its current revision");
        }
        locked.supersede(payload.backdateReason());
        calendarAssignments.save(locked);
        if (locked.getFromDate().isBefore(payload.fromDate())) {
            calendarAssignments.save(new EmployeeCalendarAssignment(UUID.randomUUID(), employee.getId(),
                locked.getRevisionId(), locked.getFromDate(), payload.fromDate(), now,
                locked.getPublicId(), payload.backdateReason()));
        }
        EmployeeCalendarAssignment replacement = saveCalendarAssignment(payload.assignmentPublicId(),
            employee.getId(), revision.getId(), payload.fromDate(), payload.toDate(), now,
            locked.getPublicId(), payload.backdateReason());
        if (payload.toDate() != null
            && (locked.getToDate() == null || payload.toDate().isBefore(locked.getToDate()))) {
            calendarAssignments.save(new EmployeeCalendarAssignment(UUID.randomUUID(), employee.getId(),
                locked.getRevisionId(), payload.toDate(), locked.getToDate(), now,
                locked.getPublicId(), payload.backdateReason()));
        }
        return replacement;
    }

    private void rejectCalendarOverlap(Long employeeId, LocalDate fromDate, LocalDate toDate, Long ignoredId) {
        for (EmployeeCalendarAssignment assignment
            : calendarAssignments.findByEmployeeIdAndSupersededFalse(employeeId)) {
            if (ignoredId != null && assignment.getId().equals(ignoredId)) {
                continue;
            }
            if (assignment.overlaps(fromDate, toDate)) {
                throw new WorkContextException(WorkContextException.OVERLAPPING_CALENDAR,
                    "Calendar assignment overlaps an active assignment");
            }
        }
    }

    private EmployeeCalendarAssignment saveCalendarAssignment(UUID publicId, Long employeeId, Long revisionId,
                                                              LocalDate fromDate, LocalDate toDate, Instant now,
                                                              UUID predecessor, String backdateReason) {
        EmployeeCalendarAssignment assignment = new EmployeeCalendarAssignment(publicId, employeeId,
            revisionId, fromDate, toDate, now, predecessor, backdateReason);
        calendarAssignments.saveAndFlush(assignment);
        return assignment;
    }

    private void ensureAccountLocked(Long employeeId) {
        accountGuard.ensureLocked(employeeId);
    }

    private OccurrenceView pinnedView(UUID employeePublicId, AttendanceOccurrence occurrence) {
        return new OccurrenceView(occurrence.getPublicId(), employeePublicId, occurrence.getWorkDate(),
            occurrence.getStartAt(), occurrence.getEndAt(), occurrence.getTimezone(),
            occurrence.isHoliday(), occurrence.getHolidayName(), true, null);
    }
}
