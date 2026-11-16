package id.mydev.peoplecore.identity.domain.repository;

import java.time.Instant;
import java.util.UUID;

public interface ReportAuthorizationRepository {
    boolean containsTarget(long accountId, String reportType, UUID employee, String unit, Instant now, long deadlineNanos);
}
