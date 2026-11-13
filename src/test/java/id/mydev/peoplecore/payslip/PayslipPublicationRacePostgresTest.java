package id.mydev.peoplecore.payslip;

import id.mydev.peoplecore.identity.domain.model.HrisRole;
import id.mydev.peoplecore.identity.domain.model.RoleAssignment;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.identity.domain.repository.RoleAssignmentRepository;
import id.mydev.peoplecore.identity.domain.repository.UserAccountRepository;
import id.mydev.peoplecore.payslip.application.PayslipCommands;
import id.mydev.peoplecore.payslip.application.PayslipModels.Draft;
import id.mydev.peoplecore.payslip.application.PayslipModels.Item;
import id.mydev.peoplecore.support.JwtAuthFixture;
import id.mydev.peoplecore.support.PostgresFixture;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("postgres")
@Timeout(45)
@SpringBootTest(properties = {"peoplecore.runtime.role=api", "spring.jpa.hibernate.ddl-auto=validate"})
class PayslipPublicationRacePostgresTest {
    private static final String ISSUER = "https://payslip-race.test";
    private static final PostgresFixture DATABASE = new PostgresFixture();
    static {
        DATABASE.flyway().migrate();
        DATABASE.grantRuntime();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DATABASE.url);
        registry.add("spring.datasource.username", () -> DATABASE.runtimeUsername);
        registry.add("spring.datasource.password", () -> DATABASE.runtimePassword);
    }

    @Autowired PayslipCommands commands;
    @Autowired UserAccountRepository accounts;
    @Autowired RoleAssignmentRepository roles;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    private Authentication caller;
    private long employeeId;
    private UUID employeePublicId;

    @BeforeEach
    void prepare() {
        Instant now = Instant.now();
        UserAccount account = accounts.save(new UserAccount(UUID.randomUUID(), ISSUER, UUID.randomUUID().toString(), now));
        roles.save(new RoleAssignment(account.getId(), HrisRole.FINANCE_PAYROLL, null, now.minusSeconds(60), now));
        caller = JwtAuthFixture.token(ISSUER, account.getOidcSubject());
        employeePublicId = UUID.randomUUID();
        employeeId = jdbc.queryForObject("insert into employees(public_id,employee_number,employment_start_date,created_at) "
            + "values(?,?,date '2020-01-01',now()) returning id", Long.class,
            employeePublicId, "PAY-RACE-" + employeePublicId);
    }

    @AfterAll
    static void closeDatabase() {
        DATABASE.close();
    }

    @Test
    void twoReviewedBatchesSerializePublicationAndReplayWithoutDuplicatingIntents() throws Exception {
        race(false);
    }

    @Test
    void failedWaitingReplacementRollsBackItsPublicationReceiptAndIntentAndKeepsWinner() throws Exception {
        race(true);
    }

    private void race(boolean failReplacement) throws Exception {
        ReviewedBatch firstReviewed = reviewedBatch();
        ReviewedBatch secondReviewed = reviewedBatch();
        UUID first = firstReviewed.batchPublicId();
        UUID second = secondReviewed.batchPublicId();
        assertFalse(firstReviewed.versionId() == secondReviewed.versionId());
        String firstKey = key();
        String secondKey = key();
        if (failReplacement) rejectOutboxFor(second);
        CountDownLatch firstPublished = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        AtomicInteger firstPid = new AtomicInteger();
        AtomicInteger secondPid = new AtomicInteger();
        try (var workers = Executors.newFixedThreadPool(2)) {
            var winner = workers.submit(() -> authenticated(() -> new TransactionTemplate(transactionManager).execute(status -> {
                firstPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                var result = commands.publish(caller, firstKey, first, 1);
                firstPublished.countDown();
                await(releaseFirst);
                return result;
            })));
            try {
                assertTrue(firstPublished.await(15, TimeUnit.SECONDS), "First publication must hold its transaction open");
                var replacement = workers.submit(() -> authenticated(() -> {
                    try {
                        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                            secondPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                            secondStarted.countDown();
                            commands.publish(caller, secondKey, second, 1);
                        });
                        return true;
                    } catch (RuntimeException exception) {
                        if (!failReplacement) throw exception;
                        Throwable cause = exception;
                        while (cause.getCause() != null) cause = cause.getCause();
                        assertTrue(cause.getMessage().contains("Replacement notification rejected"),
                            "Replacement must fail at its outbox insert");
                        return false;
                    }
                }));
                assertTrue(secondStarted.await(10, TimeUnit.SECONDS));
                assertBlockedBy(secondPid.get(), firstPid.get());
                assertFalse(replacement.isDone(), "Replacement must wait for the same employee's publication lock");
                releaseFirst.countDown();
                assertEquals("PUBLISHED", winner.get(15, TimeUnit.SECONDS).status());
                assertEquals(!failReplacement, replacement.get(15, TimeUnit.SECONDS));
            } finally {
                releaseFirst.countDown();
            }
        } finally {
            if (failReplacement) {
                DATABASE.jdbc.execute("drop trigger reject_race_outbox on outbox_events");
                DATABASE.jdbc.execute("drop function reject_race_outbox()");
            }
        }
        UUID expectedBatch = failReplacement ? first : second;
        assertEquals(1, jdbc.queryForObject("select count(employee_id) from payslip_current where employee_id=? "
            + "and period_start=date '2026-11-01'", Integer.class, employeeId));
        assertEquals(expectedBatch, jdbc.queryForObject("select b.public_id from payslip_current c "
            + "join payslip_publication p on p.id=c.publication_id join payslip_batch_revision r on r.id=p.revision_id "
            + "join payslip_batch b on b.id=r.batch_id where c.employee_id=? and c.period_start=date '2026-11-01'",
            UUID.class, employeeId));
        assertEquals(failReplacement ? firstReviewed.versionId() : secondReviewed.versionId(),
            jdbc.queryForObject("select i.document_version_id from payslip_current c "
                + "join payslip_publication p on p.id=c.publication_id join payslip_batch_item i on i.id=p.item_id "
                + "where c.employee_id=? and c.period_start=date '2026-11-01'", Long.class, employeeId));
        assertEquals(failReplacement ? 1 : 2, publicationCount());
        assertEquals(failReplacement ? 1 : 2, intentCount());
        assertEquals(failReplacement ? "REVIEWED" : "PUBLISHED", jdbc.queryForObject(
            "select status from payslip_batch where public_id=?", String.class, second));
        assertEquals(failReplacement ? 0 : 1, jdbc.queryForObject("select count(id) from command_receipts "
            + "where command_type='PAYSLIP_BATCH_PUBLISH' and idempotency_key=?", Integer.class, secondKey));
        authenticated(() -> commands.publish(caller, firstKey, first, 1));
        if (!failReplacement) authenticated(() -> commands.publish(caller, secondKey, second, 1));
        assertEquals(failReplacement ? 1 : 2, publicationCount());
        assertEquals(failReplacement ? 1 : 2, intentCount());
    }

    private ReviewedBatch reviewedBatch() {
        var document = PayslipPersistencePostgresTest.verifiedDocument(jdbc, employeeId);
        UUID documentPublicId = jdbc.queryForObject("select public_id from private_document where id=?",
            UUID.class, document.id());
        Long versionId = jdbc.queryForObject("select current_version_id from private_document where id=?",
            Long.class, document.id());
        Draft draft = new Draft(List.of(new Item(employeePublicId, YearMonth.of(2026, 11), documentPublicId)));
        return authenticated(() -> {
            var created = commands.create(caller, key(), draft);
            commands.review(caller, key(), created.batchPublicId(), 1);
            return new ReviewedBatch(created.batchPublicId(), versionId);
        });
    }

    private record ReviewedBatch(UUID batchPublicId, long versionId) { }

    private int publicationCount() {
        return jdbc.queryForObject("select count(id) from payslip_publication where employee_id=? "
            + "and period_start=date '2026-11-01'", Integer.class, employeeId);
    }

    private int intentCount() {
        return jdbc.queryForObject("select count(o.id) from outbox_events o join payslip_publication p "
            + "on p.public_id=o.aggregate_public_id where o.aggregate_type='PAYSLIP_PUBLICATION' "
            + "and o.event_type='PAYSLIP_PUBLISHED' and p.employee_id=? and p.period_start=date '2026-11-01'",
            Integer.class, employeeId);
    }

    private void rejectOutboxFor(UUID batch) {
        DATABASE.jdbc.execute("create function reject_race_outbox() returns trigger language plpgsql as $$ begin "
            + "if NEW.aggregate_type='PAYSLIP_PUBLICATION' and exists (select p.id from payslip_publication p "
            + "join payslip_batch_revision r on r.id=p.revision_id join payslip_batch b on b.id=r.batch_id "
            + "where p.public_id=NEW.aggregate_public_id and b.public_id='" + batch + "'::uuid) then "
            + "raise exception 'Replacement notification rejected'; end if; return NEW; end $$");
        DATABASE.jdbc.execute("create trigger reject_race_outbox before insert on outbox_events "
            + "for each row execute function reject_race_outbox()");
    }

    private void assertBlockedBy(int waiting, int holder) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(DATABASE.jdbc.queryForObject("select ? = any(pg_blocking_pids(?))",
                Boolean.class, holder, waiting))) return;
            Thread.onSpinWait();
        }
        throw new AssertionError("Replacement did not block on the first publication transaction");
    }

    private <T> T authenticated(Supplier<T> action) {
        SecurityContextHolder.getContext().setAuthentication(caller);
        try {
            return action.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) throw new AssertionError("Publication release timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted waiting for publication", exception);
        }
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
