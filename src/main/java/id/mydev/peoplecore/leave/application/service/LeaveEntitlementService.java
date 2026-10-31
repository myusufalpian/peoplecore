package id.mydev.peoplecore.leave.application.service;

import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.common.command.AdmissionGuard;
import id.mydev.peoplecore.common.command.CommandConflictException;
import id.mydev.peoplecore.identity.application.service.AccountAccessService;
import id.mydev.peoplecore.organization.application.service.EmployeeService;
import id.mydev.peoplecore.organization.domain.model.Employee;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@Transactional
public class LeaveEntitlementService {
    public record Balance(UUID employeePublicId, int year, long granted, long adjusted,
                          long consumed, long reserved, long available) { }
    public record LedgerEntry(UUID publicId, String type, String businessReference, String sourceReference, int halfUnits, String source, String reason, Instant createdAt) { }
    public record GrantPayload(UUID employeePublicId, int year, int halfUnits,
                               String businessReference, String source, String reason) { }
    private record ExistingGrant(int halfUnits, String source, String reason, String sourceReference) { }
    private final JdbcTemplate jdbc;
    private final AdmissionGuard admission;
    private final AccountAccessService access;
    private final EmployeeService employees;
    private final EntityManager entities;
    private final Clock clock;

    public LeaveEntitlementService(JdbcTemplate jdbc, AdmissionGuard admission,
            AccountAccessService access, EmployeeService employees, EntityManager entities, Clock clock) {
        this.jdbc = jdbc;
        this.admission = admission;
        this.access = access;
        this.employees = employees;
        this.entities = entities;
        this.clock = clock;
    }

    public Balance opening(GrantPayload payload, Authentication caller) {
        return grant(payload, caller, "OPENING");
    }

    public Balance adjust(GrantPayload payload, Authentication caller) {
        return grant(payload, caller, "ADJUSTMENT");
    }

    private Balance grant(GrantPayload payload, Authentication caller, String type) {
        validate(payload);
        if (type.equals("OPENING") && payload.halfUnits() <= 0) {
            throw new IllegalArgumentException("Opening must be positive");
        }
        admission.acquire(AdmissionGuard.Mode.SHARED);
        entities.flush();
        entities.clear();
        Employee employee = employees.requireInternalByPublicId(payload.employeePublicId());
        requireHr(caller, employee.getId());
        lock(employee.getId());
        entities.clear();
        requireHr(caller, employee.getId());
        long period = period(employee.getId(), payload.year());
        String reference = type.equals("OPENING") ? "OPENING" : payload.businessReference();
        List<ExistingGrant> existing = jdbc.query("SELECT half_units,source,reason,source_reference FROM leave_ledger WHERE period_id=? AND entry_type=? AND business_reference=?",
            (row, index) -> new ExistingGrant(row.getInt(1),row.getString(2),row.getString(3),row.getString(4)), period, type, reference);
        if (!existing.isEmpty()) {
            ExistingGrant original = existing.getFirst();
            if (original.halfUnits() != payload.halfUnits()
                || !Objects.equals(original.source(), payload.source())
                || !Objects.equals(original.reason(), payload.reason())
                || !Objects.equals(original.sourceReference(), payload.businessReference())) {
                throw new CommandConflictException("Business reference already has different values");
            }
            return balance(employee.getId(), employee.getPublicId(), payload.year());
        }
        if (type.equals("ADJUSTMENT") && balance(employee.getId(), employee.getPublicId(), payload.year()).available() + payload.halfUnits() < 0) {
            throw new IllegalArgumentException("Adjustment cannot reduce availability below zero");
        }
        requireHr(caller, employee.getId());
        append(period, type, reference, payload.halfUnits(), null, null, null, payload.source(), payload.reason(), payload.businessReference());
        return balance(employee.getId(), employee.getPublicId(), payload.year());
    }

    public Balance query(UUID employeePublicId, int year, Authentication caller) {
        validateYear(year);
        admission.acquire(AdmissionGuard.Mode.SHARED);
        entities.clear();
        Employee employee = employees.requireInternalByPublicId(employeePublicId);
        requireRead(caller, employee);
        Balance result = balance(employee.getId(), employeePublicId, year);
        requireRead(caller, employee);
        return result;
    }

    public List<LedgerEntry> ledger(UUID employeePublicId, int year, UUID afterEntryId, Authentication caller) {
        validateYear(year);
        admission.acquire(AdmissionGuard.Mode.SHARED);
        entities.clear();
        Employee employee = employees.requireInternalByPublicId(employeePublicId);
        requireRead(caller, employee);
        long afterId = afterEntryId == null ? 0 : jdbc.query("SELECT l.id FROM leave_ledger l JOIN leave_period p ON p.id=l.period_id WHERE l.public_id=? AND p.employee_id=? AND p.period_year=?", (row,index) -> row.getLong(1), afterEntryId, employee.getId(), year).stream().findFirst().orElseThrow(ResourceNotFoundException::new);
        List<LedgerEntry> result = jdbc.query("SELECT l.public_id,l.entry_type,l.business_reference,l.source_reference,l.half_units,l.source,l.reason,l.created_at FROM leave_ledger l JOIN leave_period p ON p.id=l.period_id WHERE p.employee_id=? AND p.period_year=? AND l.id>? ORDER BY l.id LIMIT 100",
            (row,index) -> new LedgerEntry(row.getObject(1,UUID.class),row.getString(2),row.getString(3),row.getString(4),row.getInt(5),row.getString(6),row.getString(7),row.getTimestamp(8).toInstant()),employee.getId(),year,afterId);
        requireRead(caller, employee);
        return result;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(Long employeeId) {
        jdbc.update("INSERT INTO leave_account(employee_id,created_at) VALUES (?,?) ON CONFLICT(employee_id) DO NOTHING",
            employeeId, Timestamp.from(Instant.now(clock)));
        jdbc.queryForObject("SELECT employee_id FROM leave_account WHERE employee_id=? FOR UPDATE", Long.class, employeeId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockExisting(Long employeeId) {
        return !jdbc.query("SELECT employee_id FROM leave_account WHERE employee_id=? FOR UPDATE",
            (row, index) -> row.getLong(1), employeeId).isEmpty();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockPeriod(Long employeeId, int year) {
        lock(employeeId);
        period(employeeId, year);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void reserve(Long employeeId, int year, UUID requestId, int revision, int units) {
        validateEffect(requestId, revision, units);
        if (!LocalDate.now(clock.withZone(ZoneId.of("Asia/Jakarta"))).isBefore(LocalDate.of(year + 1, 1, 1))) {
            throw new IllegalArgumentException("Expired entitlement cannot fund a new reservation");
        }
        lock(employeeId);
        long period = period(employeeId, year);
        List<Integer> existing = jdbc.query("SELECT half_units FROM leave_reservation WHERE request_public_id=? AND request_revision=? AND period_id=? AND active=TRUE",
            (row, index) -> row.getInt(1), requestId, revision, period);
        if (!existing.isEmpty()) {
            if (existing.getFirst() != units) throw new IllegalArgumentException("Reservation differs");
            return;
        }
        if (balance(employeeId, null, year).available() < units) throw new IllegalArgumentException("Insufficient entitlement");
        jdbc.update("INSERT INTO leave_reservation(period_id,request_public_id,request_revision,half_units,active,created_at) VALUES (?,?,?,?,TRUE,?)",
            period, requestId, revision, units, Timestamp.from(Instant.now(clock)));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void release(Long employeeId, int year, UUID requestId, int revision) {
        lock(employeeId);
        long period = period(employeeId, year);
        jdbc.update("UPDATE leave_reservation SET active=FALSE WHERE period_id=? AND request_public_id=? AND request_revision=? AND active=TRUE", period, requestId, revision);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void consume(Long employeeId, int year, UUID requestId, int revision) {
        lock(employeeId);
        long period = period(employeeId, year);
        String reference = requestId + ":" + revision;
        if (entry(period, "CONSUMPTION", reference) != null) return;
        List<Integer> units = jdbc.query("SELECT half_units FROM leave_reservation WHERE period_id=? AND request_public_id=? AND request_revision=? AND active=TRUE",
            (row, index) -> row.getInt(1), period, requestId, revision);
        if (units.size() != 1) throw new IllegalArgumentException("Exact active reservation required");
        append(period, "CONSUMPTION", reference, units.getFirst(), requestId, revision, null, "FINAL_APPROVAL", "Annual leave final approval", reference);
        release(employeeId, year, requestId, revision);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void reverse(Long employeeId, int year, UUID requestId, int revision) {
        lock(employeeId);
        long period = period(employeeId, year);
        Long original = entry(period, "CONSUMPTION", requestId + ":" + revision);
        if (original == null) throw new ResourceNotFoundException();
        String reference = original.toString();
        if (entry(period, "REVERSAL", reference) != null) return;
        Integer units = jdbc.queryForObject("SELECT half_units FROM leave_ledger WHERE id=?", Integer.class, original);
        if (units == null) throw new ResourceNotFoundException();
        append(period, "REVERSAL", reference, units, requestId, revision, original, "CANCELLATION", "Approved whole cancellation", reference);
    }

    private Long entry(long period, String type, String reference) {
        return jdbc.query("SELECT id FROM leave_ledger WHERE period_id=? AND entry_type=? AND business_reference=?",
            (row, index) -> row.getLong(1), period, type, reference).stream().findFirst().orElse(null);
    }

    private long period(Long employeeId, int year) {
        validateYear(year);
        jdbc.update("INSERT INTO leave_period(employee_id,period_year) VALUES (?,?) ON CONFLICT(employee_id,period_year) DO NOTHING", employeeId, year);
        Long id = jdbc.queryForObject("SELECT id FROM leave_period WHERE employee_id=? AND period_year=? FOR UPDATE", Long.class, employeeId, year);
        if (id == null) throw new ResourceNotFoundException();
        return id;
    }

    private Balance balance(Long employeeId, UUID publicId, int year) {
        List<long[]> sums = jdbc.query("SELECT COALESCE(SUM(CASE WHEN l.entry_type='OPENING' THEN l.half_units ELSE 0 END),0), COALESCE(SUM(CASE WHEN l.entry_type='ADJUSTMENT' THEN l.half_units ELSE 0 END),0), COALESCE(SUM(CASE WHEN l.entry_type='CONSUMPTION' THEN l.half_units WHEN l.entry_type='REVERSAL' THEN -l.half_units ELSE 0 END),0), (SELECT COALESCE(SUM(r.half_units),0) FROM leave_reservation r JOIN leave_period rp ON rp.id=r.period_id WHERE rp.employee_id=? AND rp.period_year=? AND r.active=TRUE) FROM leave_period p LEFT JOIN leave_ledger l ON l.period_id=p.id WHERE p.employee_id=? AND p.period_year=?",
            (row, index) -> new long[]{row.getLong(1),row.getLong(2),row.getLong(3),row.getLong(4)}, employeeId, year, employeeId, year);
        long[] totals = sums.getFirst();
        long held = totals[3];
        return new Balance(publicId, year, totals[0], totals[1], totals[2], held, totals[0]+totals[1]-totals[2]-held);
    }

    private void append(long period, String type, String reference, int units, UUID requestId,
                        Integer revision, Long original, String source, String reason, String sourceReference) {
        jdbc.update("INSERT INTO leave_ledger(public_id,period_id,entry_type,business_reference,half_units,request_public_id,request_revision,original_entry_id,source,reason,source_reference,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
            UUID.randomUUID(),period,type,reference,units,requestId,revision,original,source,reason,sourceReference,Timestamp.from(Instant.now(clock)));
    }

    private void requireHr(Authentication caller, Long employeeId) {
        Instant now = Instant.now(clock);
        access.requireHrScope(caller, employees.currentOrgUnit(employeeId,now).orElse(null), now);
    }

    private void requireRead(Authentication caller, Employee employee) {
        Instant now = Instant.now(clock);
        access.requireEmployeeRead(access.requireActive(caller,now), employee.getPublicId(), employees.currentOrgUnit(employee.getId(),now).orElse(null), now);
    }

    private static void validate(GrantPayload payload) {
        validateYear(payload.year());
        if (payload.employeePublicId() == null || payload.halfUnits() == 0 || !StringUtils.hasText(payload.businessReference()) || payload.businessReference().length()>200 || !StringUtils.hasText(payload.source()) || payload.source().length()>200 || !StringUtils.hasText(payload.reason()) || payload.reason().length()>1000) {
            throw new IllegalArgumentException("Employee, nonzero amount, reference, source and reason required");
        }
    }

    private static void validateYear(int year) {
        if (year<1900 || year>9998) throw new IllegalArgumentException("Invalid entitlement year");
    }

    private static void validateEffect(UUID requestId, int revision, int units) {
        if (requestId == null || revision<1 || units<1) throw new IllegalArgumentException("Invalid leave effect");
    }
}
