package id.mydev.peoplecore.leave.domain.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LeaveRoutingRepository {
    void insertRuleSet(UUID publicId, Instant createdAt);

    long ruleSetId(UUID publicId);

    void insertRule(long ruleSetId, RuleRecord rule);

    Optional<Long> findRuleSetId(UUID publicId);

    void insertActivation(long ruleSetId, Instant activatedAt);

    void activateCurrent(long ruleSetId);

    List<RuleRecord> findRules(UUID ruleSetPublicId);

    Optional<UUID> findCurrentRuleSetPublicId();

    record RuleRecord(String ruleId, int priority, String orgUnit, UUID hrAccountPublicId,
                      UUID cancellationAccountPublicId) { }
}
