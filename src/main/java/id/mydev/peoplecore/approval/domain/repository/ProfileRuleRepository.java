package id.mydev.peoplecore.approval.domain.repository;

import id.mydev.peoplecore.approval.domain.model.ProfileRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProfileRuleRepository extends JpaRepository<ProfileRule, Long> {
    List<ProfileRule> findByRuleSetIdOrderByPriorityDesc(Long ruleSetId);
}
