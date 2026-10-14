package id.mydev.peoplecore;

import id.mydev.peoplecore.approval.application.command.ApprovalPayloads.RulePayload;
import id.mydev.peoplecore.approval.application.command.ApprovalRuleCommands;
import id.mydev.peoplecore.identity.domain.model.AccountBinding;
import id.mydev.peoplecore.identity.domain.model.HrisRole;
import id.mydev.peoplecore.identity.domain.model.RoleAssignment;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.identity.domain.repository.AccountBindingRepository;
import id.mydev.peoplecore.identity.domain.repository.RoleAssignmentRepository;
import id.mydev.peoplecore.identity.domain.repository.UserAccountRepository;
import id.mydev.peoplecore.organization.application.command.EmployeeCommands;
import id.mydev.peoplecore.organization.application.command.ProfilePayloads.ChangePayload;
import id.mydev.peoplecore.organization.application.command.ProfileProposalCommands;
import id.mydev.peoplecore.organization.application.command.ProfileResults.ProposalResult;
import id.mydev.peoplecore.organization.domain.exception.ProfileOpenProposalException;
import id.mydev.peoplecore.organization.domain.exception.ProfileConflictException;
import id.mydev.peoplecore.organization.domain.exception.ProfileStaleVersionException;
import id.mydev.peoplecore.organization.application.service.ProfileProposalService;
import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import jakarta.persistence.EntityManager;
import id.mydev.peoplecore.organization.domain.repository.EmployeeRepository;
import id.mydev.peoplecore.support.JwtAuthFixture;
import id.mydev.peoplecore.support.PostgresFixture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("postgres")
@SpringBootTest(properties = {
    "peoplecore.runtime.role=worker",
    "spring.jpa.hibernate.ddl-auto=validate",
    "peoplecore.organization.allowed-units=ENG,FIN,OPS"
})
class ProfileWorkflowConcurrencyTest {

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

    @Autowired ProfileProposalCommands proposalCommands;
    @Autowired ApprovalRuleCommands ruleCommands;
    @Autowired EmployeeCommands employeeCommands;
    @Autowired UserAccountRepository accountRepository;
    @Autowired RoleAssignmentRepository roleRepository;
    @Autowired AccountBindingRepository bindingRepository;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired EntityManager entityManager;
    @Autowired ProfileProposalService proposalService;
    @Autowired TestClock clock;

    @AfterEach
    void clearCaller() {
        SecurityContextHolder.clearContext();
        clock.reset();
    }

    @AfterAll
    static void closeDatabase() {
        DATABASE.close();
    }

    private record Arena(Authentication hr, Authentication reviewer, Authentication reviewerTwo,
                         Authentication member, Long memberInternalId, UUID memberEmployeeId,
                         UUID reviewerAccountId, UUID reviewerTwoAccountId) { }

    private Arena seedArena() {
        Instant now = Instant.now(clock);
        String tag = UUID.randomUUID().toString().substring(0, 8);
        UserAccount hr = accountRepository.save(new UserAccount(UUID.randomUUID(), ISSUER, "hr-" + tag, now));
        accountRepository.flush();
        roleRepository.save(new RoleAssignment(hr.getId(), HrisRole.HR_ADMIN, null, now.minusSeconds(60), now));
        UserAccount reviewer = accountRepository.save(
            new UserAccount(UUID.randomUUID(), ISSUER, "rev-" + tag, now));
        accountRepository.flush();
        roleRepository.save(new RoleAssignment(reviewer.getId(), HrisRole.HR_ADMIN, "ENG",
            now.minusSeconds(60), now));
        UserAccount reviewerTwo = accountRepository.save(
            new UserAccount(UUID.randomUUID(), ISSUER, "rev2-" + tag, now));
        accountRepository.flush();
        roleRepository.save(new RoleAssignment(reviewerTwo.getId(), HrisRole.HR_ADMIN, "ENG",
            now.minusSeconds(60), now));
        UserAccount member = accountRepository.save(
            new UserAccount(UUID.randomUUID(), ISSUER, "emp-" + tag, now));
        accountRepository.flush();
        roleRepository.save(new RoleAssignment(member.getId(), HrisRole.EMPLOYEE, null,
            now.minusSeconds(60), now));

        Authentication hrCaller = JwtAuthFixture.token(ISSUER, "hr-" + tag);
        as(hrCaller, () -> employeeCommands.create(hrCaller, "arena-" + tag, "EMP-" + tag));
        UUID memberEmployeeId = employeeRepository.findByEmployeeNumber("EMP-" + tag).orElseThrow()
            .getPublicId();
        Long memberInternalId = employeeRepository.findByEmployeeNumber("EMP-" + tag).orElseThrow().getId();
        as(hrCaller, () -> employeeCommands.addAssignment(hrCaller, "assign-" + tag, memberEmployeeId,
            "ENG", null, "L3", now.minusSeconds(3600), null));
        bind(memberInternalId, memberEmployeeId, member.getId(), "emp-" + tag, now);

        Long reviewerInternalId = bindFreshEmployee(hrCaller, "EMPR-" + tag, reviewer.getId(), "rev-" + tag, now);
        Long reviewerTwoInternalId = bindFreshEmployee(hrCaller, "EMPR2-" + tag, reviewerTwo.getId(),
            "rev2-" + tag, now);
        assertTrue(reviewerInternalId > 0 && reviewerTwoInternalId > 0);

        Authentication reviewerCaller = JwtAuthFixture.token(ISSUER, "rev-" + tag);
        Authentication reviewerTwoCaller = JwtAuthFixture.token(ISSUER, "rev2-" + tag);
        Authentication memberCaller = JwtAuthFixture.token(ISSUER, "emp-" + tag);

        UUID setId = UUID.randomUUID();
        as(hrCaller, () -> ruleCommands.publish(hrCaller, "rules-" + tag, setId,
            List.of(new RulePayload("route-eng", 10, "ENG", "L3", reviewer.getPublicId()))));
        as(hrCaller, () -> ruleCommands.activate(hrCaller, "activate-" + tag, setId));
        return new Arena(hrCaller, reviewerCaller, reviewerTwoCaller, memberCaller, memberInternalId,
            memberEmployeeId, reviewer.getPublicId(), reviewerTwo.getPublicId());
    }

    private Long bindFreshEmployee(Authentication hrCaller, String number, Long accountId,
                                   String subject, Instant now) {
        as(hrCaller, () -> employeeCommands.create(hrCaller, "create-" + number, number));
        var employee = employeeRepository.findByEmployeeNumber(number).orElseThrow();
        employee.linkAccount(accountId);
        employeeRepository.saveAndFlush(employee);
        bindingRepository.saveAndFlush(new AccountBinding(employee.getId(), employee.getPublicId(),
            accountId, ISSUER, subject, now));
        return employee.getId();
    }

    private void bind(Long internalId, UUID publicId, Long accountId, String subject, Instant now) {
        var employee = employeeRepository.findById(internalId).orElseThrow();
        employee.linkAccount(accountId);
        employeeRepository.saveAndFlush(employee);
        bindingRepository.saveAndFlush(new AccountBinding(internalId, publicId, accountId,
            ISSUER, subject, now));
    }

    private static <T> T as(Authentication caller, Supplier<T> action) {
        SecurityContextHolder.getContext().setAuthentication(caller);
        try {
            return action.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private ProposalResult pendingProposal(Arena arena, String phone) {
        ProposalResult created = as(arena.member(), () -> proposalCommands.create(arena.member(),
            "create-" + UUID.randomUUID(), arena.memberEmployeeId(),
            List.of(new ChangePayload("personalPhone", phone))));
        return as(arena.member(), () -> proposalCommands.submit(arena.member(),
            "submit-" + UUID.randomUUID(), created.proposalPublicId(), created.proposalVersion()));
    }

    private static String proposalState(JdbcTemplate jdbc, UUID proposalPublicId) {
        return jdbc.queryForObject("select state from profile_proposal where public_id = ?::uuid",
            String.class, proposalPublicId);
    }

    @Test
    void approveAndWithdrawRaceHasSingleAuthoritativeOutcome() throws Exception {
        Arena arena = seedArena();
        ProposalResult submitted = pendingProposal(arena, "+62 812 345 678");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            Callable<String> approve = () -> {
                gate.await(10, TimeUnit.SECONDS);
                return as(arena.reviewer(), () -> proposalCommands.decide(arena.reviewer(),
                    "approve-" + UUID.randomUUID(), submitted.proposalPublicId(),
                    submitted.proposalVersion(), submitted.revisionPublicId(),
                    submitted.assignmentPublicId(), "PROFILE_APPROVE", null).state());
            };
            Callable<String> withdraw = () -> {
                gate.await(10, TimeUnit.SECONDS);
                return as(arena.member(), () -> proposalCommands.withdraw(arena.member(),
                    "withdraw-" + UUID.randomUUID(), submitted.proposalPublicId(),
                    submitted.proposalVersion()).state());
            };
            Future<String> approval = pool.submit(approve);
            Future<String> withdrawal = pool.submit(withdraw);
            gate.countDown();
            int successes = 0;
            for (Future<String> outcome : List.of(approval, withdrawal)) {
                try {
                    outcome.get(60, TimeUnit.SECONDS);
                    successes++;
                } catch (Exception expectedLoser) {
                    assertTrue(expectedLoser.getCause() instanceof RuntimeException);
                }
            }
            assertEquals(1, successes);
            String state = proposalState(jdbc, submitted.proposalPublicId());
            if ("APPROVED".equals(state)) {
                assertEquals("+62 812 345 678", jdbc.queryForObject(
                    "select personal_phone from employee_contact_profile where employee_id = "
                        + "(select employee_id from profile_proposal where public_id = ?::uuid)",
                    String.class, submitted.proposalPublicId()));
            } else {
                assertEquals("CANCELLED", state);
                assertEquals(0, jdbc.queryForObject(
                    "select count(*) from employee_contact_profile where personal_phone = '+62 812 345 678'",
                    Integer.class));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void approveAndReassignRaceKeepsSingleActiveAssignment() throws Exception {
        Arena arena = seedArena();
        ProposalResult submitted = pendingProposal(arena, "+62 812 456 789");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            Callable<String> approve = () -> {
                gate.await(10, TimeUnit.SECONDS);
                return as(arena.reviewer(), () -> proposalCommands.decide(arena.reviewer(),
                    "approve-" + UUID.randomUUID(), submitted.proposalPublicId(),
                    submitted.proposalVersion(), submitted.revisionPublicId(),
                    submitted.assignmentPublicId(), "PROFILE_APPROVE", null).state());
            };
            Callable<String> reassign = () -> {
                gate.await(10, TimeUnit.SECONDS);
                return as(arena.hr(), () -> proposalCommands.reassign(arena.hr(),
                    "reassign-" + UUID.randomUUID(), submitted.proposalPublicId(),
                    submitted.proposalVersion(), submitted.assignmentPublicId(),
                    arena.reviewerTwoAccountId(), "Covering leave").state());
            };
            Future<String> approval = pool.submit(approve);
            Future<String> reassignment = pool.submit(reassign);
            gate.countDown();
            int successes = 0;
            for (Future<String> outcome : List.of(approval, reassignment)) {
                try {
                    outcome.get(60, TimeUnit.SECONDS);
                    successes++;
                } catch (Exception expectedLoser) {
                    assertTrue(expectedLoser.getCause() instanceof RuntimeException);
                }
            }
            assertEquals(1, successes);
            assertTrue(jdbc.queryForObject(
                "select count(*) from profile_assignment where proposal_id = "
                    + "(select id from profile_proposal where public_id = ?::uuid) and status = 'ACTIVE'",
                Integer.class, submitted.proposalPublicId()) <= 1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void distinctKeyDoubleCreateKeepsOneOpenProposal() throws Exception {
        Arena arena = seedArena();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            Callable<ProposalResult> first = () -> {
                gate.await(10, TimeUnit.SECONDS);
                return as(arena.member(), () -> proposalCommands.create(arena.member(),
                    "race-a-" + UUID.randomUUID(), arena.memberEmployeeId(),
                    List.of(new ChangePayload("personalPhone", "+62 812 111 111"))));
            };
            Callable<ProposalResult> second = () -> {
                gate.await(10, TimeUnit.SECONDS);
                return as(arena.member(), () -> proposalCommands.create(arena.member(),
                    "race-b-" + UUID.randomUUID(), arena.memberEmployeeId(),
                    List.of(new ChangePayload("personalPhone", "+62 812 222 222"))));
            };
            Future<ProposalResult> left = pool.submit(first);
            Future<ProposalResult> right = pool.submit(second);
            gate.countDown();
            int successes = 0;
            for (Future<ProposalResult> outcome : List.of(left, right)) {
                try {
                    outcome.get(60, TimeUnit.SECONDS);
                    successes++;
                } catch (Exception expectedLoser) {
                    assertTrue(expectedLoser.getCause() instanceof ProfileOpenProposalException);
                }
            }
            assertEquals(1, successes);
            assertEquals(1, jdbc.queryForObject(
                "select count(*) from profile_proposal where employee_id = ?"
                    + " and state in ('DRAFT','PENDING','RETURNED')",
                Integer.class, arena.memberInternalId()));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void identicalSubmitReplayReturnsRecordedResult() {
        Arena arena = seedArena();
        ProposalResult created = as(arena.member(), () -> proposalCommands.create(arena.member(),
            "replay-create-" + UUID.randomUUID(), arena.memberEmployeeId(),
            List.of(new ChangePayload("personalPhone", "+62 812 777 888"))));
        String key = "replay-submit-" + UUID.randomUUID();
        ProposalResult first = as(arena.member(), () -> proposalCommands.submit(arena.member(), key,
            created.proposalPublicId(), created.proposalVersion()));
        ProposalResult replayed = as(arena.member(), () -> proposalCommands.submit(arena.member(), key,
            created.proposalPublicId(), created.proposalVersion()));
        assertEquals(first.proposalVersion(), replayed.proposalVersion());
        assertEquals(first.revisionPublicId(), replayed.revisionPublicId());
        assertEquals(2, jdbc.queryForObject(
            "select count(*) from profile_revision where proposal_id = "
                + "(select id from profile_proposal where public_id = ?::uuid)",
            Integer.class, created.proposalPublicId()));
    }

    @Test
    void formerReviewerReplayDeniedAfterReplacementCloses() {
        Arena arena = seedArena();
        ProposalResult submitted = pendingProposal(arena, "+62 812 345 678");
        String returnKey = "return-" + UUID.randomUUID();
        ProposalResult returned = as(arena.reviewer(), () -> proposalCommands.decide(arena.reviewer(),
            returnKey, submitted.proposalPublicId(), submitted.proposalVersion(),
            submitted.revisionPublicId(), submitted.assignmentPublicId(), "PROFILE_RETURN",
            "Fix the format"));
        assertEquals("RETURNED", returned.state());
        ProposalResult resubmitted = as(arena.member(), () -> proposalCommands.resubmit(arena.member(),
            "resubmit-" + UUID.randomUUID(), returned.proposalPublicId(), returned.proposalVersion(),
            List.of(new ChangePayload("personalPhone", "+62 813 000 111"))));
        assertEquals("PENDING", resubmitted.state());
        ProposalResult reassigned = as(arena.hr(), () -> proposalCommands.reassign(arena.hr(),
            "reassign-" + UUID.randomUUID(), resubmitted.proposalPublicId(),
            resubmitted.proposalVersion(), resubmitted.assignmentPublicId(),
            arena.reviewerTwoAccountId(), "Covering leave"));
        String approveKey = "approve-" + UUID.randomUUID();
        ProposalResult approved = as(arena.reviewerTwo(), () -> proposalCommands.decide(
            arena.reviewerTwo(), approveKey, reassigned.proposalPublicId(),
            reassigned.proposalVersion(), reassigned.revisionPublicId(),
            reassigned.assignmentPublicId(), "PROFILE_APPROVE", null));
        assertEquals("APPROVED", approved.state());

        assertThrows(AccessDeniedException.class,
            () -> as(arena.reviewer(), () -> proposalCommands.decide(arena.reviewer(),
                returnKey, submitted.proposalPublicId(), submitted.proposalVersion(),
                submitted.revisionPublicId(), submitted.assignmentPublicId(), "PROFILE_RETURN",
                "Fix the format")));

        ProposalResult replayed = as(arena.reviewerTwo(), () -> proposalCommands.decide(
            arena.reviewerTwo(), approveKey, reassigned.proposalPublicId(),
            reassigned.proposalVersion(), reassigned.revisionPublicId(),
            reassigned.assignmentPublicId(), "PROFILE_APPROVE", null));
        assertEquals(approved.proposalVersion(), replayed.proposalVersion());
        assertEquals(approved.assignmentPublicId(), replayed.assignmentPublicId());
        assertEquals(2, jdbc.queryForObject(
            "select count(*) from profile_decision where proposal_id = "
                + "(select id from profile_proposal where public_id = ?::uuid)",
            Integer.class, submitted.proposalPublicId()));
    }

    @TestConfiguration
    static class TimeConfiguration {
        @Bean
        @Primary
        TestClock acceptanceClock() {
            return new TestClock();
        }
    }

    static final class TestClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());

        void reset() {
            now.set(Instant.now());
        }

        void advanceSeconds(long seconds) {
            now.updateAndGet(instant -> instant.plusSeconds(seconds));
        }

        @Override
        public ZoneId getZone() { return ZoneOffset.UTC; }

        @Override
        public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }

        @Override
        public Instant instant() { return now.get(); }
    }

    private <T> Future<T> waitBehindTransaction(ExecutorService pool, Supplier<?> first,
                                                Supplier<T> waiting) throws Exception {
        return waitBehindTransaction(pool, first, waiting, () -> { });
    }

    private <T> Future<T> waitBehindTransaction(ExecutorService pool, Supplier<?> first,
                                                Supplier<T> waiting, Runnable afterWait) throws Exception {
        CountDownLatch changed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
            Object result = first.get();
            entityManager.flush();
            changed.countDown();
            try {
                assertTrue(release.await(15, TimeUnit.SECONDS), "Holding transaction was not released");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            return result;
        }));
        try {
            assertTrue(changed.await(10, TimeUnit.SECONDS), "First command did not reach held transaction");
            Future<T> queued = pool.submit(waiting::get);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            boolean blocked = false;
            while (System.nanoTime() < deadline && !queued.isDone()) {
                Integer blockedSessions = DATABASE.jdbc.queryForObject(
                    "select count(*) from pg_stat_activity where usename = ? and wait_event_type = 'Lock'",
                    Integer.class, DATABASE.runtimeUsername);
                if (blockedSessions != null && blockedSessions > 0) {
                    blocked = true;
                    break;
                }
                Thread.sleep(10);
            }
            assertTrue(blocked, "Second operation must wait on a real PostgreSQL lock");
            assertFalse(queued.isDone());
            afterWait.run();
            release.countDown();
            holder.get(20, TimeUnit.SECONDS);
            return queued;
        } finally {
            release.countDown();
        }
    }

    private ProposalResult approve(Arena arena, ProposalResult submitted, String key) {
        return as(arena.reviewer(), () -> proposalCommands.decide(arena.reviewer(), key,
            submitted.proposalPublicId(), submitted.proposalVersion(), submitted.revisionPublicId(),
            submitted.assignmentPublicId(), "PROFILE_APPROVE", null));
    }

    private Object correct(Arena arena, long phoneVersion, long emailVersion, List<ChangePayload> values) {
        return as(arena.hr(), () -> proposalCommands.masterCorrect(arena.hr(),
            "correct-" + UUID.randomUUID(), arena.memberEmployeeId(), phoneVersion, emailVersion, 0, 0,
            values, "Verified HR correction"));
    }

    private int decisionCount(UUID proposalId) {
        return jdbc.queryForObject("select count(*) from profile_decision where proposal_id = "
            + "(select id from profile_proposal where public_id = ?::uuid)", Integer.class, proposalId);
    }

    @Test
    void returnCommitsWhileApprovalWaitsAndApprovalCannotChangeReturnedState() throws Exception {
        Arena arena = seedArena();
        ProposalResult submitted = pendingProposal(arena, "+62 812 345 670");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ProposalResult> queued = waitBehindTransaction(pool,
                () -> as(arena.reviewer(), () -> proposalCommands.decide(arena.reviewer(),
                    "return-" + UUID.randomUUID(), submitted.proposalPublicId(), submitted.proposalVersion(),
                    submitted.revisionPublicId(), submitted.assignmentPublicId(), "PROFILE_RETURN", "Fix contact")),
                () -> approve(arena, submitted, "approve-" + UUID.randomUUID()));
            ExecutionException loser = assertThrows(ExecutionException.class, () -> queued.get(20, TimeUnit.SECONDS));
            assertTrue(loser.getCause() instanceof ProfileStaleVersionException);
            assertEquals("RETURNED", proposalState(jdbc, submitted.proposalPublicId()));
            assertEquals(1, decisionCount(submitted.proposalPublicId()));
            assertNull(jdbc.queryForObject("select personal_phone from employee_contact_profile where employee_id = ?",
                String.class, arena.memberInternalId()));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void touchedContactABACorrectionCommittedDuringApprovalWaitStillConflicts() throws Exception {
        Arena arena = seedArena();
        correct(arena, 0, 0, List.of(new ChangePayload("personalPhone", "+62 812 100 100")));
        ProposalResult submitted = pendingProposal(arena, "+62 812 200 200");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ProposalResult> queued = waitBehindTransaction(pool, () -> {
                correct(arena, 1, 0, List.of(new ChangePayload("personalPhone", "+62 812 300 300")));
                return correct(arena, 2, 0, List.of(new ChangePayload("personalPhone", "+62 812 100 100")));
            }, () -> approve(arena, submitted, "approve-" + UUID.randomUUID()));
            ExecutionException conflict = assertThrows(ExecutionException.class, () -> queued.get(20, TimeUnit.SECONDS));
            assertTrue(conflict.getCause() instanceof ProfileConflictException);
            assertEquals("PENDING", proposalState(jdbc, submitted.proposalPublicId()));
            assertEquals(0, decisionCount(submitted.proposalPublicId()));
            assertEquals("+62 812 100 100", jdbc.queryForObject(
                "select personal_phone from employee_contact_profile where employee_id = ?",
                String.class, arena.memberInternalId()));
            assertEquals(3L, jdbc.queryForObject("select phone_version from employee_contact_profile where employee_id = ?",
                Long.class, arena.memberInternalId()));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void untouchedContactCorrectionCommittedDuringApprovalWaitIsPreserved() throws Exception {
        Arena arena = seedArena();
        ProposalResult submitted = pendingProposal(arena, "+62 812 222 444");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ProposalResult> queued = waitBehindTransaction(pool,
                () -> correct(arena, 0, 0, List.of(new ChangePayload("personalEmail", "verified@example.com"))),
                () -> approve(arena, submitted, "approve-" + UUID.randomUUID()));
            assertEquals("APPROVED", queued.get(20, TimeUnit.SECONDS).state());
            Map<String, Object> contact = jdbc.queryForMap(
                "select personal_phone, personal_email, phone_version, email_version from employee_contact_profile where employee_id = ?",
                arena.memberInternalId());
            assertEquals("+62 812 222 444", contact.get("personal_phone"));
            assertEquals("verified@example.com", contact.get("personal_email"));
            assertEquals(1L, contact.get("phone_version"));
            assertEquals(1L, contact.get("email_version"));
        } finally {
            pool.shutdownNow();
        }
    }

    private void reviewerAuthorityChangeWhileWaiting(boolean cutoff) throws Exception {
        Arena arena = seedArena();
        ProposalResult submitted = pendingProposal(arena, "+62 812 987 654");
        Instant expiry = Instant.now(clock).plusSeconds(60);
        if (cutoff) {
            jdbc.update("update user_accounts set access_ends_at = ? where public_id = ?::uuid",
                Timestamp.from(expiry), arena.reviewerAccountId());
        } else {
            jdbc.update("update role_assignments set effective_to = ? where account_id = "
                + "(select id from user_accounts where public_id = ?::uuid)",
                Timestamp.from(expiry), arena.reviewerAccountId());
        }
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ProposalResult> queued = waitBehindTransaction(pool,
                () -> jdbc.queryForObject("select id from employees where id = ? for update", Long.class,
                    arena.memberInternalId()),
                () -> approve(arena, submitted, "approve-" + UUID.randomUUID()),
                () -> clock.advanceSeconds(61));
            ExecutionException denial = assertThrows(ExecutionException.class, () -> queued.get(20, TimeUnit.SECONDS));
            assertTrue(denial.getCause() instanceof AccessDeniedException);
            assertEquals("PENDING", proposalState(jdbc, submitted.proposalPublicId()));
            assertEquals(0, decisionCount(submitted.proposalPublicId()));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void approvalRevalidatesReviewerAuthorityAfterEmployeeLockWait() throws Exception {
        reviewerAuthorityChangeWhileWaiting(false);
    }

    @Test
    void approvalRevalidatesReviewerCutoffAfterEmployeeLockWait() throws Exception {
        reviewerAuthorityChangeWhileWaiting(true);
    }

    private ProposalResult draft(Arena arena) {
        return as(arena.member(), () -> proposalCommands.create(arena.member(), "create-" + UUID.randomUUID(),
            arena.memberEmployeeId(), List.of(new ChangePayload("personalPhone", "+62 812 456 123"))));
    }

    private UUID publish(Arena arena, String unit, String level, UUID reviewer) {
        UUID setId = UUID.randomUUID();
        as(arena.hr(), () -> ruleCommands.publish(arena.hr(), "publish-" + UUID.randomUUID(), setId,
            List.of(new RulePayload("route-" + unit, 10, unit, level, reviewer))));
        return setId;
    }

    private Object activate(Arena arena, UUID setId) {
        return as(arena.hr(), () -> ruleCommands.activate(arena.hr(), "activate-" + UUID.randomUUID(), setId));
    }

    @Test
    void submitWaitsForGlobalActivationAndPinsCommittedConfiguration() throws Exception {
        Arena arena = seedArena();
        ProposalResult created = draft(arena);
        UUID setId = publish(arena, "ENG", "L3", arena.reviewerTwoAccountId());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ProposalResult> queued = waitBehindTransaction(pool, () -> activate(arena, setId),
                () -> as(arena.member(), () -> proposalCommands.submit(arena.member(), "submit-" + UUID.randomUUID(),
                    created.proposalPublicId(), created.proposalVersion())));
            ProposalResult submitted = queued.get(20, TimeUnit.SECONDS);
            assertEquals("PENDING", submitted.state());
            assertEquals(setId, jdbc.queryForObject("select rule_set_public_id from profile_revision where public_id = ?::uuid",
                UUID.class, submitted.revisionPublicId()));
            assertEquals(arena.reviewerTwoAccountId(), jdbc.queryForObject(
                "select assignee_account_public_id from profile_assignment where public_id = ?::uuid",
                UUID.class, submitted.assignmentPublicId()));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void blockedRetryWaitsForActivationAndRetainsOriginalRevisionInputs() throws Exception {
        Arena arena = seedArena();
        UUID nonmatching = publish(arena, "ENG", "L4", arena.reviewerAccountId());
        activate(arena, nonmatching);
        ProposalResult blocked = pendingProposal(arena, "+62 812 321 987");
        assertEquals("NO_MATCH", blocked.blockedReason());
        UUID matching = publish(arena, "ENG", "L3", arena.reviewerTwoAccountId());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            UUID pinnedContext = jdbc.queryForObject("select org_assignment_public_id from profile_revision where public_id = ?::uuid",
                UUID.class, blocked.revisionPublicId());
            Future<ProposalResult> queued = waitBehindTransaction(pool, () -> {
                reviseContext(arena, "ENG", "L4");
                return activate(arena, matching);
            }, () -> as(arena.hr(), () -> proposalCommands.retryRoute(arena.hr(), "retry-" + UUID.randomUUID(),
                    blocked.proposalPublicId(), blocked.proposalVersion())));
            ProposalResult retried = queued.get(20, TimeUnit.SECONDS);
            assertNull(retried.blockedReason());
            assertEquals(blocked.revisionPublicId(), retried.revisionPublicId());
            assertEquals(pinnedContext, jdbc.queryForObject("select org_assignment_public_id from profile_revision where public_id = ?::uuid",
                UUID.class, blocked.revisionPublicId()));
            assertEquals("L3", jdbc.queryForObject("select evaluated_job_level from profile_revision where public_id = ?::uuid",
                String.class, blocked.revisionPublicId()));
            assertEquals("L4", jdbc.queryForObject("select job_level from employee_assignments where employee_id = ? "
                + "and superseded_at is null", String.class, arena.memberInternalId()));
            assertEquals(nonmatching, jdbc.queryForObject("select rule_set_public_id from profile_revision where public_id = ?::uuid",
                UUID.class, blocked.revisionPublicId()));
            assertEquals(matching, jdbc.queryForObject("select rule_set_public_id from profile_route_evaluation "
                + "where proposal_id = (select id from profile_proposal where public_id = ?::uuid) order by id desc limit 1",
                UUID.class, blocked.proposalPublicId()));
        } finally {
            pool.shutdownNow();
        }
    }

    private Object reviseContext(Arena arena, String unit, String level) {
        UUID assignmentId = jdbc.queryForObject("select public_id from employee_assignments where employee_id = ? "
            + "and superseded_at is null", UUID.class, arena.memberInternalId());
        Instant from = jdbc.queryForObject("select valid_from from employee_assignments where public_id = ?::uuid",
            Timestamp.class, assignmentId).toInstant();
        return as(arena.hr(), () -> employeeCommands.reviseAssignment(arena.hr(), "revise-" + UUID.randomUUID(),
            arena.memberEmployeeId(), assignmentId, unit, null, level, from, null, "Correct current context"));
    }

    @Test
    void submitWaitsForOrganizationCorrectionAndPinsCommittedJobLevel() throws Exception {
        Arena arena = seedArena();
        ProposalResult created = draft(arena);
        UUID matching = publish(arena, "ENG", "L4", arena.reviewerTwoAccountId());
        activate(arena, matching);
        UUID originalContext = jdbc.queryForObject("select public_id from employee_assignments where employee_id = ? "
            + "and superseded_at is null", UUID.class, arena.memberInternalId());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ProposalResult> queued = waitBehindTransaction(pool, () -> reviseContext(arena, "ENG", "L4"),
                () -> as(arena.member(), () -> proposalCommands.submit(arena.member(), "submit-" + UUID.randomUUID(),
                    created.proposalPublicId(), created.proposalVersion())));
            ProposalResult submitted = queued.get(20, TimeUnit.SECONDS);
            assertNull(submitted.blockedReason());
            assertEquals("L4", jdbc.queryForObject("select evaluated_job_level from profile_revision where public_id = ?::uuid",
                String.class, submitted.revisionPublicId()));
            UUID currentContext = jdbc.queryForObject("select public_id from employee_assignments where employee_id = ? "
                + "and superseded_at is null", UUID.class, arena.memberInternalId());
            assertEquals(currentContext, jdbc.queryForObject("select org_assignment_public_id from profile_revision where public_id = ?::uuid",
                UUID.class, submitted.revisionPublicId()));
            assertEquals(originalContext, jdbc.queryForObject("select org_assignment_predecessor_public_id from profile_revision where public_id = ?::uuid",
                UUID.class, submitted.revisionPublicId()));
            assertEquals(arena.reviewerTwoAccountId(), jdbc.queryForObject(
                "select assignee_account_public_id from profile_assignment where public_id = ?::uuid",
                UUID.class, submitted.assignmentPublicId()));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void scopedReadWaitsForOrganizationCorrectionAndCannotReadNewOutOfScopeContext() throws Exception {
        Arena arena = seedArena();
        ProposalResult submitted = pendingProposal(arena, "+62 812 222 999");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> queued = waitBehindTransaction(pool, () -> reviseContext(arena, "FIN", "L3"),
                () -> as(arena.reviewer(), () -> proposalService.getProposal(submitted.proposalPublicId(), arena.reviewer())));
            ExecutionException denial = assertThrows(ExecutionException.class, () -> queued.get(20, TimeUnit.SECONDS));
            assertTrue(denial.getCause() instanceof ResourceNotFoundException);
            assertEquals("PENDING", proposalState(jdbc, submitted.proposalPublicId()));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void outboxFailureAfterAppliedProfileChangesRollsBackEntireApproval() {
        Arena arena = seedArena();
        correct(arena, 0, 0, List.of(new ChangePayload("personalPhone", "+62 812 000 100")));
        ProposalResult created = as(arena.member(), () -> proposalCommands.create(arena.member(),
            "create-" + UUID.randomUUID(), arena.memberEmployeeId(), List.of(
                new ChangePayload("personalPhone", "+62 812 000 200"),
                new ChangePayload("personalEmail", "submitted@example.com"),
                new ChangePayload("residentialAddress", "Jakarta office"),
                new ChangePayload("emergencyContactName", "Alex"),
                new ChangePayload("emergencyContactRelationship", "Sibling"),
                new ChangePayload("emergencyContactPhone", "+62 812 000 300"))));
        ProposalResult submitted = as(arena.member(), () -> proposalCommands.submit(arena.member(),
            "submit-" + UUID.randomUUID(), created.proposalPublicId(), created.proposalVersion()));
        Map<String, Object> before = jdbc.queryForMap("select personal_phone, personal_email, residential_address, "
            + "emergency_contact_name, emergency_contact_relationship, emergency_contact_phone, "
            + "phone_version, email_version, address_version, emergency_version, updated_at "
            + "from employee_contact_profile where employee_id = ?", arena.memberInternalId());
        int audits = aggregateRows("audit_events", submitted.proposalPublicId());
        int outboxes = aggregateRows("outbox_events", submitted.proposalPublicId());
        String key = "rollback-" + UUID.randomUUID();
        DATABASE.jdbc.execute("create function fail_profile_approval_outbox() returns trigger language plpgsql as $$ "
            + "begin if NEW.event_type = 'PROFILE_APPROVE' then "
            + "if exists(select 1 from employee_contact_profile where personal_phone = '+62 812 000 200' "
            + "and personal_email = 'submitted@example.com' and phone_version = 2 and emergency_version = 1) then "
            + "raise exception 'Injected after profile mutation' using errcode = '45000'; "
            + "else raise exception 'Profile mutation did not precede outbox' using errcode = '45001'; end if; "
            + "end if; return NEW; end; $$");
        DATABASE.jdbc.execute("create trigger fail_profile_approval_outbox before insert on outbox_events "
            + "for each row execute function fail_profile_approval_outbox()");
        try {
            RuntimeException failure = assertThrows(RuntimeException.class, () -> approve(arena, submitted, key));
            assertEquals("45000", rootSqlState(failure));
            assertEquals(before, jdbc.queryForMap("select personal_phone, personal_email, residential_address, "
                + "emergency_contact_name, emergency_contact_relationship, emergency_contact_phone, "
                + "phone_version, email_version, address_version, emergency_version, updated_at "
                + "from employee_contact_profile where employee_id = ?", arena.memberInternalId()));
            assertEquals("PENDING", proposalState(jdbc, submitted.proposalPublicId()));
            assertEquals(0, decisionCount(submitted.proposalPublicId()));
            assertEquals("ACTIVE", jdbc.queryForObject("select status from profile_assignment where public_id = ?::uuid",
                String.class, submitted.assignmentPublicId()));
            assertEquals(0, jdbc.queryForObject("select count(*) from command_receipts where idempotency_key = ?",
                Integer.class, key));
            assertEquals(audits, aggregateRows("audit_events", submitted.proposalPublicId()));
            assertEquals(outboxes, aggregateRows("outbox_events", submitted.proposalPublicId()));
        } finally {
            DATABASE.jdbc.execute("drop trigger fail_profile_approval_outbox on outbox_events");
            DATABASE.jdbc.execute("drop function fail_profile_approval_outbox()");
        }
        assertEquals("APPROVED", approve(arena, submitted, key).state());
        assertEquals(1, decisionCount(submitted.proposalPublicId()));
        assertEquals(audits + 1, aggregateRows("audit_events", submitted.proposalPublicId()));
        assertEquals(outboxes + 1, aggregateRows("outbox_events", submitted.proposalPublicId()));
    }

    private int aggregateRows(String table, UUID proposalId) {
        return jdbc.queryForObject("select count(*) from " + table + " where aggregate_public_id = ?::uuid",
            Integer.class, proposalId);
    }

    private static String rootSqlState(Throwable failure) {
        String state = null;
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql) {
                state = sql.getSQLState();
            }
        }
        return state;
    }
}
