package id.mydev.peoplecore.attendance.application.service;

import id.mydev.peoplecore.attendance.application.command.AttendancePayloads.ClockPayload;
import id.mydev.peoplecore.attendance.application.command.AttendanceResults.ClockResult;
import id.mydev.peoplecore.attendance.application.command.AttendanceResults.EffectiveResult;
import id.mydev.peoplecore.attendance.application.service.AttendanceTimePolicy.Action;
import id.mydev.peoplecore.attendance.domain.exception.WorkContextException;
import id.mydev.peoplecore.attendance.domain.model.AttendanceOccurrence;
import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.common.command.AdmissionGuard;
import id.mydev.peoplecore.identity.application.service.AccountAccessService;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.organization.application.service.EmployeeService;
import id.mydev.peoplecore.organization.domain.model.Employee;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class AttendanceRecordingService {
    private static final ZoneId PILOT_ZONE = ZoneId.of("Asia/Jakarta");
    private final AttendanceOccurrenceResolver resolver;
    private final AttendanceAccountGuard guard;
    private final AccountAccessService access;
    private final EmployeeService employees;
    private final AdmissionGuard admission;
    private final EntityManager entities;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AttendanceRecordingService(AttendanceOccurrenceResolver resolver, AttendanceAccountGuard guard,
        AccountAccessService access, EmployeeService employees, AdmissionGuard admission,
        EntityManager entities, JdbcTemplate jdbc, Clock clock) {
        this.resolver = resolver;
        this.guard = guard;
        this.access = access;
        this.employees = employees;
        this.admission = admission;
        this.entities = entities;
        this.jdbc = jdbc;
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
        List<ClockResult> recent = jdbc.query("SELECT e.public_id, o.public_id AS occurrence_public_id, "
            + "e.action, e.received_at, e.accepted_at FROM clock_event e "
            + "JOIN attendance_occurrence o ON o.id=e.occurrence_id "
            + "WHERE e.employee_id=? AND e.action=? AND e.accepted_at>=? AND e.accepted_at<=? "
            + "ORDER BY e.accepted_at DESC", (rs, row) -> new ClockResult(
                rs.getObject("public_id", UUID.class), employeePublic(caller, acceptedAt),
                rs.getObject("occurrence_public_id", UUID.class), rs.getString("action"),
                rs.getTimestamp("received_at").toInstant(), rs.getTimestamp("accepted_at").toInstant(), true),
            employee.getId(), payload.action().name(), Timestamp.from(acceptedAt.minusSeconds(60)),
            Timestamp.from(acceptedAt));
        if (!recent.isEmpty()) {
            if (payload.action()==Action.IN) {
                Long outs=jdbc.queryForObject("SELECT count(e.id) FROM clock_event e JOIN attendance_occurrence o "
                    + "ON o.id=e.occurrence_id WHERE o.public_id=? AND e.action='OUT'",Long.class,
                    recent.getFirst().occurrencePublicId());
                if (outs>0) throw conflict("ACTION_ALREADY_RECORDED","The occurrence is already complete");
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
        jdbc.update("INSERT INTO clock_event(public_id,occurrence_id,employee_id,action,received_at,accepted_at) "
            + "VALUES(?,?,?,?,?,?)", eventId, occurrence.getId(), employee.getId(), payload.action().name(),
            Timestamp.from(receivedAt), Timestamp.from(acceptedAt));
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
                if (!WorkContextException.NON_WORKING_DATE.equals(ex.getReason())) {
                    throw ex;
                }
            }
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
        return jdbc.query("SELECT public_id,in_at,out_at FROM attendance_effective_revision "
            + "WHERE occurrence_id=? ORDER BY id DESC LIMIT 1", (rs, row) -> new EffectiveResult(
                rs.getObject("public_id", UUID.class), rs.getTimestamp("in_at").toInstant(),
                rs.getTimestamp("out_at") == null ? null : rs.getTimestamp("out_at").toInstant()),
            occurrenceId).stream().findFirst();
    }

    void appendEffective(Long occurrenceId, UUID predecessor, Instant in, Instant out, UUID sourceId, Instant now) {
        jdbc.update("INSERT INTO attendance_effective_revision(public_id,occurrence_id,predecessor_id,"
            + "in_at,out_at,source_id,created_at) VALUES(?,?,?,?,?,?,?)", UUID.randomUUID(), occurrenceId,
            predecessor, Timestamp.from(in), out == null ? null : Timestamp.from(out), sourceId, Timestamp.from(now));
    }

    static WorkContextException conflict(String reason, String message) {
        return new WorkContextException(reason, message);
    }
}
