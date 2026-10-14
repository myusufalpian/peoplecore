package id.mydev.peoplecore;

import id.mydev.peoplecore.identity.domain.model.HrisRole;
import id.mydev.peoplecore.identity.domain.model.RoleAssignment;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.identity.domain.repository.RoleAssignmentRepository;
import id.mydev.peoplecore.identity.domain.repository.UserAccountRepository;
import id.mydev.peoplecore.organization.application.command.ProfileResults.ProposalQueueResult;
import id.mydev.peoplecore.organization.application.command.ProfileResults.ProposalSummary;
import id.mydev.peoplecore.support.JwtAuthFixture;
import id.mydev.peoplecore.support.PostgresFixture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("postgres")
@SpringBootTest(properties = {
    "peoplecore.runtime.role=worker",
    "spring.jpa.hibernate.ddl-auto=validate",
    "peoplecore.organization.allowed-units=ENG,FIN,OPS"
})
class ProfileQueuePostgresTest {

    private static final String ISSUER = "https://idp.example";

    private static final PostgresFixture DATABASE = new PostgresFixture();

    static {
        DATABASE.flyway().migrate();
        DATABASE.grantRuntime();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> DATABASE.url);
        properties.add("spring.datasource.username", () -> DATABASE.runtimeUsername);
        properties.add("spring.datasource.password", () -> DATABASE.runtimePassword);
    }

    @Autowired id.mydev.peoplecore.organization.application.service.ProfileProposalService proposals;
    @Autowired UserAccountRepository accountRepository;
    @Autowired RoleAssignmentRepository roleRepository;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clearCaller() {
        SecurityContextHolder.clearContext();
    }

    @AfterAll
    static void closeDatabase() {
        DATABASE.close();
    }

    private record Seeded(Authentication hrEng, Authentication hrAll, UUID reviewerAccountId,
                          List<UUID> engProposals) { }

    private Seeded seedQueue() {
        Instant now = Instant.now();
        String tag = UUID.randomUUID().toString().substring(0, 8);
        UserAccount scoped = accountRepository.save(new UserAccount(UUID.randomUUID(), ISSUER, "hq-" + tag, now));
        accountRepository.flush();
        roleRepository.save(new RoleAssignment(scoped.getId(), HrisRole.HR_ADMIN, "ENG",
            now.minusSeconds(3600), now));
        UserAccount orgWide = accountRepository.save(new UserAccount(UUID.randomUUID(), ISSUER, "ha-" + tag, now));
        accountRepository.flush();
        roleRepository.save(new RoleAssignment(orgWide.getId(), HrisRole.HR_ADMIN, null,
            now.minusSeconds(3600), now));
        UserAccount reviewer = accountRepository.save(
            new UserAccount(UUID.randomUUID(), ISSUER, "qr-" + tag, now));
        accountRepository.flush();
        roleRepository.save(new RoleAssignment(reviewer.getId(), HrisRole.HR_ADMIN, "ENG",
            now.minusSeconds(3600), now));

        List<UUID> engProposals = new ArrayList<>();
        for (int index = 0; index < 105; index++) {
            seedPending("FIN", "QFIN-" + tag + "-" + index, reviewer.getPublicId(), true, 300 - index);
        }
        engProposals.add(seedPending("ENG", "QENG-" + tag + "-0", reviewer.getPublicId(), true, 100));
        engProposals.add(seedPending("ENG", "QENG-" + tag + "-1", reviewer.getPublicId(), true, 99));
        engProposals.add(seedPending("ENG", "QENG-" + tag + "-orphan", UUID.randomUUID(), true, 98));
        engProposals.add(seedPending("ENG", "QENG-" + tag + "-blocked", null, false, 97));
        return new Seeded(JwtAuthFixture.token(ISSUER, "hq-" + tag),
            JwtAuthFixture.token(ISSUER, "ha-" + tag), reviewer.getPublicId(), engProposals);
    }

    private UUID seedPending(String unit, String number, UUID assignee, boolean assigned, int ageSeconds) {
        UUID employeePublicId = UUID.randomUUID();
        jdbc.update("insert into employees(public_id,employee_number,created_at) values(?::uuid,?,now())",
            employeePublicId, number);
        Long employeeId = jdbc.queryForObject("select id from employees where employee_number=?",
            Long.class, number);
        jdbc.update("insert into employee_assignments(public_id,employee_id,org_unit,job_level,"
            + "valid_from,created_at) values(gen_random_uuid(),?,?,?,now() - (? || ' seconds')::interval,now())",
            employeeId, unit, "L3", ageSeconds);
        UUID proposalPublicId = UUID.randomUUID();
        String blocked = assigned ? null : "NO_MATCH";
        jdbc.update("insert into profile_proposal(public_id,employee_id,state,blocked_reason,"
            + "created_at,updated_at) values(?::uuid,?,'PENDING',?,"
            + "now() - (? || ' seconds')::interval,now() - (? || ' seconds')::interval)",
            proposalPublicId, employeeId, blocked, ageSeconds, ageSeconds);
        if (assigned) {
            Long proposalId = jdbc.queryForObject(
                "select id from profile_proposal where public_id=?::uuid", Long.class, proposalPublicId);
            jdbc.update("insert into profile_revision(public_id,proposal_id,revision_number,"
                + "evaluated_org_unit,evaluated_job_level,created_at) values(gen_random_uuid(),?,1,?,?,now())",
                proposalId, unit, "L3");
            Long revisionId = jdbc.queryForObject(
                "select id from profile_revision where proposal_id=?", Long.class, proposalId);
            jdbc.update("insert into profile_assignment(public_id,proposal_id,revision_id,"
                + "assignee_account_public_id,created_at) values(gen_random_uuid(),?,?,?::uuid,now())",
                proposalId, revisionId, assignee);
            jdbc.update("update profile_proposal set current_revision_id=?,"
                + "current_assignment_id=(select id from profile_assignment where proposal_id=? and status='ACTIVE') "
                + "where id=?", revisionId, proposalId, proposalId);
        }
        return proposalPublicId;
    }

    @Test
    void scopedQueueFindsInScopeWorkBeyondOlderOutOfScopeRows() {
        Seeded arena = seedQueue();
        ProposalQueueResult first = proposals.queueProposals(arena.hrEng(), 0, 20);
        assertEquals(4, first.total());
        assertEquals(4, first.proposals().size());
        assertEquals(0, first.page());
        List<UUID> visible = new ArrayList<>();
        int orphan = 0;
        for (ProposalSummary summary : first.proposals()) {
            visible.add(summary.proposalPublicId());
            if (summary.assigneeAccountPublicId() == null) {
                assertEquals("NO_MATCH", summary.derivedBlockedReason());
            } else if (arena.reviewerAccountId().equals(summary.assigneeAccountPublicId())) {
                assertNull(summary.derivedBlockedReason());
            } else {
                assertEquals("NO_DESIGNATED_REVIEWER", summary.derivedBlockedReason());
                orphan++;
            }
        }
        assertTrue(visible.containsAll(arena.engProposals()));
        assertEquals(1, orphan);

        ProposalQueueResult second = proposals.queueProposals(arena.hrEng(), 1, 2);
        assertEquals(4, second.total());
        assertEquals(2, second.proposals().size());

        ProposalQueueResult orgWide = proposals.queueProposals(arena.hrAll(), 0, 100);
        assertEquals(109, orgWide.total());
        assertEquals(100, orgWide.proposals().size());
        ProposalQueueResult remainder = proposals.queueProposals(arena.hrAll(), 1, 100);
        assertEquals(9, remainder.proposals().size());

        UserAccount reviewer = accountRepository.findByPublicId(arena.reviewerAccountId()).orElseThrow();
        roleRepository.findByAccountId(reviewer.getId()).forEach(grant -> {
            grant.endAt(Instant.now().minusSeconds(1));
            roleRepository.save(grant);
        });
        ProposalQueueResult flagged = proposals.queueProposals(arena.hrEng(), 0, 20);
        int ineligible = 0;
        for (ProposalSummary summary : flagged.proposals()) {
            if (arena.reviewerAccountId().equals(summary.assigneeAccountPublicId())
                && "REVIEWER_INELIGIBLE".equals(summary.derivedBlockedReason())) {
                ineligible++;
            }
        }
        assertEquals(2, ineligible);
    }
}
