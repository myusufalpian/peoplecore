package id.mydev.peoplecore.approval.infrastructure.migration;

import id.mydev.peoplecore.support.PostgresFixture;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("postgres")
class ProfileApprovalRulesPostgresTest {

    @Test
    void cleanMigrationCreatesBoundedRuleTables() {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            assertEquals(0, fixture.jdbc.queryForObject(
                "select count(*) from profile_rule_sets", Integer.class));
            assertEquals(0, fixture.jdbc.queryForObject(
                "select count(*) from profile_rule_current_activation", Integer.class));

            UUID setId = UUID.randomUUID();
            UUID reviewer = UUID.randomUUID();
            fixture.jdbc.update(
                "insert into profile_rule_sets(public_id,request_type,status,created_at) values(?,?,?,now())",
                setId, "PROFILE_CHANGE", "DRAFT");
            fixture.jdbc.update(
                "insert into profile_rules(rule_set_id,rule_id,priority,org_unit,job_level,reviewer_account_public_id,created_at) "
                    + "select id,'route-eng',10,'ENG','L3',?::uuid,now() from profile_rule_sets where public_id=?::uuid",
                reviewer, setId);
            assertEquals(1, fixture.jdbc.queryForObject(
                "select count(*) from profile_rules where rule_id='route-eng'", Integer.class));

            assertThrows(DataAccessException.class, () -> fixture.jdbc.update(
                "insert into profile_rules(rule_set_id,rule_id,priority,reviewer_account_public_id,created_at) "
                    + "select id,'route-eng',5,?::uuid,now() from profile_rule_sets where public_id=?::uuid",
                reviewer, setId));
            assertThrows(DataAccessException.class, () -> fixture.jdbc.update(
                "insert into profile_rule_sets(public_id,request_type,status,created_at) values(gen_random_uuid(),?,?,now())",
                "LEAVE_REQUEST", "DRAFT"));
            assertThrows(DataAccessException.class, () -> fixture.jdbc.update(
                "insert into profile_rules(rule_set_id,rule_id,priority,reviewer_account_public_id,created_at) "
                    + "select id,'',1,?::uuid,now() from profile_rule_sets where public_id=?::uuid",
                reviewer, setId));
            assertThrows(DataAccessException.class, () -> fixture.jdbc.update(
                "insert into profile_rule_activations(rule_set_id,activated_at,activated_by) "
                    + "select id,now(),'' from profile_rule_sets where public_id=?::uuid", setId));
        }
    }

    @Test
    void publishedContentIsImmutableAndPointerIsSingleton() {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            UUID setId = UUID.randomUUID();
            UUID draftId = UUID.randomUUID();
            UUID reviewer = UUID.randomUUID();
            fixture.jdbc.update(
                "insert into profile_rule_sets(public_id,request_type,status,created_at) "
                    + "values(?,'PROFILE_CHANGE','DRAFT',now())",
                setId);
            fixture.jdbc.update(
                "insert into profile_rule_sets(public_id,request_type,status,created_at) "
                    + "values(?,'PROFILE_CHANGE','DRAFT',now())",
                draftId);
            fixture.jdbc.update(
                "insert into profile_rules(rule_set_id,rule_id,priority,reviewer_account_public_id,created_at) "
                    + "select id,'route-eng',10,?::uuid,now() from profile_rule_sets where public_id=?::uuid",
                reviewer, setId);
            fixture.jdbc.update(
                "update profile_rule_sets set status='PUBLISHED',published_at=now() where public_id=?::uuid",
                setId);
            fixture.jdbc.update(
                "insert into profile_rule_activations(rule_set_id,activated_at,activated_by) "
                    + "select id,now(),'hr-1' from profile_rule_sets where public_id=?::uuid", setId);
            fixture.jdbc.update(
                "insert into profile_rule_current_activation(id,rule_set_id,activated_at,activated_by) "
                    + "select 1,id,now(),'hr-1' from profile_rule_sets where public_id=?::uuid", setId);

            assertThrows(DataAccessException.class, () -> fixture.jdbc.update(
                "insert into profile_rules(rule_set_id,rule_id,priority,reviewer_account_public_id,created_at) "
                    + "select id,'route-new',99,?::uuid,now() from profile_rule_sets where public_id=?::uuid",
                reviewer, setId));
            assertThrows(DataAccessException.class, () -> fixture.jdbc.update(
                "update profile_rules set priority=99 where rule_id='route-eng'"));
            assertThrows(DataAccessException.class, () -> fixture.jdbc.update(
                "delete from profile_rules where rule_id='route-eng'"));
            assertThrows(DataAccessException.class, () -> fixture.jdbc.update(
                "update profile_rules set rule_set_id=(select id from profile_rule_sets where public_id=?::uuid) "
                    + "where rule_id='route-eng'",
                draftId));
            assertThrows(DataAccessException.class, () -> fixture.jdbc.update(
                "update profile_rule_sets set status='DRAFT' where public_id=?::uuid", setId));
            assertThrows(DataAccessException.class, () -> fixture.jdbc.update(
                "insert into profile_rule_current_activation(id,rule_set_id,activated_at,activated_by) "
                    + "select 2,id,now(),'hr-2' from profile_rule_sets where public_id=?::uuid", setId));

            fixture.jdbc.update(
                "insert into profile_rules(rule_set_id,rule_id,priority,reviewer_account_public_id,created_at) "
                    + "select id,'route-draft',10,?::uuid,now() from profile_rule_sets where public_id=?::uuid",
                reviewer, draftId);
            assertThrows(DataAccessException.class, () -> fixture.jdbc.update(
                "update profile_rules set rule_set_id=(select id from profile_rule_sets where public_id=?::uuid) "
                    + "where rule_id='route-draft'",
                setId));

            fixture.jdbc.update(
                "update profile_rule_current_activation set activated_by='hr-2' where id=1");
            assertEquals("hr-2", fixture.jdbc.queryForObject(
                "select activated_by from profile_rule_current_activation where id=1", String.class));
        }
    }

    @Test
    void concurrentPublishSerializesChildInsert() throws Exception {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            UUID setId = UUID.randomUUID();
            UUID reviewer = UUID.randomUUID();
            fixture.jdbc.update(
                "insert into profile_rule_sets(public_id,request_type,status,created_at) "
                    + "values(?,'PROFILE_CHANGE','DRAFT',now())",
                setId);
            var dataSource = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                fixture.url, fixture.username, fixture.password);
            java.sql.Connection publisher = dataSource.getConnection();
            publisher.setAutoCommit(false);
            try {
                try (var lock = publisher.prepareStatement(
                    "select id from profile_rule_sets where public_id=?::uuid for update")) {
                    lock.setObject(1, setId);
                    lock.executeQuery().close();
                }
                java.util.concurrent.ExecutorService pool =
                    java.util.concurrent.Executors.newSingleThreadExecutor();
                try {
                    java.util.concurrent.Future<?> child = pool.submit(() -> fixture.jdbc.update(
                        "insert into profile_rules(rule_set_id,rule_id,priority,"
                            + "reviewer_account_public_id,created_at) "
                            + "select id,'route-late',99,?::uuid,now() from profile_rule_sets "
                            + "where public_id=?::uuid",
                        reviewer, setId));
                    Thread.sleep(500);
                    try (var publish = publisher.prepareStatement(
                        "update profile_rule_sets set status='PUBLISHED',published_at=now() "
                            + "where public_id=?::uuid")) {
                        publish.setObject(1, setId);
                        publish.executeUpdate();
                    }
                    publisher.commit();
                    java.util.concurrent.ExecutionException failure =
                        org.junit.jupiter.api.Assertions.assertThrows(
                            java.util.concurrent.ExecutionException.class, child::get);
                    org.junit.jupiter.api.Assertions.assertTrue(
                        String.valueOf(failure.getCause()).contains("immutable"));
                    assertEquals(0, fixture.jdbc.queryForObject(
                        "select count(*) from profile_rules where rule_id='route-late'", Integer.class));
                } finally {
                    pool.shutdownNow();
                }
            } finally {
                publisher.close();
            }
        }
    }

    @Test
    void draftRulesRemainEditableAndUpgradePreservesExistingData() {
        try (var fixture = new PostgresFixture()) {
            Flyway.configure().dataSource(fixture.url, fixture.username, fixture.password)
                .schemas(fixture.schema).target("9").load().migrate();
            fixture.jdbc.update(
                "insert into employees(public_id,employee_number,created_at) values(gen_random_uuid(),'PRE-RULES',now())");
            assertEquals(4, fixture.flyway().migrate().migrationsExecuted);
            assertEquals(1, fixture.jdbc.queryForObject(
                "select count(*) from employees where employee_number='PRE-RULES'", Integer.class));

            UUID setId = UUID.randomUUID();
            fixture.jdbc.update(
                "insert into profile_rule_sets(public_id,request_type,status,created_at) "
                    + "values(?,'PROFILE_CHANGE','DRAFT',now())",
                setId);
            fixture.jdbc.update(
                "insert into profile_rules(rule_set_id,rule_id,priority,reviewer_account_public_id,created_at) "
                    + "select id,'route-eng',10,gen_random_uuid(),now() from profile_rule_sets where public_id=?::uuid",
                setId);
            fixture.jdbc.update("update profile_rules set priority=20 where rule_id='route-eng'");
            assertEquals(20, fixture.jdbc.queryForObject(
                "select priority from profile_rules where rule_id='route-eng'", Integer.class));
            assertEquals(0, fixture.flyway().migrate().migrationsExecuted);
        }
    }
}
