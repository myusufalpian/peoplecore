package id.mydev.peoplecore.approval.application.command;

import id.mydev.peoplecore.approval.application.command.ApprovalPayloads.RulePayload;
import id.mydev.peoplecore.approval.application.command.ApprovalResults.ActivationResult;
import id.mydev.peoplecore.approval.application.command.ApprovalResults.RuleSetResult;
import id.mydev.peoplecore.approval.application.service.ApprovalRuleService;
import id.mydev.peoplecore.approval.application.trail.ApprovalTrailMapper;
import id.mydev.peoplecore.common.command.CommandExecutionService;
import id.mydev.peoplecore.common.command.CommandExecutionService.AuditDescriptor;
import id.mydev.peoplecore.common.command.CommandExecutionService.OutboxDescriptor;
import id.mydev.peoplecore.common.command.DefaultCommandResultSerializer;
import id.mydev.peoplecore.identity.domain.model.AccountBinding;
import id.mydev.peoplecore.identity.domain.model.HrisRole;
import id.mydev.peoplecore.identity.domain.model.RoleAssignment;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.identity.domain.repository.AccountBindingRepository;
import id.mydev.peoplecore.identity.domain.repository.RoleAssignmentRepository;
import id.mydev.peoplecore.identity.domain.repository.UserAccountRepository;
import id.mydev.peoplecore.organization.application.service.EmployeeService;
import id.mydev.peoplecore.organization.domain.model.Employee;
import id.mydev.peoplecore.organization.domain.repository.EmployeeRepository;
import id.mydev.peoplecore.support.JwtAuthFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(properties = {
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "peoplecore.organization.allowed-units=ENG,FIN,OPS"
})
@Transactional
class ApprovalRuleCommandsTest {

    static final String ISSUER = "https://idp.example";
    static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    static class FakeExecution extends CommandExecutionService {
        private final DefaultCommandResultSerializer codec =
            new DefaultCommandResultSerializer(new ObjectMapper());

        FakeExecution() {
            super(null, null, null, null, null, null, null, null);
        }

        @Override
        public <T> T executeCommand(CommandContext context, Supplier<T> domainAction,
                                    Class<T> resultType,
                                    Function<T, AuditDescriptor> auditMapping,
                                    Function<T, OutboxDescriptor> outboxMapping) {
            T result = domainAction.get();
            auditMapping.apply(result);
            outboxMapping.apply(result);
            return codec.deserialize(codec.serialize(result), resultType);
        }
    }

    @TestConfiguration
    static class FakeCommands {
        @Bean
        @Primary
        ApprovalRuleCommands approvalRuleCommands(ApprovalRuleService rules,
                                                   ApprovalTrailMapper trail) {
            return new ApprovalRuleCommands(rules, new FakeExecution(), trail);
        }
    }

    @Autowired ApprovalRuleCommands commands;
    @Autowired EmployeeService employees;
    @Autowired UserAccountRepository accountRepository;
    @Autowired RoleAssignmentRepository roleRepository;
    @Autowired AccountBindingRepository bindingRepository;
    @Autowired EmployeeRepository employeeRepository;

    private Authentication hrCaller;
    private UUID reviewerAccountId;

    @BeforeEach
    void seedAccess() {
        UserAccount hr = accountRepository.save(new UserAccount(UUID.randomUUID(), ISSUER, "hr-1", NOW));
        roleRepository.save(new RoleAssignment(hr.getId(), HrisRole.HR_ADMIN, null, NOW.minusSeconds(60), NOW));
        hrCaller = JwtAuthFixture.token(ISSUER, "hr-1");

        UserAccount reviewer = accountRepository.save(new UserAccount(UUID.randomUUID(), ISSUER, "rev-1", NOW));
        roleRepository.save(new RoleAssignment(reviewer.getId(), HrisRole.HR_ADMIN, "ENG", NOW.minusSeconds(60), NOW));
        reviewerAccountId = reviewer.getPublicId();

        UUID reviewerEmployeeId = UUID.randomUUID();
        employees.create(reviewerEmployeeId, "EMP-REV", hrCaller);
        Employee entity = employeeRepository.findByPublicId(reviewerEmployeeId).orElseThrow();
        entity.linkAccount(reviewer.getId());
        employeeRepository.save(entity);
        bindingRepository.save(new AccountBinding(entity.getId(), reviewerEmployeeId,
            reviewer.getId(), ISSUER, "rev-1", NOW));
    }

    @AfterEach
    void clearCaller() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void commandPathPublishesAndActivatesWithSerializableReceipts() {
        UUID setId = UUID.randomUUID();
        RuleSetResult published = commands.publish(hrCaller, "rule-publish-1", setId,
            List.of(new RulePayload("route-eng", 10, "ENG", "L3", reviewerAccountId)));
        assertEquals("PUBLISHED", published.status());
        assertEquals(1, published.ruleCount());

        RuleSetResult derived = commands.publish(hrCaller, "rule-publish-derived", null,
            List.of(new RulePayload("route-eng", 10, "ENG", "L3", reviewerAccountId)));
        assertEquals("PUBLISHED", derived.status());

        ActivationResult activated = commands.activate(hrCaller, "rule-activate-1", setId);
        assertEquals(setId, activated.ruleSetPublicId());
    }
}
