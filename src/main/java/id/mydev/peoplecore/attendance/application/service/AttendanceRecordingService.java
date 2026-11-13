package id.mydev.peoplecore.attendance.application.service;

import id.mydev.peoplecore.attendance.application.command.AttendancePayloads.ClockPayload;
import id.mydev.peoplecore.attendance.application.command.AttendanceResults.ClockResult;
import id.mydev.peoplecore.attendance.application.command.AttendanceResults.EffectiveResult;
import id.mydev.peoplecore.attendance.application.service.AttendanceTimePolicy.Action;
import id.mydev.peoplecore.attendance.domain.exception.WorkContextException;
import id.mydev.peoplecore.attendance.domain.model.AttendanceOccurrence;
import id.mydev.peoplecore.attendance.domain.repository.AttendanceRecordingRepository;
import id.mydev.peoplecore.attendance.domain.repository.AttendanceRecordingRepository.EffectiveRecord;
import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.common.command.AdmissionGuard;
import id.mydev.peoplecore.identity.application.service.AccountAccessService;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.organization.application.service.EmployeeService;
import id.mydev.peoplecore.organization.domain.model.Employee;
import jakarta.persistence.EntityManager;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class AttendanceRecordingService {
    private static final ZoneId PILOT_ZONE = ZoneId.of("Asia/Jakarta");
    private static final Set<String> MISSING_CONTEXT_REASONS = Set.of(
        WorkContextException.MISSING_SCHEDULE, WorkContextException.MISSING_CALENDAR);
    private final AttendanceOccurrenceResolver resolver;
    private final AttendanceAccountGuard guard;
    private final AccountAccessService access;
    private final EmployeeService employees;
    private final AdmissionGuard admission;
    private final EntityManager entities;
    private final AttendanceRecordingRepository recordings;
    private final Clock clock;

    public AttendanceRecordingService(AttendanceOccurrenceResolver resolver, AttendanceAccountGuard guard,
        AccountAccessService access, EmployeeService employees, AdmissionGuard admission,
        EntityManager entities, AttendanceRecordingRepository recordings, Clock clock) {
        this.resolver = resolver;
        this.guard = guard;
        this.access = access;
        this.employees = employees;
        this.admission = admission;
        this.entities = entities;
        this.recordings = recordings;
        this.clock = clock;
    }

    @Transactional
    public ClockResult record(ClockPayload payload, Authentication caller, Instant receivedAt) {
        if (payload == null || payload.action() == null) {
            throw new IllegalArgumentException("Action is required");
        }
        admission.acquire(AdmissionGuard.Mode.SHARED);
        entities.flush();
        entities.clear();
        Employee employee = requireSelf(caller, Instant.now(clock));
        guard.ensureLocked(employee.getId());
        Instant acceptedAt = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        employee = requireSelf(caller, acceptedAt);
        UUID employeePublicId = employeePublic(caller, acceptedAt);
        List<ClockResult> recent = recordings.findRecent(employee.getId(), payload.action().name(),
                acceptedAt.minusSeconds(60), acceptedAt).stream()
            .map(event -> new ClockResult(event.publicId(), employeePublicId, event.occurrencePublicId(),
                event.action(), event.receivedAt(), event.acceptedAt(), true))
            .toList();
        if (!recent.isEmpty()) {
            if (payload.action() == Action.IN) {
                if (recordings.countOuts(recent.getFirst().occurrencePublicId()) > 0) {
                    throw conflict("ACTION_ALREADY_RECORDED", "The occurrence is already complete");
                }
            }
            return recent.getFirst();
        }
        LocalDate date = acceptedAt.atZone(PILOT_ZONE).toLocalDate();
        List<LocalDate> candidates = candidates(employee, date, acceptedAt, payload.action());
        if (candidates.size() != 1) {
            throw conflict("AMBIGUOUS_OCCURRENCE", "Action requires exactly one applicable work occurrence");
        }
        LocalDate workDate = candidates.getFirst();
        requireEmployment(employee, workDate);
        AttendanceOccurrence occurrence = resolver.resolveOrPin(employee.getId(), workDate);
        Optional<EffectiveResult> effective = currentEvidence(occurrence.getId());
        if (payload.action() == Action.IN && effective.isPresent()) {
            throw conflict("ACTION_ALREADY_RECORDED", "An effective IN already exists");
        }
        if (payload.action() == Action.OUT && (effective.isEmpty() || effective.get().outAt() != null
            || !acceptedAt.isAfter(effective.get().inAt()))) {
            throw conflict("INVALID_ACTION_ORDER", "OUT requires an earlier IN and an unfilled OUT");
        }
        UUID eventId = UUID.randomUUID();
        requireSelf(caller, Instant.now(clock));
        recordings.insertClockEvent(eventId, occurrence.getId(), employee.getId(), payload.action().name(),
            receivedAt, acceptedAt);
        appendEffective(occurrence.getId(), effective.map(EffectiveResult::publicId).orElse(null),
            payload.action() == Action.IN ? acceptedAt : effective.orElseThrow().inAt(),
            payload.action() == Action.OUT ? acceptedAt : null, eventId, acceptedAt);
        return new ClockResult(eventId, employee.getPublicId(), occurrence.getPublicId(),
            payload.action().name(), receivedAt, acceptedAt, false);
    }

    private UUID employeePublic(Authentication caller, Instant now) {
        return access.ownEmployeePublicId(access.requireActive(caller, now))
            .orElseThrow(ResourceNotFoundException::new);
    }

    private List<LocalDate> candidates(Employee employee, LocalDate today, Instant at, Action action) {
        List<LocalDate> result = new ArrayList<>();
        WorkContextException missingContext = null;
        for (int offset = -2; offset <= 1; offset++) {
            LocalDate date = today.plusDays(offset);
            try {
                Optional<AttendanceOccurrence> pinned = resolver.findPinned(employee.getId(), date);
                Instant start;
                Instant end;
                boolean holiday;
                if (pinned.isPresent()) {
                    start = pinned.get().getStartAt(); end = pinned.get().getEndAt();
                    holiday = pinned.get().isHoliday();
                } else {
                    var context = resolver.computeEffective(employee.getId(), date);
                    start = context.startAt(); end = context.endAt(); holiday = context.holidayName() != null;
                }
                if (!holiday && AttendanceTimePolicy.accepts(action, start, end, at)) {
                    result.add(date);
                }
            } catch (WorkContextException ex) {
                if (WorkContextException.NON_WORKING_DATE.equals(ex.getReason())) {
                    continue;
                }
                if (MISSING_CONTEXT_REASONS.contains(ex.getReason())) {
                    if (missingContext == null) {
                        missingContext = ex;
                    }
                    continue;
                }
                throw ex;
            }
        }
        if (result.isEmpty() && missingContext != null) {
            throw missingContext;
        }
        return result;
    }

    Employee requireSelf(Authentication caller, Instant now) {
        UserAccount account = access.requireActive(caller, now);
        if (access.activeRoles(account.getId(), now).isEmpty()) {
            throw new ResourceNotFoundException();
        }
        return employees.requireInternalByPublicId(access.ownEmployeePublicId(account)
            .orElseThrow(ResourceNotFoundException::new));
    }

    static void requireEmployment(Employee employee, LocalDate date) {
        if (!employee.isActive() || employee.getEmploymentStartDate() == null
            || date.isBefore(employee.getEmploymentStartDate())
            || (employee.getEmploymentEndDate() != null && date.isAfter(employee.getEmploymentEndDate()))) {
            throw new IllegalArgumentException("Work date is outside employment dates");
        }
    }

    Optional<EffectiveResult> currentEvidence(Long occurrenceId) {
        return recordings.findCurrentEvidence(occurrenceId)
            .map(AttendanceRecordingService::toEffective);
    }

    void appendEffective(Long occurrenceId, UUID predecessor, Instant in, Instant out, UUID sourceId, Instant now) {
        recordings.insertEffective(UUID.randomUUID(), occurrenceId, predecessor, in, out, sourceId, now);
    }

    private static EffectiveResult toEffective(EffectiveRecord record) {
        return new EffectiveResult(record.publicId(), record.inAt(), record.outAt());
    }

    static WorkContextException conflict(String reason, String message) {
        return new WorkContextException(reason, message);
    }
}
