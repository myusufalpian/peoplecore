package id.mydev.peoplecore.leave.domain.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LeaveEntitlementRepository {
    void lock(long employeeId, Instant now);

    boolean lockExisting(long employeeId);

    long period(long employeeId, int year);

    List<GrantRecord> findGrants(long periodId, String entryType, String businessReference);

    void appendLedger(long periodId, String entryType, String businessReference, int halfUnits, UUID requestId,
                      Integer revision, Long originalEntryId, String source, String reason, String sourceReference,
                      Instant createdAt);

    Optional<Long> ledgerEntryId(UUID entryPublicId, long employeeId, int year);

    List<LedgerRecord> findLedger(long employeeId, int year, long afterId);

    List<Integer> findReservationUnits(long periodId, UUID requestId, int revision);

    void insertReservation(long periodId, UUID requestId, int revision, int halfUnits, Instant createdAt);

    void releaseReservation(long periodId, UUID requestId, int revision);

    Optional<Long> findEntryId(long periodId, String entryType, String businessReference);

    Integer findEntryUnits(long entryId);

    BalanceRecord balance(long employeeId, int year);

    record GrantRecord(int halfUnits, String source, String reason, String sourceReference) { }

    record LedgerRecord(UUID publicId, String type, String businessReference, String sourceReference, int halfUnits,
                        String source, String reason, Instant createdAt) { }

    record BalanceRecord(long granted, long adjusted, long consumed, long reserved) { }
}
