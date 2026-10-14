package id.mydev.peoplecore.approval.domain.repository;

import id.mydev.peoplecore.approval.domain.model.ProfileRuleActivation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProfileRuleActivationRepository extends JpaRepository<ProfileRuleActivation, Long> {
    List<ProfileRuleActivation> findByRuleSetIdOrderByActivatedAtDesc(Long ruleSetId);
}
