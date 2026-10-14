package id.mydev.peoplecore.organization.application.command;

import id.mydev.peoplecore.approval.application.command.ApprovalPayloads.RulePayload;
import id.mydev.peoplecore.approval.application.service.ApprovalRuleService;
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
import id.mydev.peoplecore.organization.application.command.ProfilePayloads.ChangePayload;
import id.mydev.peoplecore.organization.application.command.ProfileResults.ContactCorrectionResult;
import id.mydev.peoplecore.organization.application.command.ProfileResults.ProposalResult;
import id.mydev.peoplecore.organization.application.service.EmployeeService;
import id.mydev.peoplecore.organization.application.service.ProfileProposalService;
import id.mydev.peoplecore.organization.application.trail.ProfileTrailMapper;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(properties = {
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "peoplecore.organization.allowed-units=ENG,FIN,OPS"
})
@Transactional
class ProfileProposalCommandsTest {

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
            String serialized = codec.serialize(result);
            return codec.deserialize(serialized, resultType);
        }
    }

    @TestConfiguration
    static class FakeCommands {
        @Bean
        @Primary
        ProfileProposalCommands profileProposalCommands(ProfileProposalService proposals,
                                                         ProfileTrailMapper trail) {
            return new ProfileProposalCommands(proposals, new FakeExecution(), trail);
        }
    }

    @Autowired ProfileProposalCommands commands;
    @Autowired ApprovalRuleService rules;
    @Autowired EmployeeService employees;
    @Autowired UserAccountRepository accountRepository;
    @Autowired RoleAssignmentRepository roleRepository;
    @Autowired AccountBindingRepository bindingRepository;
    @Autowired EmployeeRepository employeeRepository;

    private Authentication hrCaller;
    private Authentication reviewerCaller;
    private Authentication reviewerTwoCaller;
    private Authentication memberCaller;
    private UUID reviewerAccountId;
    private UUID reviewerTwoAccountId;
    private UUID memberEmployeeId;

    @BeforeEach
    void seedAccess() {
        UserAccount hr = accountRepository.save(new UserAccount(UUID.randomUUID(), ISSUER, "hr-1", NOW));
        roleRepository.save(new RoleAssignment(hr.getId(), HrisRole.HR_ADMIN, null, NOW.minusSeconds(60), NOW));
        hrCaller = JwtAuthFixture.token(ISSUER, "hr-1");

        UserAccount reviewer = accountRepository.save(new UserAccount(UUID.randomUUID(), ISSUER, "rev-1", NOW));
        roleRepository.save(new RoleAssignment(reviewer.getId(), HrisRole.HR_ADMIN, "ENG", NOW.minusSeconds(60), NOW));
        reviewerAccountId = reviewer.getPublicId();
        reviewerCaller = JwtAuthFixture.token(ISSUER, "rev-1");

        UserAccount reviewerTwo = accountRepository.save(new UserAccount(UUID.randomUUID(), ISSUER, "rev-2", NOW));
        roleRepository.save(new RoleAssignment(reviewerTwo.getId(), HrisRole.HR_ADMIN, "ENG", NOW.minusSeconds(60), NOW));
        reviewerTwoAccountId = reviewerTwo.getPublicId();
        reviewerTwoCaller = JwtAuthFixture.token(ISSUER, "rev-2");

        UserAccount member = accountRepository.save(new UserAccount(UUID.randomUUID(), ISSUER, "emp-1", NOW));
        roleRepository.save(new RoleAssignment(member.getId(), HrisRole.EMPLOYEE, null, NOW.minusSeconds(60), NOW));
        memberCaller = JwtAuthFixture.token(ISSUER, "emp-1");

        UUID reviewerEmployeeId = UUID.randomUUID();
        employees.create(reviewerEmployeeId, "EMP-REV", hrCaller);
        Employee revEntity = employeeRepository.findByPublicId(reviewerEmployeeId).orElseThrow();
        revEntity.linkAccount(reviewer.getId());
        employeeRepository.save(revEntity);
        bindingRepository.save(new AccountBinding(revEntity.getId(), reviewerEmployeeId,
            reviewer.getId(), ISSUER, "rev-1", NOW));

        memberEmployeeId = UUID.randomUUID();
        employees.create(memberEmployeeId, "EMP-OWN", hrCaller);
        Employee entity = employeeRepository.findByPublicId(memberEmployeeId).orElseThrow();
        entity.linkAccount(member.getId());
        employeeRepository.save(entity);
        bindingRepository.save(new AccountBinding(entity.getId(), memberEmployeeId,
            member.getId(), ISSUER, "emp-1", NOW));
        employees.addAssignment(memberEmployeeId, UUID.randomUUID(), "ENG", null, "L3",
            Instant.parse("2026-01-01T00:00:00Z"), null, hrCaller);

        UUID setId = UUID.randomUUID();
        rules.publishRuleSet(setId,
            List.of(new RulePayload("route-eng", 10, "ENG", "L3", reviewerAccountId)), hrCaller);
        rules.activateRuleSet(setId, hrCaller);
    }

    @AfterEach
    void clearCaller() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void commandPathCreatesSubmitsAndApprovesWithSerializableReceipts() {
        ProposalResult created = commands.create(memberCaller, "cmd-create-1", memberEmployeeId,
            List.of(new ChangePayload("personalPhone", "+62 812 345 678")));
        assertEquals("DRAFT", created.state());

        ProposalResult edited = commands.edit(memberCaller, "cmd-edit-1", created.proposalPublicId(),
            created.proposalVersion(),
            List.of(new ChangePayload("personalPhone", "+62 812 999 999")));
        assertEquals(2, edited.revisionNumber());

        ProposalResult submitted = commands.submit(memberCaller, "cmd-submit-1",
            edited.proposalPublicId(), edited.proposalVersion());
        assertEquals("PENDING", submitted.state());
        assertNotNull(submitted.assignmentPublicId());

        ProposalResult approved = commands.decide(reviewerCaller, "cmd-approve-1",
            submitted.proposalPublicId(), submitted.proposalVersion(), submitted.revisionPublicId(),
            submitted.assignmentPublicId(), "PROFILE_APPROVE", null);
        assertEquals("APPROVED", approved.state());
    }

    @Test
    void commandPathRejectsReturnsReassignsAndCorrects() {
        ProposalResult created = commands.create(memberCaller, "cmd-create-2", memberEmployeeId,
            List.of(new ChangePayload("personalPhone", "+62 812 111 222")));
        ProposalResult submitted = commands.submit(memberCaller, "cmd-submit-2",
            created.proposalPublicId(), created.proposalVersion());
        ProposalResult returned = commands.decide(reviewerCaller, "cmd-return-2",
            submitted.proposalPublicId(), submitted.proposalVersion(), submitted.revisionPublicId(),
            submitted.assignmentPublicId(), "PROFILE_RETURN", "Fix the format");
        assertEquals("RETURNED", returned.state());

        ProposalResult resubmitted = commands.resubmit(memberCaller, "cmd-resubmit-2",
            returned.proposalPublicId(), returned.proposalVersion(),
            List.of(new ChangePayload("personalPhone", "+62 812 333 444")));
        assertEquals("PENDING", resubmitted.state());

        ProposalResult rejected = commands.decide(reviewerCaller, "cmd-reject-2",
            resubmitted.proposalPublicId(), resubmitted.proposalVersion(),
            resubmitted.revisionPublicId(), resubmitted.assignmentPublicId(),
            "PROFILE_REJECT", "Unverifiable");
        assertEquals("REJECTED", rejected.state());

        ProposalResult second = commands.create(memberCaller, "cmd-create-3", memberEmployeeId,
            List.of(new ChangePayload("personalPhone", "+62 812 555 666")));
        ProposalResult pending = commands.submit(memberCaller, "cmd-submit-3",
            second.proposalPublicId(), second.proposalVersion());
        ProposalResult withdrawn = commands.withdraw(memberCaller, "cmd-withdraw-3",
            pending.proposalPublicId(), pending.proposalVersion());
        assertEquals("CANCELLED", withdrawn.state());

        ContactCorrectionResult corrected = commands.masterCorrect(hrCaller, "cmd-correct-1",
            memberEmployeeId, 0, 0, 0, 0,
            List.of(new ChangePayload("personalEmail", "member@example.id")), "HR correction");
        assertEquals(1, corrected.emailVersion());
        assertEquals(memberEmployeeId, corrected.employeePublicId());
    }

    @Test
    void commandPathRejectsUnknownDecisionType() {
        ProposalResult created = commands.create(memberCaller, "cmd-create-6", memberEmployeeId,
            List.of(new ChangePayload("personalPhone", "+62 812 888 999")));
        assertThrows(IllegalArgumentException.class, () -> commands.decide(memberCaller, "cmd-bogus-6",
            created.proposalPublicId(), created.proposalVersion(), null, null,
            "PROFILE_ESCALATE", null));
    }

    @Test
    void commandPathReassignsReviewer() {
        ProposalResult created = commands.create(memberCaller, "cmd-create-4", memberEmployeeId,
            List.of(new ChangePayload("personalPhone", "+62 812 444 555")));
        ProposalResult submitted = commands.submit(memberCaller, "cmd-submit-4",
            created.proposalPublicId(), created.proposalVersion());
        ProposalResult reassigned = commands.reassign(hrCaller, "cmd-reassign-4",
            submitted.proposalPublicId(), submitted.proposalVersion(), submitted.assignmentPublicId(),
            reviewerTwoAccountId, "Covering leave");
        assertEquals("PENDING", reassigned.state());
        ProposalResult approved = commands.decide(reviewerTwoCaller, "cmd-approve-4",
            reassigned.proposalPublicId(), reassigned.proposalVersion(),
            reassigned.revisionPublicId(), reassigned.assignmentPublicId(),
            "PROFILE_APPROVE", null);
        assertEquals("APPROVED", approved.state());
    }

    @Test
    void commandPathRetriesBlockedRouteAfterRepair() {
        UUID tieSet = UUID.randomUUID();
        rules.publishRuleSet(tieSet, List.of(
            new RulePayload("tie-a", 10, "ENG", "L3", reviewerAccountId),
            new RulePayload("tie-b", 10, "ENG", null, reviewerAccountId)), hrCaller);
        rules.activateRuleSet(tieSet, hrCaller);
        ProposalResult created = commands.create(memberCaller, "cmd-create-5", memberEmployeeId,
            List.of(new ChangePayload("personalPhone", "+62 812 666 777")));
        ProposalResult blocked = commands.submit(memberCaller, "cmd-submit-5",
            created.proposalPublicId(), created.proposalVersion());
        assertEquals("AMBIGUOUS_RULE", blocked.blockedReason());

        UUID fixedSet = UUID.randomUUID();
        rules.publishRuleSet(fixedSet, List.of(
            new RulePayload("route-eng", 10, "ENG", "L3", reviewerAccountId)), hrCaller);
        rules.activateRuleSet(fixedSet, hrCaller);
        ProposalResult recovered = commands.retryRoute(hrCaller, "cmd-retry-5",
            blocked.proposalPublicId(), blocked.proposalVersion());
        assertEquals("PENDING", recovered.state());
        assertNotNull(recovered.assignmentPublicId());
    }
}
