package id.mydev.peoplecore.common.command;

import id.mydev.peoplecore.common.audit.AuditQueryService;
import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.concurrent.ExecutionException;
import id.mydev.peoplecore.common.security.CurrentAccessPolicy;
import id.mydev.peoplecore.support.PostgresFixture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("postgres")
@SpringBootTest(properties = {"peoplecore.runtime.role=worker", "peoplecore.command.lock-timeout-ms=5000", "spring.jpa.hibernate.ddl-auto=validate"})
@Import(CommandExecutionServiceTest.PolicyFixture.class)
class CommandExecutionServiceTest {
    private static final PostgresFixture DATABASE = new PostgresFixture();
    private static final AtomicBoolean REPLAY_ALLOWED = new AtomicBoolean(true);
    private static final UUID ALLOWED = UUID.randomUUID();
    static {
        DATABASE.flyway().migrate();
        DATABASE.jdbc.execute("create table domain_probe (command_key varchar(128) primary key)");
        DATABASE.grantRuntime();
    }
    record Result(UUID id, String status) { }

    @TestConfiguration
    static class PolicyFixture {
        @Bean
        @Primary
        CurrentAccessPolicy fixturePolicy() {
            return new CurrentAccessPolicy() {
                @Override
                public void checkCommand(Authentication caller, CommandExecutionService.CommandContext command) {
                    if (!caller.getName().equals("actor")) {
                        throw new AccessDeniedException("Denied by fixture");
                    }
                }
                @Override
                public void checkReplay(Authentication caller, CommandExecutionService.CommandContext command, String recordedResult) {
                    checkCommand(caller, command);
                    if (!REPLAY_ALLOWED.get()) {
                        throw new AccessDeniedException("Replay scope removed by fixture");
                    }
                }
                @Override
                public Set<String> auditResources(Authentication caller) {
                    return Set.of("EMPLOYEE:" + ALLOWED);
                }
            };
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> DATABASE.url);
        properties.add("spring.datasource.username", () -> DATABASE.runtimeUsername);
        properties.add("spring.datasource.password", () -> DATABASE.runtimePassword);
    }

    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired CommandExecutionService service;
    @Autowired CommandExecutionCoordinator coordinator;
    @Autowired AuditQueryService audit;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired tools.jackson.databind.ObjectMapper mapper;

    @AfterEach
    void clearCaller() { SecurityContextHolder.clearContext(); }
    @AfterAll
    static void closeDatabase() { DATABASE.close(); }

    private static void authenticate(String actor) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(actor, "", java.util.List.of()));
    }
    private CommandExecutionService.CommandContext context(String key, String payload) {
        return new CommandExecutionService.CommandContext("actor", "CREATE", key, payload, null);
    }
    private CommandExecutionService.CommandExecutionPlan<Result> plan(String key, UUID id, Supplier<Result> action) {
        return new CommandExecutionService.CommandExecutionPlan<>(action, Result.class,
            new CommandExecutionService.AuditDescriptor("CREATE", "EMPLOYEE", null, id, "correlation", "Sensitive reason", "Sensitive details"),
            new CommandExecutionService.OutboxDescriptor("CREATED", 1, "EMPLOYEE", null, id, "{}"));
    }
    private Result insert(String key, UUID id) {
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
        jdbc.update("insert into domain_probe(command_key) values (?)", key);
        return new Result(id, "CREATED");
    }

    @Test
    void atomicCommitAndTypedReplayWithCanonicalPayload() {
        authenticate("actor");
        String key = UUID.randomUUID().toString();
        UUID id = UUID.randomUUID();
        var command = plan(key, id, () -> insert(key, id));
        Result expected = service.executeCommand(context(key, "{\"b\":2,\"a\":1}"), command);
        assertEquals(expected, service.executeCommand(context(key, "{\"a\":1, \"b\":2}"), command));
        assertEquals(1, jdbc.queryForObject("select count(*) from command_receipts where idempotency_key=?", Integer.class, key));
        assertEquals(1, jdbc.queryForObject("select count(*) from audit_events where aggregate_public_id=?", Integer.class, id));
        assertEquals(1, jdbc.queryForObject("select count(*) from outbox_events where aggregate_public_id=?", Integer.class, id));
        assertThrows(CommandConflictException.class, () -> service.executeCommand(context(key, "{\"a\":3}"), command));
        authenticate("other");
        assertThrows(AccessDeniedException.class, () -> service.executeCommand(context(key, "{}"), command));
    }

    @Test
    void differentPreciseDecimalPayloadConflictsWithoutRepeatingAction() {
        authenticate("actor");
        String key = UUID.randomUUID().toString();
        UUID id = UUID.randomUUID();
        var command = plan(key, id, () -> insert(key, id));
        service.executeCommand(context(key, "{\"amount\":0.10000000000000001}"), command);
        assertThrows(CommandConflictException.class, () ->
            service.executeCommand(context(key, "{\"amount\":0.10000000000000000}"), command));
        assertEquals(1, jdbc.queryForObject("select count(*) from domain_probe where command_key=?", Integer.class, key));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "7s"})
    void restoresPreviousLockTimeoutBeforeDomainAction(String previousTimeout) {
        authenticate("actor");
        String key = UUID.randomUUID().toString();
        UUID id = UUID.randomUUID();
        new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.queryForObject("select set_config('lock_timeout', ?, true)", String.class, previousTimeout);
            service.executeCommand(context(key, "{}"), plan(key, id, () -> {
                assertEquals(previousTimeout, jdbc.queryForObject("show lock_timeout", String.class));
                return insert(key, id);
            }));
            assertEquals(previousTimeout, jdbc.queryForObject("show lock_timeout", String.class));
            return null;
        });
    }

    @Test
    void rollbackAfterAuditWriteLeavesNoDomainReceiptAuditOrOutbox() {
        authenticate("actor");
        String key = UUID.randomUUID().toString();
        UUID id = UUID.randomUUID();
        var bad = new CommandExecutionService.CommandExecutionPlan<>(() -> insert(key, id), Result.class,
            new CommandExecutionService.AuditDescriptor("CREATE", "EMPLOYEE", null, id, null, null, null),
            new CommandExecutionService.OutboxDescriptor(null, 1, "EMPLOYEE", null, id, "{}"));
        assertThrows(NullPointerException.class, () -> service.executeCommand(context(key, "{}"), bad));
        assertEquals(0, jdbc.queryForObject("select count(*) from domain_probe where command_key=?", Integer.class, key));
        assertEquals(0, jdbc.queryForObject("select count(*) from command_receipts where idempotency_key=?", Integer.class, key));
        assertEquals(0, jdbc.queryForObject("select count(*) from audit_events where aggregate_public_id=?", Integer.class, id));
        assertEquals(0, jdbc.queryForObject("select count(*) from outbox_events where aggregate_public_id=?", Integer.class, id));
        assertEquals(new Result(id, "CREATED"), service.executeCommand(context(key, "{}"), plan(key, id, () -> insert(key, id))));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void competingCommandsWaitForWinnerAndNeverRepeatDomainAction(boolean changedPayload) throws Exception {
        String key = UUID.randomUUID().toString();
        UUID id = UUID.randomUUID();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var actions = new AtomicInteger();
        Supplier<Result> action = () -> {
            actions.incrementAndGet();
            entered.countDown();
            await(release);
            return insert(key, id);
        };
        try (var threads = Executors.newFixedThreadPool(2)) {
            var first = threads.submit(() -> { authenticate("actor"); return service.executeCommand(context(key, "{}"), plan(key, id, action)); });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var second = threads.submit(() -> { authenticate("actor"); return service.executeCommand(context(key, changedPayload ? "{\"changed\":true}" : "{}"), plan(key, id, action)); });
            awaitAdvisoryWait();
            release.countDown();
            Result expected = first.get(5, TimeUnit.SECONDS);
            if (changedPayload) {
                var failure = assertThrows(ExecutionException.class, () -> second.get(5, TimeUnit.SECONDS));
                assertTrue(failure.getCause() instanceof CommandConflictException);
            } else {
                assertEquals(expected, second.get(5, TimeUnit.SECONDS));
            }
            assertEquals(1, actions.get());
        } finally { release.countDown(); }
    }

    @Test
    void lockTimeoutIsTransientAndDoesNotPersistAReceipt() throws Exception {
        String key = UUID.randomUUID().toString();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var tx = new TransactionTemplate(transactionManager);
        try (var thread = Executors.newSingleThreadExecutor()) {
            var holder = thread.submit(() -> tx.execute(status -> {
                coordinator.lock("actor", "CREATE", key);
                entered.countDown();
                await(release);
                return null;
            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            authenticate("actor");
            var shortWait = new CommandExecutionCoordinator(jdbc, mapper, 100);
            assertThrows(CommandBusyException.class, () -> tx.execute(status -> {
                shortWait.lock("actor", "CREATE", key);
                return null;
            }));
            assertEquals(0, jdbc.queryForObject("select count(*) from command_receipts where idempotency_key=?", Integer.class, key));
            release.countDown();
            holder.get(5, TimeUnit.SECONDS);
        } finally { release.countDown(); }
    }

    @Test
    void rejectsIncompleteLegacyReceiptAndNonVoidNullResult() {
        authenticate("actor");
        String key = UUID.randomUUID().toString();
        jdbc.update("insert into command_receipts(actor_id,command_type,idempotency_key,request_hash,result_status,created_at,expires_at) values('actor','CREATE',?,?,'PROCESSING',now(),now())", key, coordinator.requestHash("{}"));
        var executed = new AtomicBoolean();
        assertThrows(CommandBusyException.class, () -> service.executeCommand(context(key, "{}"),
            new CommandExecutionService.CommandExecutionPlan<>(() -> { executed.set(true); return null; }, Void.class, null, null)));
        assertEquals(false, executed.get());
        assertThrows(IllegalStateException.class, () -> service.executeCommand(context(UUID.randomUUID().toString(), "{}"),
            new CommandExecutionService.CommandExecutionPlan<>(() -> null, Result.class, null, null)));
        String voidKey = UUID.randomUUID().toString();
        var voidPlan = new CommandExecutionService.CommandExecutionPlan<>(() -> null, Void.class, null, null);
        assertEquals(null, service.executeCommand(context(voidKey, "{}"), voidPlan));
        assertEquals(null, service.executeCommand(context(voidKey, "{}"), voidPlan));
    }

    @Test
    void expiredReceiptsStillReplayAndRevokedScopeCannotReadRecordedResult() {
        authenticate("actor");
        String key = UUID.randomUUID().toString();
        UUID id = UUID.randomUUID();
        var command = plan(key, id, () -> insert(key, id));
        Result expected = service.executeCommand(context(key, "{}"), command);
        jdbc.update("update command_receipts set expires_at=now()-interval '1 day' where idempotency_key=?", key);
        assertEquals(expected, service.executeCommand(context(key, "{}"), command));
        REPLAY_ALLOWED.set(false);
        try {
            assertThrows(AccessDeniedException.class, () -> service.executeCommand(context(key, "{}"), command));
        } finally { REPLAY_ALLOWED.set(true); }
        assertEquals(1, jdbc.queryForObject("select count(*) from domain_probe where command_key=?", Integer.class, key));
    }

    @Test
    void rollbackAfterOutboxWriteRemovesAllFourWrites() {
        authenticate("actor");
        String key = UUID.randomUUID().toString();
        UUID id = UUID.randomUUID();
        var tx = new TransactionTemplate(transactionManager);
        assertThrows(IllegalStateException.class, () -> tx.execute(status -> {
            service.executeCommand(context(key, "{}"), plan(key, id, () -> insert(key, id)));
            assertEquals(1, jdbc.queryForObject("select count(*) from outbox_events where aggregate_public_id=?", Integer.class, id));
            throw new IllegalStateException("Injected failure after outbox write");
        }));
        assertEquals(0, jdbc.queryForObject("select count(*) from domain_probe where command_key=?", Integer.class, key));
        assertEquals(0, jdbc.queryForObject("select count(*) from command_receipts where idempotency_key=?", Integer.class, key));
        assertEquals(0, jdbc.queryForObject("select count(*) from audit_events where aggregate_public_id=?", Integer.class, id));
        assertEquals(0, jdbc.queryForObject("select count(*) from outbox_events where aggregate_public_id=?", Integer.class, id));
    }

    @Test
    void actorSearchFiltersEveryRowByObjectScope() {
        authenticate("actor");
        UUID denied = UUID.randomUUID();
        jdbc.update("insert into audit_events(event_id,actor_id,action,aggregate_type,aggregate_public_id,created_at) values(gen_random_uuid(),'actor','CREATE','EMPLOYEE',?,now())", ALLOWED);
        jdbc.update("insert into audit_events(event_id,actor_id,action,aggregate_type,aggregate_public_id,created_at) values(gen_random_uuid(),'actor','CREATE','EMPLOYEE',?,now())", denied);
        var rows = audit.findByActor("actor", 0, 10);
        assertEquals(1, rows.getTotalElements());
        assertEquals(ALLOWED, rows.getContent().getFirst().aggregatePublicId());
        assertThrows(ResourceNotFoundException.class, () -> audit.findByAggregate("EMPLOYEE", denied, 0, 10));
    }

    private void awaitAdvisoryWait() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (jdbc.queryForObject("select count(*) from pg_stat_activity where datname=current_database() and wait_event='advisory'", Integer.class) > 0) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Competing command never waited on the advisory lock");
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(15, TimeUnit.SECONDS)); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
    }
}
