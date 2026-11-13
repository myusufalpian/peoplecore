package id.mydev.peoplecore.leave.application.service;

import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.common.command.AdmissionGuard;
import id.mydev.peoplecore.common.command.CommandConflictException;
import id.mydev.peoplecore.identity.application.service.AccountAccessService;
import id.mydev.peoplecore.leave.domain.repository.LeaveEntitlementRepository;
import id.mydev.peoplecore.leave.domain.repository.LeaveEntitlementRepository.BalanceRecord;
import id.mydev.peoplecore.leave.domain.repository.LeaveEntitlementRepository.GrantRecord;
import id.mydev.peoplecore.organization.application.service.EmployeeService;
import id.mydev.peoplecore.organization.domain.model.Employee;
import jakarta.persistence.EntityManager;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

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
    private final LeaveEntitlementRepository ledger;
    private final AdmissionGuard admission;
    private final AccountAccessService access;
    private final EmployeeService employees;
    private final EntityManager entities;
    private final Clock clock;

    public LeaveEntitlementService(LeaveEntitlementRepository ledger, AdmissionGuard admission,
            AccountAccessService access, EmployeeService employees, EntityManager entities, Clock clock) {
        this.ledger = ledger;
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
        List<GrantRecord> existing = ledger.findGrants(period, type, reference);
        if (!existing.isEmpty()) {
            GrantRecord original = existing.getFirst();
            if (original.halfUnits() != payload.halfUnits()
                || !Objects.equals(original.source(), payload.source())
                || !Objects.equals(original.reason(), payload.reason())
                || !Objects.equals(original.sourceReference(), payload.businessReference())) {
                throw new CommandConflictException("Business reference already has different values");
            }
            return balance(employee.getId(), employee.getPublicId(), payload.year());
        }
        if (type.equals("ADJUSTMENT")
            && balance(employee.getId(), employee.getPublicId(), payload.year()).available() + payload.halfUnits() < 0) {
            throw new IllegalArgumentException("Adjustment cannot reduce availability below zero");
        }
        requireHr(caller, employee.getId());
        append(period, type, reference, payload.halfUnits(), null, null, null, payload.source(), payload.reason(),
            payload.businessReference());
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
        long afterId = afterEntryId == null ? 0 : ledger.ledgerEntryId(afterEntryId, employee.getId(), year)
            .orElseThrow(ResourceNotFoundException::new);
        List<LedgerEntry> result = ledger.findLedger(employee.getId(), year, afterId).stream()
            .map(record -> new LedgerEntry(record.publicId(), record.type(), record.businessReference(),
                record.sourceReference(), record.halfUnits(), record.source(), record.reason(), record.createdAt()))
            .toList();
        requireRead(caller, employee);
        return result;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(Long employeeId) {
        ledger.lock(employeeId, Instant.now(clock));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockExisting(Long employeeId) {
        return ledger.lockExisting(employeeId);
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
        List<Integer> existing = ledger.findReservationUnits(period, requestId, revision);
        if (!existing.isEmpty()) {
            if (existing.getFirst() != units) {
                throw new IllegalArgumentException("Reservation differs");
            }
            return;
        }
        if (balance(employeeId, null, year).available() < units) {
            throw new IllegalArgumentException("Insufficient entitlement");
        }
        ledger.insertReservation(period, requestId, revision, units, Instant.now(clock));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void release(Long employeeId, int year, UUID requestId, int revision) {
        lock(employeeId);
        long period = period(employeeId, year);
        ledger.releaseReservation(period, requestId, revision);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void consume(Long employeeId, int year, UUID requestId, int revision) {
        lock(employeeId);
        long period = period(employeeId, year);
        String reference = requestId + ":" + revision;
        if (entry(period, "CONSUMPTION", reference) != null) {
            return;
        }
        List<Integer> units = ledger.findReservationUnits(period, requestId, revision);
        if (units.size() != 1) {
            throw new IllegalArgumentException("Exact active reservation required");
        }
        append(period, "CONSUMPTION", reference, units.getFirst(), requestId, revision, null, "FINAL_APPROVAL",
            "Annual leave final approval", reference);
        release(employeeId, year, requestId, revision);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void reverse(Long employeeId, int year, UUID requestId, int revision) {
        lock(employeeId);
        long period = period(employeeId, year);
        Long original = entry(period, "CONSUMPTION", requestId + ":" + revision);
        if (original == null) {
            throw new ResourceNotFoundException();
        }
        String reference = original.toString();
        if (entry(period, "REVERSAL", reference) != null) {
            return;
        }
        Integer units = ledger.findEntryUnits(original);
        if (units == null) {
            throw new ResourceNotFoundException();
        }
        append(period, "REVERSAL", reference, units, requestId, revision, original, "CANCELLATION",
            "Approved whole cancellation", reference);
    }

    private Long entry(long period, String type, String reference) {
        return ledger.findEntryId(period, type, reference).orElse(null);
    }

    private long period(Long employeeId, int year) {
        validateYear(year);
        return ledger.period(employeeId, year);
    }

    private Balance balance(Long employeeId, UUID publicId, int year) {
        BalanceRecord totals = ledger.balance(employeeId, year);
        return new Balance(publicId, year, totals.granted(), totals.adjusted(), totals.consumed(),
            totals.reserved(), totals.granted() + totals.adjusted() - totals.consumed() - totals.reserved());
    }

    private void append(long period, String type, String reference, int units, UUID requestId,
                        Integer revision, Long original, String source, String reason, String sourceReference) {
        ledger.appendLedger(period, type, reference, units, requestId, revision, original, source, reason,
            sourceReference, Instant.now(clock));
    }

    private void requireHr(Authentication caller, Long employeeId) {
        Instant now = Instant.now(clock);
        access.requireHrScope(caller, employees.currentOrgUnit(employeeId, now).orElse(null), now);
    }

    private void requireRead(Authentication caller, Employee employee) {
        Instant now = Instant.now(clock);
        access.requireEmployeeRead(access.requireActive(caller, now), employee.getPublicId(),
            employees.currentOrgUnit(employee.getId(), now).orElse(null), now);
    }

    private static void validate(GrantPayload payload) {
        validateYear(payload.year());
        if (payload.employeePublicId() == null || payload.halfUnits() == 0
            || !StringUtils.hasText(payload.businessReference()) || payload.businessReference().length() > 200
            || !StringUtils.hasText(payload.source()) || payload.source().length() > 200
            || !StringUtils.hasText(payload.reason()) || payload.reason().length() > 1000) {
            throw new IllegalArgumentException("Employee, nonzero amount, reference, source and reason required");
        }
    }

    private static void validateYear(int year) {
        if (year < 1900 || year > 9998) {
            throw new IllegalArgumentException("Invalid entitlement year");
        }
    }

    private static void validateEffect(UUID requestId, int revision, int units) {
        if (requestId == null || revision < 1 || units < 1) {
            throw new IllegalArgumentException("Invalid leave effect");
        }
    }
}
