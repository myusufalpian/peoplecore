package id.mydev.peoplecore.identity.infrastructure.persistence;

import id.mydev.peoplecore.support.PostgresFixture;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("postgres")
class AuthorizationFencePostgresTest {
    private static final Instant NOW=Instant.parse("2026-11-13T10:00:00Z");

    @Test
    void futureRoleCutoffIsCapturedWithoutWaitingForAnotherMutation() {
        try(var fixture=new PostgresFixture()) {
            fixture.flyway().migrate();
            var jdbc=fixture.jdbc;
            UUID account=UUID.randomUUID();
            Long id=jdbc.queryForObject("INSERT INTO user_accounts(public_id,oidc_issuer,oidc_subject,created_at) VALUES(?,?,?,?) RETURNING id",
                Long.class,account,"https://identity.example",account.toString(),NOW.atOffset(ZoneOffset.UTC));
            Instant boundary=NOW.plusSeconds(60);
            jdbc.update("INSERT INTO role_assignments(account_id,role,effective_from,effective_to,created_at) VALUES(?,'EMPLOYEE',?,?,?)",
                id,NOW.minusSeconds(30).atOffset(ZoneOffset.UTC),boundary.atOffset(ZoneOffset.UTC),NOW.atOffset(ZoneOffset.UTC));
            var repository=new JdbcAuthorizationFenceRepository(jdbc);
            assertEquals(boundary,repository.nextBoundary(NOW));
            assertTrue(repository.nextBoundary(boundary)==null);
            assertThrows(IllegalStateException.class,()->repository.generation(true));
        }
    }

    @Test
    void sharedAuthorizationFenceOrdersFinalizationBeforeConcurrentRoleRemoval() throws Exception {
        try(var fixture=new PostgresFixture(); var threads=Executors.newFixedThreadPool(2)) {
            fixture.flyway().migrate();
            var jdbc=fixture.jdbc;
            UUID account=UUID.randomUUID();
            Long id=jdbc.queryForObject("INSERT INTO user_accounts(public_id,oidc_issuer,oidc_subject,created_at) VALUES(?,?,?,?) RETURNING id",
                Long.class,account,"https://identity.example",account.toString(),NOW.atOffset(ZoneOffset.UTC));
            jdbc.update("INSERT INTO role_assignments(account_id,role,effective_from,created_at) VALUES(?,'EMPLOYEE',?,?)",
                id,NOW.minusSeconds(30).atOffset(ZoneOffset.UTC),NOW.atOffset(ZoneOffset.UTC));
            var repository=new JdbcAuthorizationFenceRepository(jdbc);
            var tx=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
            CountDownLatch fenced=new CountDownLatch(1);
            CountDownLatch release=new CountDownLatch(1);
            CountDownLatch writerStarted=new CountDownLatch(1);
            var finalization=threads.submit(()->tx.execute(status->{
                long generation=repository.generation(true);
                fenced.countDown();
                await(release);
                return generation;
            }));
            assertTrue(fenced.await(2,TimeUnit.SECONDS));
            var removal=threads.submit(()->{
                writerStarted.countDown();
                jdbc.update("UPDATE role_assignments SET effective_to=? WHERE account_id=?",NOW.atOffset(ZoneOffset.UTC),id);
                return true;
            });
            try {
                assertTrue(writerStarted.await(2,TimeUnit.SECONDS));
                assertThrows(TimeoutException.class,()->removal.get(100,TimeUnit.MILLISECONDS));
            } finally { release.countDown(); }
            long before=finalization.get(2,TimeUnit.SECONDS);
            assertTrue(removal.get(2,TimeUnit.SECONDS));
            Long after=tx.execute(status->repository.generation(false));
            assertNotNull(after);
            assertTrue(after>before);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if(!latch.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("Timed out waiting for fence");
        } catch(InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Fence interrupted",exception);
        }
    }
}
