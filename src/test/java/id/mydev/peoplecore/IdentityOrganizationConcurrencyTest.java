package id.mydev.peoplecore;

import id.mydev.peoplecore.common.command.CommandConflictException;
import id.mydev.peoplecore.common.command.CommandExecutionCoordinator;
import id.mydev.peoplecore.common.command.CommandExecutionService;
import id.mydev.peoplecore.identity.application.command.EnrollmentCommands;
import id.mydev.peoplecore.identity.application.command.EnrollmentResults;
import id.mydev.peoplecore.identity.application.policy.HrisAccessPolicy;
import id.mydev.peoplecore.identity.application.service.AccountAccessService;
import id.mydev.peoplecore.identity.application.service.AuthGenerationService;
import id.mydev.peoplecore.identity.application.service.EnrollmentService;
import id.mydev.peoplecore.identity.application.trail.IdentityTrailMapper;
import id.mydev.peoplecore.identity.domain.exception.ActivationThrottledException;
import id.mydev.peoplecore.identity.domain.exception.BindingConflictException;
import id.mydev.peoplecore.identity.domain.exception.InvitationConflictException;
import id.mydev.peoplecore.identity.domain.repository.EnrollmentInvitationRepository;
import id.mydev.peoplecore.identity.domain.model.HrisRole;
import id.mydev.peoplecore.identity.domain.model.RoleAssignment;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.identity.domain.repository.RoleAssignmentRepository;
import id.mydev.peoplecore.identity.domain.repository.UserAccountRepository;
import id.mydev.peoplecore.organization.application.command.EmployeeCommands;
import id.mydev.peoplecore.organization.application.command.EmployeeResults;
import id.mydev.peoplecore.organization.application.service.EmployeeService;
import id.mydev.peoplecore.organization.application.trail.OrganizationTrailMapper;
import id.mydev.peoplecore.organization.domain.exception.AssignmentOverlapException;
import id.mydev.peoplecore.support.JwtAuthFixture;
import id.mydev.peoplecore.support.PostgresFixture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("postgres")
@SpringBootTest(properties = {
    "peoplecore.runtime.role=worker",
    "spring.jpa.hibernate.ddl-auto=validate",
    "peoplecore.organization.allowed-units=ENG,FIN"
})
class IdentityOrganizationConcurrencyTest {

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

    @Autowired EmployeeService employees;
    @Autowired EnrollmentService enrollments;
    @Autowired EmployeeCommands employeeCommands;
    @Autowired EnrollmentCommands enrollmentCommands;
    @Autowired AccountAccessService access;
    @Autowired AuthGenerationService generations;
    @Autowired HrisAccessPolicy policy;
    @Autowired CommandExecutionCoordinator coordinator;
    @Autowired UserAccountRepository accountRepository;
    @Autowired RoleAssignmentRepository roleRepository;
    @Autowired EnrollmentInvitationRepository invitationRepository;
    @Autowired PlatformTransactionManager transactions;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    private Authentication hrCaller;

    @BeforeEach
    void seedHr() {
        Instant now = Instant.now();
        String subject = "hr-" + UUID.randomUUID();
        UserAccount hr = accountRepository.save(new UserAccount(UUID.randomUUID(), ISSUER, subject, now));
        roleRepository.save(new RoleAssignment(hr.getId(), HrisRole.HR_ADMIN, null, now.minusSeconds(60), now));
        roleRepository.save(new RoleAssignment(hr.getId(), HrisRole.SYSTEM_ADMIN, null, now.minusSeconds(60), now));
        hrCaller = JwtAuthFixture.token(ISSUER, subject);
        SecurityContextHolder.getContext().setAuthentication(hrCaller);
    }

    @AfterEach
    void clearCaller() {
        SecurityContextHolder.clearContext();
    }

    @AfterAll
    static void closeDatabase() {
        DATABASE.close();
    }

    private static CommandExecutionService.CommandContext command(String actor, String type, String key) {
        return new CommandExecutionService.CommandContext(actor, type, key, "{}", null);
    }

    private UUID createEmployee() {
        return employeeCommands.create(hrCaller, "create-" + UUID.randomUUID(), "EMP-" + UUID.randomUUID())
            .employeeId();
    }

    @Test
    void identityHardeningSchemaIsApplied() {
        List<String> columns = jdbc.queryForList(
            "select column_name from information_schema.columns where table_schema = current_schema() "
                + "and table_name in ('enrollment_invitations', 'employee_assignments')", String.class);
        assertTrue(columns.contains("expected_issuer"));
        assertTrue(columns.contains("expected_subject"));
        assertTrue(columns.contains("public_id"));
        List<String> indexes = jdbc.queryForList(
            "select indexname from pg_indexes where schemaname = current_schema()", String.class);
        assertTrue(indexes.contains("uk_active_binding_employee"));
        assertTrue(indexes.contains("uk_active_binding_account"));
        assertTrue(indexes.contains("uk_active_binding_identity"));
        assertEquals(1, jdbc.queryForObject("select count(*) from auth_generation where id = 1", Integer.class));
    }

    @Test
    void activationFailuresRecordedAndThrottleRecipient() {
        UUID employeeId = createEmployee();
        String subject = "throttle-" + UUID.randomUUID();
        var issued = enrollmentCommands.issue(hrCaller, "invite-" + UUID.randomUUID(),
            employeeId, "hrd@example.id", ISSUER, subject);
        Authentication member = JwtAuthFixture.token(ISSUER, subject);
        for (int attempt = 0; attempt < 5; attempt++) {
            String guess = "bad-" + attempt;
            Authentication attemptCaller = member;
            assertThrows(InvitationConflictException.class, () -> as(attemptCaller,
                () -> enrollmentCommands.activate(attemptCaller, "bad-" + guess + UUID.randomUUID(),
                    issued.result().invitationId(), guess)));
        }
        assertEquals(5, jdbc.queryForObject(
            "select count(*) from activation_attempts where invitation_public_id = ?",
            Integer.class, issued.result().invitationId()));
        assertThrows(ActivationThrottledException.class, () -> as(member,
            () -> enrollmentCommands.activate(member, "last-" + UUID.randomUUID(),
                issued.result().invitationId(), issued.secret())));
    }

    @Test
    void outsiderCannotSpendRecipientBudget() {
        UUID employeeId = createEmployee();
        String subject = "victim-" + UUID.randomUUID();
        var issued = enrollmentCommands.issue(hrCaller, "invite-" + UUID.randomUUID(),
            employeeId, "hrd@example.id", ISSUER, subject);
        Authentication outsider = JwtAuthFixture.token(ISSUER, "outsider-" + UUID.randomUUID());
        for (int attempt = 0; attempt < 5; attempt++) {
            String guess = "outsider-" + attempt;
            assertThrows(InvitationConflictException.class, () -> as(outsider,
                () -> enrollmentCommands.activate(outsider, "out-" + guess + UUID.randomUUID(),
                    issued.result().invitationId(), guess)));
        }
        assertThrows(ActivationThrottledException.class, () -> as(outsider,
            () -> enrollmentCommands.activate(outsider, "out-once-more-" + UUID.randomUUID(),
                issued.result().invitationId(), "outsider-x")));
        Authentication member = JwtAuthFixture.token(ISSUER, subject);
        var bound = as(member, () -> enrollmentCommands.activate(member, "act-" + UUID.randomUUID(),
            issued.result().invitationId(), issued.secret()));
        assertEquals(employeeId, bound.employeeId());
    }

    @Test
    void rotatingInvitationIdsHitGlobalBudget() {
        Authentication drifter = JwtAuthFixture.token(ISSUER, "drifter-" + UUID.randomUUID());
        for (int attempt = 0; attempt < 20; attempt++) {
            String tag = "drift-" + attempt;
            assertThrows(InvitationConflictException.class, () -> as(drifter,
                () -> enrollmentCommands.activate(drifter, tag + UUID.randomUUID(),
                    UUID.randomUUID(), "guess")));
        }
        assertThrows(ActivationThrottledException.class, () -> as(drifter,
            () -> enrollmentCommands.activate(drifter, "drift-last-" + UUID.randomUUID(),
                UUID.randomUUID(), "guess")));
        Authentication fresh = JwtAuthFixture.token(ISSUER, "fresh-" + UUID.randomUUID());
        assertThrows(InvitationConflictException.class, () -> as(fresh,
            () -> enrollmentCommands.activate(fresh, "fresh-" + UUID.randomUUID(),
                UUID.randomUUID(), "guess")));
    }

    @Test
    void concurrentOverlappingAssignmentsKeepSingleWinner() throws Exception {
        UUID employeeId = createEmployee();
        employeeCommands.addAssignment(hrCaller, "seed-" + UUID.randomUUID(), employeeId, "ENG", null, "L0",
            Instant.now().minusSeconds(3600), Instant.now().minusSeconds(1800));
        Instant from = Instant.now().plusSeconds(3600);
        Instant to = from.plusSeconds(3600);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            Future<?> first = pool.submit(() -> guarded(ready, go, hrCaller,  () ->
                employeeCommands.addAssignment(hrCaller, "race-a-" + UUID.randomUUID(), employeeId,
                    "ENG", null, "L3", from, to)));
            Future<?> second = pool.submit(() -> guarded(ready, go, hrCaller,  () ->
                employeeCommands.addAssignment(hrCaller, "race-b-" + UUID.randomUUID(), employeeId,
                    "FIN", null, "L4", from.plusSeconds(10), to.plusSeconds(10))));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            int conflicts = 0;
            for (Future<?> future : List.of(first, second)) {
                try {
                    future.get(30, TimeUnit.SECONDS);
                } catch (java.util.concurrent.ExecutionException ex) {
                    if (ex.getCause() instanceof AssignmentOverlapException) {
                        conflicts++;
                    } else {
                        throw ex;
                    }
                }
            }
            assertEquals(1, conflicts);
            assertEquals(2, employees.listAssignments(employeeId, hrCaller).size());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void accessRevokedWhileWaitingOnLockDeniesMutation() throws Exception {
        UUID employeeId = createEmployee();
        Long internalId = employees.requireInternalByPublicId(employeeId).getId();
        Instant from = Instant.now().plusSeconds(3600);
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            CountDownLatch locked = new CountDownLatch(1);
            CountDownLatch attempting = new CountDownLatch(1);
            Future<?> worker = pool.submit(() -> {
                SecurityContextHolder.getContext().setAuthentication(hrCaller);
                try {
                    if (!locked.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Lock was not acquired first");
                    }
                    attempting.countDown();
                    employeeCommands.addAssignment(hrCaller, "wait-" + UUID.randomUUID(), employeeId,
                        "ENG", null, "L3", from, null);
                    return null;
                } catch (AccessDeniedException ex) {
                    throw new RuntimeException(ex);
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });
            new TransactionTemplate(transactions).execute(status -> {
                employees.lockEmployee(internalId).orElseThrow();
                locked.countDown();
                try {
                    if (!attempting.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Worker did not attempt mutation");
                    }
                    Thread.sleep(500);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                Instant now = Instant.now();
                for (RoleAssignment role : roleRepository.findByAccountId(
                    access.resolveAccount(hrCaller).orElseThrow().getId())) {
                    role.endAt(now);
                    roleRepository.save(role);
                }
                return null;
            });
            try {
                worker.get(30, TimeUnit.SECONDS);
                throw new AssertionError("Worker mutation should have been denied");
            } catch (java.util.concurrent.ExecutionException ex) {
                assertTrue(ex.getCause() instanceof RuntimeException
                    && ex.getCause().getCause() instanceof AccessDeniedException);
            }
            assertEquals(0, jdbc.queryForObject(
                "select count(*) from employee_assignments where employee_id = ?",
                Integer.class, internalId));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentActivationsBindExactlyOnce() throws Exception {
        UUID employeeId = createEmployee();
        String subject = "race-" + UUID.randomUUID();
        var issued = enrollmentCommands.issue(hrCaller,
            "invite-" + UUID.randomUUID(), employeeId, "hrd@example.id", ISSUER, subject);
        Authentication member = JwtAuthFixture.token(ISSUER, subject);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            Future<?> first = pool.submit(() -> guarded(ready, go, member,  () ->
                enrollmentCommands.activate(member, "act-a-" + UUID.randomUUID(),
                    issued.result().invitationId(), issued.secret())));
            Future<?> second = pool.submit(() -> guarded(ready, go, member,  () ->
                enrollmentCommands.activate(member, "act-b-" + UUID.randomUUID(),
                    issued.result().invitationId(), issued.secret())));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            int bound = 0;
            int rejected = 0;
            for (Future<?> future : List.of(first, second)) {
                try {
                    future.get(30, TimeUnit.SECONDS);
                    bound++;
                } catch (java.util.concurrent.ExecutionException ex) {
                    rejected++;
                }
            }
            assertEquals(1, bound);
            assertEquals(1, rejected);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentIssuanceLeavesSingleUsableInvitation() throws Exception {
        UUID employeeId = createEmployee();
        String subject = "dupe-" + UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            Future<?> first = pool.submit(() -> guarded(ready, go, hrCaller,  () ->
                enrollmentCommands.issue(hrCaller, "issue-a-" + UUID.randomUUID(), employeeId,
                    "hrd@example.id", ISSUER, subject)));
            Future<?> second = pool.submit(() -> guarded(ready, go, hrCaller,  () ->
                enrollmentCommands.issue(hrCaller, "issue-b-" + UUID.randomUUID(), employeeId,
                    "hrd@example.id", ISSUER, subject)));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);
            Long internalId = employees.requireInternalByPublicId(employeeId).getId();
            assertEquals(1, jdbc.queryForObject(
                "select count(*) from enrollment_invitations where employee_id = ? and status = 'ISSUED'",
                Integer.class, internalId));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void activationRacingReissueStaysConsistent() throws Exception {
        UUID employeeId = createEmployee();
        String subject = "split-" + UUID.randomUUID();
        var issued = enrollmentCommands.issue(hrCaller,
            "invite-" + UUID.randomUUID(), employeeId, "hrd@example.id", ISSUER, subject);
        Authentication member = JwtAuthFixture.token(ISSUER, subject);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            Future<?> activate = pool.submit(() -> guarded(ready, go, member,  () -> {
                try {
                    enrollmentCommands.activate(member, "act-" + UUID.randomUUID(),
                        issued.result().invitationId(), issued.secret());
                    return "activated";
                } catch (RuntimeException ex) {
                    return "rejected";
                }
            }));
            Future<?> reissue = pool.submit(() -> guarded(ready, go, hrCaller,  () -> {
                enrollmentCommands.reissue(hrCaller, "reissue-" + UUID.randomUUID(), employeeId,
                    "hrd@example.id", ISSUER, subject);
                return "reissued";
            }));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            String activation = (String) activate.get(30, TimeUnit.SECONDS);
            reissue.get(30, TimeUnit.SECONDS);
            Long internalId = employees.requireInternalByPublicId(employeeId).getId();
            long consumed = jdbc.queryForObject(
                "select count(*) from enrollment_invitations where employee_id = ? and status = 'CONSUMED'",
                Long.class, internalId).longValue();
            long issuedCount = jdbc.queryForObject(
                "select count(*) from enrollment_invitations where employee_id = ? and status = 'ISSUED'",
                Long.class, internalId).longValue();
            long bindings = jdbc.queryForObject(
                "select count(*) from account_bindings where employee_id = ? and revoked_at is null",
                Long.class, internalId).longValue();
            if ("activated".equals(activation)) {
                assertEquals(1L, consumed);
                assertEquals(1L, bindings);
            } else {
                assertEquals(0L, consumed);
                assertEquals(1L, issuedCount);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void rebindOnFlywaySchemaTransfersAccessWithHistory() {
        UUID employeeId = createEmployee();
        String firstSubject = "bind-" + UUID.randomUUID();
        var issued = enrollmentCommands.issue(hrCaller,
            "invite-" + UUID.randomUUID(), employeeId, "hrd@example.id", ISSUER, firstSubject);
        as(JwtAuthFixture.token(ISSUER, firstSubject), () -> enrollmentCommands.activate(
            JwtAuthFixture.token(ISSUER, firstSubject),
            "act-" + UUID.randomUUID(), issued.result().invitationId(), issued.secret()));
        Long internalId = employees.requireInternalByPublicId(employeeId).getId();
        Long originalAccount = jdbc.queryForObject(
            "select account_id from account_bindings where employee_id = ? and revoked_at is null",
            Long.class, internalId);
        roleRepository.save(new RoleAssignment(originalAccount, HrisRole.HR_ADMIN, null,
            Instant.now().minusSeconds(60), Instant.now()));

        String nextSubject = "rebind-" + UUID.randomUUID();
        enrollmentCommands.rebind(hrCaller, "rebind-" + UUID.randomUUID(), employeeId, ISSUER, nextSubject, "IDV-100");
        assertEquals(2, jdbc.queryForObject(
            "select count(*) from account_bindings where employee_id = ?", Integer.class, internalId));
        assertEquals(1, jdbc.queryForObject(
            "select count(*) from account_bindings where employee_id = ? and revoked_at is null",
            Integer.class, internalId));
        assertThrows(AccessDeniedException.class,
            () -> access.requireActive(JwtAuthFixture.token(ISSUER, firstSubject), Instant.now()));
        Authentication replacement = JwtAuthFixture.token(ISSUER, nextSubject);
        access.requireActive(replacement, Instant.now());
        assertTrue(!access.hasRole(originalAccount, HrisRole.HR_ADMIN, Instant.now()));
    }

    @Test
    void commandReceiptReplaysIssuanceWithoutDuplicateInvite() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(hrCaller);
        UUID employeeId = createEmployee();
        String subject = "cmd-" + UUID.randomUUID();
        String key = "invite-" + UUID.randomUUID();
        var first = enrollmentCommands.issue(hrCaller, key, employeeId, "hrd@example.id", ISSUER, subject);
        var replayed = enrollmentCommands.issue(hrCaller, key, employeeId, "hrd@example.id", ISSUER, subject);
        assertEquals(first.result().invitationId(), replayed.result().invitationId());
        assertTrue(first.secret() != null && !first.secret().isBlank());
        assertEquals(null, replayed.secret());
        String storedPayload = jdbc.queryForObject(
            "select result_payload from command_receipts where idempotency_key = ?", String.class, key);
        assertTrue(!storedPayload.contains(first.secret()));
        assertTrue(!storedPayload.contains("\"secret\""));
        Long internalId = employees.requireInternalByPublicId(employeeId).getId();
        assertEquals(1, jdbc.queryForObject(
            "select count(*) from enrollment_invitations where employee_id = ?", Integer.class, internalId));
        assertThrows(CommandConflictException.class, () -> enrollmentCommands.issue(
            hrCaller, key, employeeId, "changed@example.id", ISSUER, subject));
    }

    @Test
    void cutoffDeniesProtectedCommandsReadsAndReplay() {
        UUID employeeId = createEmployee();
        String subject = "off-" + UUID.randomUUID();
        var issued = enrollmentCommands.issue(hrCaller,
            "invite-" + UUID.randomUUID(), employeeId, "hrd@example.id", ISSUER, subject);
        Authentication member = JwtAuthFixture.token(ISSUER, subject);
        as(member, () -> enrollmentCommands.activate(member, "act-" + UUID.randomUUID(),
            issued.result().invitationId(), issued.secret()));

        Instant future = Instant.now().plusSeconds(3600);
        enrollmentCommands.offboard(hrCaller, "off-1-" + UUID.randomUUID(), employeeId, future, null, null, null);
        access.requireActive(member, Instant.now());

        enrollmentCommands.offboard(hrCaller, "off-2-" + UUID.randomUUID(), employeeId,
            Instant.now().minusSeconds(5), null, null, null);
        assertThrows(AccessDeniedException.class, () -> access.requireActive(member, Instant.now()));
        String actor = member.getName();
        assertThrows(AccessDeniedException.class,
            () -> policy.checkCommand(member, command(actor, "EMPLOYEE_CREATE", "k1")));
        assertThrows(AccessDeniedException.class,
            () -> policy.checkReplay(member, command(actor, "EMPLOYEE_CREATE", "k2"), "{}"));

        long before = generations.current();
        enrollmentCommands.rehire(hrCaller, "rehire-" + UUID.randomUUID(), employeeId, "Back");
        enrollmentCommands.rehire(hrCaller, "rehire-null-" + UUID.randomUUID(), employeeId, null);
        assertTrue(generations.current() > before);
        assertThrows(AccessDeniedException.class, () -> access.requireActive(member, Instant.now()));

        enrollmentCommands.restoreAccess(hrCaller, "restore-" + UUID.randomUUID(), employeeId,
            List.of(HrisRole.EMPLOYEE), null, "Reviewed");
        access.requireActive(member, Instant.now());
        UUID unbound = createEmployee();
        assertThrows(BindingConflictException.class, () -> enrollments.restoreAccess(
            unbound, List.of(HrisRole.EMPLOYEE), null, hrCaller));
    }

    @Test
    void replayRevokedDuringAdvisoryWaitIsDenied() throws Exception {
        UUID employeeId = createEmployee();
        String key = "replay-wait-" + UUID.randomUUID();
        String actor = hrCaller.getName();
        employeeCommands.updateNumber(hrCaller, key, employeeId, "EMP-W1");
        String storedHash = jdbc.queryForObject(
            "select request_hash from command_receipts where idempotency_key = ?", String.class, key);
        String storedResult = jdbc.queryForObject(
            "select result_payload from command_receipts where idempotency_key = ?", String.class, key);
        long auditsBefore = jdbc.queryForObject(
            "select count(*) from audit_events where aggregate_public_id = ?", Long.class, employeeId);
        long outboxBefore = jdbc.queryForObject(
            "select count(*) from outbox_events where aggregate_public_id = ?", Long.class, employeeId);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<?> holder = pool.submit(() -> {
                SecurityContextHolder.getContext().setAuthentication(hrCaller);
                try {
                    new TransactionTemplate(transactions).execute(status -> {
                        coordinator.lock(actor, "EMPLOYEE_UPDATE", key);
                        holding.countDown();
                        try {
                            if (!release.await(15, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("Lock was not released");
                            }
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(ex);
                        }
                        return null;
                    });
                    return null;
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });
            assertTrue(holding.await(10, TimeUnit.SECONDS));
            Future<?> replay = pool.submit(() -> {
                SecurityContextHolder.getContext().setAuthentication(hrCaller);
                try {
                    employeeCommands.updateNumber(hrCaller, key, employeeId, "EMP-W1");
                    return null;
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });
            awaitAdvisoryWait();
            Instant now = Instant.now();
            for (RoleAssignment role : roleRepository.findByAccountId(
                access.resolveAccount(hrCaller).orElseThrow().getId())) {
                role.endAt(now);
                roleRepository.save(role);
            }
            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
            try {
                replay.get(30, TimeUnit.SECONDS);
                throw new AssertionError("Replay after revocation should have been denied");
            } catch (java.util.concurrent.ExecutionException ex) {
                assertTrue(ex.getCause() instanceof AccessDeniedException);
            }
            assertEquals(storedHash, jdbc.queryForObject(
                "select request_hash from command_receipts where idempotency_key = ?", String.class, key));
            assertEquals(storedResult, jdbc.queryForObject(
                "select result_payload from command_receipts where idempotency_key = ?", String.class, key));
            assertEquals(auditsBefore, jdbc.queryForObject(
                "select count(*) from audit_events where aggregate_public_id = ?", Long.class, employeeId));
            assertEquals(outboxBefore, jdbc.queryForObject(
                "select count(*) from outbox_events where aggregate_public_id = ?", Long.class, employeeId));
            assertEquals("EMP-W1", employees.requireInternalByPublicId(employeeId).getEmployeeNumber());
            assertThrows(id.mydev.peoplecore.common.api.ResourceNotFoundException.class,
                () -> employees.get(employeeId, hrCaller));
        } finally {
            pool.shutdownNow();
        }
    }

    private void awaitAdvisoryWait() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject(
                "select count(*) from pg_stat_activity where datname = current_database() and wait_event = 'advisory'",
                Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
        }
        throw new AssertionError("Replay never waited on the advisory lock");
    }

    @Test
    void activationReplayDeniedAfterOffboard() {
        UUID employeeId = createEmployee();
        String subject = "replay-off-" + UUID.randomUUID();
        var issued = enrollmentCommands.issue(hrCaller,
            "invite-" + UUID.randomUUID(), employeeId, "hrd@example.id", ISSUER, subject);
        Authentication member = JwtAuthFixture.token(ISSUER, subject);
        String key = "activate-" + UUID.randomUUID();
        as(member, () -> enrollmentCommands.activate(member, key, issued.result().invitationId(), issued.secret()));
        enrollmentCommands.offboard(hrCaller, "off-" + UUID.randomUUID(), employeeId,
            Instant.now().minusSeconds(5), null, null, null);
        assertThrows(AccessDeniedException.class, () -> as(member,
            () -> enrollmentCommands.activate(member, key, issued.result().invitationId(), issued.secret())));
    }

    @Test
    void activationReplayDeniedAfterScheduledCutoffPasses() {
        UUID employeeId = createEmployee();
        String subject = "replay-cut-" + UUID.randomUUID();
        var issued = enrollmentCommands.issue(hrCaller,
            "invite-" + UUID.randomUUID(), employeeId, "hrd@example.id", ISSUER, subject);
        Authentication member = JwtAuthFixture.token(ISSUER, subject);
        String key = "activate-" + UUID.randomUUID();
        as(member, () -> enrollmentCommands.activate(member, key, issued.result().invitationId(), issued.secret()));
        enrollmentCommands.offboard(hrCaller, "off-" + UUID.randomUUID(), employeeId,
            Instant.now().plusSeconds(3600), null, null, null);
        as(member, () -> enrollmentCommands.activate(member, key, issued.result().invitationId(), issued.secret()));
        jdbc.update("update user_accounts set access_ends_at = now() - interval '1 second' "
            + "where oidc_issuer = ? and oidc_subject = ?", ISSUER, subject);
        assertThrows(AccessDeniedException.class, () -> as(member,
            () -> enrollmentCommands.activate(member, key, issued.result().invitationId(), issued.secret())));
    }

    @Test
    void rebindPreservesScheduledCutoff() {
        UUID employeeId = createEmployee();
        String subject = "cut-" + UUID.randomUUID();
        var issued = enrollmentCommands.issue(hrCaller,
            "invite-" + UUID.randomUUID(), employeeId, "hrd@example.id", ISSUER, subject);
        Authentication member = JwtAuthFixture.token(ISSUER, subject);
        as(member, () -> enrollmentCommands.activate(member, "act-" + UUID.randomUUID(),
            issued.result().invitationId(), issued.secret()));
        Instant cutoff = Instant.now().plusSeconds(3600);
        enrollmentCommands.offboard(hrCaller, "off-" + UUID.randomUUID(), employeeId,
            cutoff, LocalTime.of(17, 0), "Asia/Jakarta", null);
        enrollmentCommands.rehire(hrCaller, "rehire-" + UUID.randomUUID(), employeeId, null);
        String nextSubject = "cut-next-" + UUID.randomUUID();
        EnrollmentResults.RebindResult rebound = enrollmentCommands.rebind(hrCaller,
            "rebind-" + UUID.randomUUID(), employeeId, ISSUER, nextSubject, "IDV-101");
        Long replacementAccount = jdbc.queryForObject(
            "select account_id from account_bindings where employee_id = ? and revoked_at is null",
            Long.class, employees.requireInternalByPublicId(employeeId).getId());
        Instant carried = jdbc.queryForObject("select access_ends_at from user_accounts where id = ?",
            Instant.class, replacementAccount);
        assertTrue(carried != null && !carried.isAfter(cutoff.plusSeconds(1)) && !carried.isBefore(cutoff.minusSeconds(1)));
        assertEquals("Asia/Jakarta", jdbc.queryForObject(
            "select cutoff_timezone from user_accounts where id = ?", String.class, replacementAccount));
        assertEquals(rebound.accountId(), jdbc.queryForObject(
            "select public_id from user_accounts where id = ?", UUID.class, replacementAccount));
        Authentication replacement = JwtAuthFixture.token(ISSUER, nextSubject);
        access.requireActive(replacement, Instant.now());
        assertThrows(AccessDeniedException.class,
            () -> access.requireActive(replacement, cutoff.plusSeconds(60)));
    }

    @Test
    void restoreOutboxCarriesScopeAndReasonSeparately() {
        UUID employeeId = createEmployee();
        String subject = "ev-" + UUID.randomUUID();
        var issued = enrollmentCommands.issue(hrCaller,
            "invite-" + UUID.randomUUID(), employeeId, "hrd@example.id", ISSUER, subject);
        Authentication member = JwtAuthFixture.token(ISSUER, subject);
        as(member, () -> enrollmentCommands.activate(member, "act-" + UUID.randomUUID(),
            issued.result().invitationId(), issued.secret()));
        enrollmentCommands.offboard(hrCaller, "off-" + UUID.randomUUID(), employeeId,
            Instant.now().minusSeconds(5), null, null, null);
        enrollmentCommands.rehire(hrCaller, "rehire-" + UUID.randomUUID(), employeeId, null);
        EnrollmentResults.RestoreResult restored = enrollmentCommands.restoreAccess(hrCaller,
            "restore-" + UUID.randomUUID(), employeeId, List.of(HrisRole.EMPLOYEE), "ENG", "Reviewed access decision");
        String payload = jdbc.queryForObject(
            "select payload from outbox_events where aggregate_public_id = ? order by id desc limit 1",
            String.class, restored.accountId());
        assertTrue(payload.contains("\"scope\":\"ENG\""));
        assertTrue(payload.contains("\"reason\":\"Reviewed access decision\""));
    }

    @Test
    void concurrentRotatingAdmissionsRespectGlobalBudget() throws Exception {
        Authentication drifter = JwtAuthFixture.token(ISSUER, "race-drifter-" + UUID.randomUUID());
        int racers = 24;
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CountDownLatch ready = new CountDownLatch(racers);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int racer = 0; racer < racers; racer++) {
                futures.add(pool.submit(() -> guarded(ready, go, drifter, () -> {
                    try {
                        enrollmentCommands.activate(drifter, "race-" + UUID.randomUUID(),
                            UUID.randomUUID(), "guess");
                        throw new AssertionError("Unknown invitation should not activate");
                    } catch (ActivationThrottledException throttled) {
                        return "throttled";
                    } catch (InvitationConflictException rejected) {
                        return "rejected";
                    }
                })));
            }
            assertTrue(ready.await(15, TimeUnit.SECONDS));
            go.countDown();
            int throttled = 0;
            int rejected = 0;
            for (Future<String> future : futures) {
                String outcome = future.get(60, TimeUnit.SECONDS);
                if ("throttled".equals(outcome)) {
                    throttled++;
                } else {
                    rejected++;
                }
            }
            assertEquals(20, rejected);
            assertEquals(4, throttled);
            StringBuilder dump = new StringBuilder();
            for (Map<String, Object> row : jdbc.queryForList(
                "select scope, ref_a, ref_b, used from activation_budget_slots")) {
                dump.append(row).append('\n');
            }
            System.err.println("BUDGETDUMP\n" + dump);
            assertEquals(20, jdbc.queryForObject(
                "select count(*) from activation_attempts where principal_subject = ?",
                Integer.class, drifter.getName()));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void activationAndReissueKeepEmployeeFirstLockOrder() throws Exception {
        UUID employeeId = createEmployee();
        String subject = "lock-" + UUID.randomUUID();
        var issued = enrollmentCommands.issue(hrCaller, "invite-" + UUID.randomUUID(),
            employeeId, "hrd@example.id", ISSUER, subject);
        Authentication member = JwtAuthFixture.token(ISSUER, subject);
        Long internalId = employees.requireInternalByPublicId(employeeId).getId();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<?> holder = pool.submit(() -> {
                SecurityContextHolder.getContext().setAuthentication(hrCaller);
                try {
                    return new TransactionTemplate(transactions).execute(status -> {
                        employees.lockEmployee(internalId).orElseThrow();
                        held.countDown();
                        try {
                            if (!release.await(15, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("Holder was not released");
                            }
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(ex);
                        }
                        var invitation = invitationRepository.findByPublicId(issued.result().invitationId())
                            .orElseThrow();
                        invitation.revoke(Instant.now());
                        invitationRepository.saveAndFlush(invitation);
                        return null;
                    });
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });
            assertTrue(held.await(10, TimeUnit.SECONDS));
            Future<?> worker = pool.submit(() -> {
                SecurityContextHolder.getContext().setAuthentication(member);
                try {
                    enrollmentCommands.activate(member, "act-" + UUID.randomUUID(),
                        issued.result().invitationId(), issued.secret());
                    return null;
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });
            awaitRowLockWait();
            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
            try {
                worker.get(30, TimeUnit.SECONDS);
                throw new AssertionError("Revoked invitation should not activate");
            } catch (java.util.concurrent.ExecutionException ex) {
                assertNoDeadlock(ex);
                assertTrue(ex.getCause() instanceof InvitationConflictException);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private void awaitRowLockWait() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject(
                "select count(*) from pg_stat_activity where datname = current_database() "
                    + "and wait_event_type = 'Lock' and wait_event = 'transactionid'",
                Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
        }
        throw new AssertionError("Activation never waited on the employee lock");
    }

    private static void assertNoDeadlock(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof java.sql.SQLException sql && "40P01".equals(sql.getSQLState())) {
                throw new AssertionError("Deadlock detected where lock order should prevent it");
            }
            if (current.getMessage() != null && current.getMessage().contains("40P01")) {
                throw new AssertionError("Deadlock detected where lock order should prevent it");
            }
            current = current.getCause();
        }
    }

    private static <T> T as(Authentication caller, java.util.function.Supplier<T> action) {        Authentication previous = SecurityContextHolder.getContext().getAuthentication();
        SecurityContextHolder.getContext().setAuthentication(caller);
        try {
            return action.get();
        } finally {
            SecurityContextHolder.getContext().setAuthentication(previous);
        }
    }

    private static <T> T guarded(CountDownLatch ready, CountDownLatch go, Authentication caller,
                                java.util.concurrent.Callable<T> action) throws Exception {        ready.countDown();
        if (!go.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Racers did not start together");
        }
        SecurityContextHolder.getContext().setAuthentication(caller);
        try {
            return action.call();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
