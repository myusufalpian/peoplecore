package id.mydev.peoplecore.leave.application.service;

import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.identity.application.service.AccountAccessService;
import id.mydev.peoplecore.identity.domain.model.HrisRole;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.leave.application.command.LeaveWorkflowModels.Configuration;
import id.mydev.peoplecore.leave.application.command.LeaveWorkflowModels.Publish;
import id.mydev.peoplecore.leave.application.command.LeaveWorkflowModels.Route;
import id.mydev.peoplecore.leave.application.command.LeaveWorkflowModels.Rule;
import id.mydev.peoplecore.organization.application.service.EmployeeService;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LeaveRoutingService {
    private final JdbcTemplate jdbc;
    private final AccountAccessService access;
    private final EmployeeService employees;
    private final Clock clock;

    public LeaveRoutingService(JdbcTemplate jdbc, AccountAccessService access, EmployeeService employees,
                               Clock clock) {
        this.jdbc=jdbc; this.access=access; this.employees=employees; this.clock=clock;
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public Configuration publish(UUID id, Publish payload, Authentication caller) {
        Instant now=clock.instant();
        UserAccount hr=access.requireHrOperation(caller,now);
        if(payload.rules()==null || payload.rules().isEmpty() || payload.rules().size()>100
            || payload.rules().stream().anyMatch(Objects::isNull)
            || payload.rules().stream().map(Rule::ruleId).distinct().count()!=payload.rules().size()) {
            throw new IllegalArgumentException("Invalid leave rule snapshot");
        }
        for(Rule rule:payload.rules()) {
            access.requireTargetScope(hr.getId(),rule.orgUnit(),now);
            requireHrReviewer(rule.hrAccountPublicId(),rule.orgUnit(),null,now);
            requireHrReviewer(rule.cancellationAccountPublicId(),rule.orgUnit(),null,now);
        }
        jdbc.update("INSERT INTO leave_rule_set(public_id,created_at) VALUES (?,?)",id,Timestamp.from(now));
        Long internal=jdbc.queryForObject("SELECT id FROM leave_rule_set WHERE public_id=?",Long.class,id);
        for(Rule rule:payload.rules()) {
            jdbc.update("INSERT INTO leave_rule(rule_set_id,rule_id,priority,org_unit,hr_account_public_id,cancellation_account_public_id) VALUES (?,?,?,?,?,?)",
                internal,rule.ruleId(),rule.priority(),rule.orgUnit(),rule.hrAccountPublicId(),rule.cancellationAccountPublicId());
        }
        return new Configuration(id,payload.rules());
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public Configuration activate(UUID id,Authentication caller) {
        UserAccount hr=access.requireHrOperation(caller,clock.instant());
        if(!access.hrAuthority(hr.getId(),clock.instant()).organizationWide()) {
            throw new AccessDeniedException("Global activation requires organization-wide HR");
        }
        Long internal=jdbc.query("SELECT id FROM leave_rule_set WHERE public_id=?",
            (rs,n)->rs.getLong(1),id).stream().findFirst().orElseThrow(ResourceNotFoundException::new);
        jdbc.update("INSERT INTO leave_rule_activation(rule_set_id,activated_at) VALUES (?,?)",internal,Timestamp.from(clock.instant()));
        jdbc.update("INSERT INTO leave_rule_current(id,rule_set_id) VALUES (1,?) ON CONFLICT(id) DO UPDATE SET rule_set_id=EXCLUDED.rule_set_id",internal);
        return configuration(id);
    }

    public Configuration configuration(UUID id) {
        List<Rule> rules=jdbc.query("SELECT r.rule_id,r.priority,r.org_unit,r.hr_account_public_id,r.cancellation_account_public_id FROM leave_rule r JOIN leave_rule_set s ON s.id=r.rule_set_id WHERE s.public_id=? ORDER BY r.priority DESC,r.id",
            (rs,n)->new Rule(rs.getString(1),rs.getInt(2),rs.getString(3),rs.getObject(4,UUID.class),rs.getObject(5,UUID.class)),id);
        if(rules.isEmpty()) throw new ResourceNotFoundException();
        return new Configuration(id,rules);
    }

    public Route resolve(Long employeeId,UUID employeePublicId,String unit,Instant now) {
        return resolveWithManager(employeePublicId,unit,employees.currentManagerPublicId(employeeId,now).orElse(null),now);
    }

    public Route resolveWithManager(UUID employeePublicId,String unit,UUID managerEmployeePublicId,Instant now) {
        if(unit==null || unit.isBlank()) return new Route(null,null,null,null,"NO_ORG_UNIT");
        List<UUID> configs=jdbc.query("SELECT s.public_id FROM leave_rule_current c JOIN leave_rule_set s ON s.id=c.rule_set_id WHERE c.id=1",(rs,n)->rs.getObject(1,UUID.class));
        if(configs.isEmpty()) return new Route(null,null,null,null,"NO_ACTIVE_CONFIGURATION");
        UUID config=configs.getFirst();
        List<Rule> matches=configuration(config).rules().stream().filter(r->r.orgUnit().equals(unit)).toList();
        if(matches.isEmpty()) return new Route(config,null,null,null,"NO_MATCH");
        if(matches.size()>1 && matches.get(0).priority()==matches.get(1).priority()) return new Route(config,null,null,null,"AMBIGUOUS_RULE");
        Rule rule=matches.getFirst();
        Optional<UserAccount> manager=Optional.ofNullable(managerEmployeePublicId).flatMap(access::accountBoundToEmployee);
        if(manager.isEmpty()) return new Route(config,null,rule.hrAccountPublicId(),rule.cancellationAccountPublicId(),"NO_DESIGNATED_REVIEWER");
        UUID managerId=manager.get().getPublicId();
        Route route=new Route(config,managerId,rule.hrAccountPublicId(),rule.cancellationAccountPublicId(),null);
        if(!eligible(managerId,employeePublicId,unit,1,false,now)
            || !eligible(rule.hrAccountPublicId(),employeePublicId,unit,2,false,now)
            || !eligible(rule.cancellationAccountPublicId(),employeePublicId,unit,2,false,now)
            || samePerson(managerId,rule.hrAccountPublicId())) {
            return new Route(config,managerId,rule.hrAccountPublicId(),rule.cancellationAccountPublicId(),"REVIEWER_INELIGIBLE");
        }
        return route;
    }

    public boolean eligible(UUID reviewer,UUID requester,String unit,int step,boolean fallback,Instant now) {
        Optional<UserAccount> account=access.findAccountByPublicId(reviewer);
        if(account.isEmpty() || !account.get().isActiveAt(now)) return false;
        Optional<UUID> own=access.ownEmployeePublicId(account.get());
        if(own.isEmpty() || own.get().equals(requester)) return false;
        if(step==2 || fallback) return access.hasRole(account.get().getId(),HrisRole.HR_ADMIN,now)
            && access.coversUnit(account.get().getId(),unit,now);
        if(!access.hasRole(account.get().getId(),HrisRole.MANAGER,now)) return false;
        return access.hasScopedRole(account.get().getId(),HrisRole.MANAGER,unit,now);
    }

    public UUID reviewerEmployee(UUID reviewer) {
        return access.findAccountByPublicId(reviewer).flatMap(access::ownEmployeePublicId)
            .orElseThrow(()->new IllegalArgumentException("Reviewer requires active employee binding"));
    }

    public boolean samePerson(UUID first,UUID second) {
        if(first==null || second==null) return false;
        Optional<UUID> left=access.findAccountByPublicId(first).flatMap(access::ownEmployeePublicId);
        Optional<UUID> right=access.findAccountByPublicId(second).flatMap(access::ownEmployeePublicId);
        return left.isPresent() && left.equals(right);
    }

    private void requireHrReviewer(UUID id,String unit,UUID requester,Instant now) {
        UserAccount account=access.findAccountByPublicId(id).orElseThrow(()->new IllegalArgumentException("Unknown reviewer"));
        if(!account.isActiveAt(now) || !access.hasRole(account.getId(),HrisRole.HR_ADMIN,now)
            || !access.coversUnit(account.getId(),unit,now) || access.ownEmployeePublicId(account).isEmpty()
            || Objects.equals(requester,access.ownEmployeePublicId(account).orElse(null))) {
            throw new IllegalArgumentException("Ineligible designated leave reviewer");
        }
    }
}
