package id.mydev.peoplecore.reimbursement.domain.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface ReimbursementCategoryRepository {
    List<CategoryRecord> findApplicable(LocalDate date, UUID cursor, int limitPlusOne);

    void lockPublication(String categoryCode);

    List<OverlapRecord> lockOverlaps(String categoryCode, LocalDate from, LocalDate to);

    void markSuperseded(long id);

    void insert(UUID publicId, String code, LocalDate from, LocalDate to, long limit, Instant createdAt);

    record CategoryRecord(UUID publicId, String code, LocalDate from, LocalDate to, long limit) { }

    record OverlapRecord(long id, LocalDate from, LocalDate to, long limit) { }
}
