package id.mydev.peoplecore.attendance.domain.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AttendanceReviewRepository {
    void insertRuleSet(UUID publicId, Instant createdAt);

    void insertRule(UUID rulePublicId, UUID ruleSetId, int priority, String orgUnit, Long designatedHrId);

    void markPublished(UUID ruleSetId);

    long countPublished(UUID ruleSetId);

    void insertActivation(UUID publicId, UUID ruleSetId, String reason, Instant createdAt);

    List<RuleMatchRecord> findMatchingRules(String orgUnit);

    Optional<UUID> findActiveRuleSetPublicId();

    long countDesignatedHr(long reviewerId, String orgUnit);

    record RuleMatchRecord(int priority, Long designatedHrId) { }
}
