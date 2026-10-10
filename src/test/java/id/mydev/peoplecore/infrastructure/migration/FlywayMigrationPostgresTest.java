package id.mydev.peoplecore.infrastructure.migration;

import id.mydev.peoplecore.support.PostgresFixture;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("postgres")
class FlywayMigrationPostgresTest {
    @Test
    void intervalUpgradeRejectsInvalidLegacyRowsWithoutRewritingThem() {
        try (var fixture = new PostgresFixture()) {
            Flyway.configure().dataSource(fixture.url, fixture.username, fixture.password).schemas(fixture.schema)
                .target("8").load().migrate();
            fixture.jdbc.update("insert into employees(public_id,employee_number,created_at) values(gen_random_uuid(),'INVALID-INTERVAL',now())");
            fixture.jdbc.update("insert into employee_assignments(public_id,employee_id,org_unit,job_level,valid_from,valid_to,created_at) select gen_random_uuid(),id,'ENG','L3','2026-01-01','2026-01-01',now() from employees");
            assertThrows(FlywayException.class, () -> fixture.flyway().migrate());
            assertEquals(1, fixture.jdbc.queryForObject("select count(*) from employee_assignments where valid_from=valid_to", Integer.class));
        }
    }

    @Test
    void intervalConstraintRejectsCollapsedAndReversedRows() {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            fixture.jdbc.update("insert into employees(public_id,employee_number,created_at) values(gen_random_uuid(),'VALID-INTERVAL',now())");
            assertThrows(DataAccessException.class, () -> fixture.jdbc.update("insert into employee_assignments(public_id,employee_id,org_unit,job_level,valid_from,valid_to,created_at) select gen_random_uuid(),id,'ENG','L3','2026-01-01','2026-01-01',now() from employees"));
            assertThrows(DataAccessException.class, () -> fixture.jdbc.update("insert into employee_assignments(public_id,employee_id,org_unit,job_level,valid_from,valid_to,created_at) select gen_random_uuid(),id,'ENG','L3','2026-01-02','2026-01-01',now() from employees"));
        }
    }

    @Test
    void historyUpgradePreservesUnknownLegacyDatesAndEnforcesDateOrder() {
        try (var fixture = new PostgresFixture()) {
            Flyway.configure().dataSource(fixture.url, fixture.username, fixture.password).schemas(fixture.schema)
                .target("7").load().migrate();
            fixture.jdbc.update("insert into employees(public_id,employee_number,created_at) values(gen_random_uuid(),'PRE-HISTORY',now())");
            fixture.jdbc.update("insert into enrollment_invitations(public_id,employee_id,intended_identity_ref,secret_hash,status,expires_at,issued_at,expected_issuer,expected_subject) select gen_random_uuid(),id,'IDV-1',repeat('a',64),'REVOKED',now(),now(),'https://idp.example','member' from employees");
            assertEquals(2, fixture.flyway().migrate().migrationsExecuted);
            assertEquals(true, fixture.jdbc.queryForObject("select employment_start_date is null and employment_end_date is null from employees", Boolean.class));
            assertEquals(true, fixture.jdbc.queryForObject("select revoked_at is null from enrollment_invitations", Boolean.class));
            assertThrows(DataAccessException.class, () -> fixture.jdbc.update("update employees set employment_start_date='2026-01-02',employment_end_date='2026-01-01'"));
            assertThrows(DataAccessException.class, () -> fixture.jdbc.update("update employees set employment_end_date='2026-01-01'"));
            assertEquals(0, fixture.flyway().migrate().migrationsExecuted);
        }
    }

    @Test
    void upgradeRedactsLegacySecretsAndRevokesOutstandingInvitations() {
        try (var fixture = new PostgresFixture()) {
            Flyway.configure().dataSource(fixture.url, fixture.username, fixture.password).schemas(fixture.schema)
                .target("6").load().migrate();
            fixture.jdbc.update("insert into employees(public_id,employee_number,created_at) values(gen_random_uuid(),'LEGACY',now())");
            fixture.jdbc.update("insert into enrollment_invitations(public_id,employee_id,intended_identity_ref,secret_hash,status,expires_at,issued_at,expected_issuer,expected_subject) select gen_random_uuid(),id,'IDV-1',repeat('a',64),?,now()+interval '1 day',now(),'https://idp.example','member' from employees", "ISSUED");
            fixture.jdbc.update("insert into enrollment_invitations(public_id,employee_id,intended_identity_ref,secret_hash,status,expires_at,issued_at,expected_issuer,expected_subject) select gen_random_uuid(),id,'IDV-2',repeat('b',64),?,now()+interval '1 day',now(),'https://idp.example','member2' from employees", "CONSUMED");
            insertReceipt(fixture, "INVITATION_ISSUE", "legacy", "{\"invitationId\":\"legacy-id\",\"secret\":\"legacy-test-value\",\"status\":\"ISSUED\"}");
            insertReceipt(fixture, "INVITATION_REISSUE", "malformed", "invalid legacy-test-value");
            insertReceipt(fixture, "INVITATION_ISSUE", "non-object", "[\"legacy-test-value\"]");
            insertReceipt(fixture, "INVITATION_REISSUE", "json-null", "null");
            insertReceipt(fixture, "INVITATION_ISSUE", "current", "{\"invitationId\":\"current-id\",\"status\":\"ISSUED\"}");
            insertReceipt(fixture, "EMPLOYEE_CREATE", "other", "{\"employeeId\":\"unchanged\"}");
            assertEquals(3, fixture.flyway().migrate().migrationsExecuted);
            assertEquals(false, fixture.jdbc.queryForObject("select jsonb_exists(result_payload::jsonb, 'secret') from command_receipts where idempotency_key='legacy'", Boolean.class));
            assertEquals("legacy-id", fixture.jdbc.queryForObject("select result_payload::jsonb->>'invitationId' from command_receipts where idempotency_key='legacy'", String.class));
            assertEquals(3, fixture.jdbc.queryForObject("select count(*) from command_receipts where result_payload is null", Integer.class));
            assertEquals(6, fixture.jdbc.queryForObject("select count(*) from command_receipts where request_hash=repeat('a',64) and result_status='SUCCESS'", Integer.class));
            assertEquals(0, fixture.jdbc.queryForObject("select count(*) from enrollment_invitations where status='ISSUED'", Integer.class));
            assertEquals(1, fixture.jdbc.queryForObject("select count(*) from enrollment_invitations where status='CONSUMED'", Integer.class));
            assertEquals(1L, fixture.jdbc.queryForObject("select generation from auth_generation", Long.class));
            assertEquals(0, fixture.flyway().migrate().migrationsExecuted);
        }
    }

    @Test
    void metadataOnlyUpgradeDoesNotRevokeCurrentInvitations() {
        try (var fixture = new PostgresFixture()) {
            Flyway.configure().dataSource(fixture.url, fixture.username, fixture.password).schemas(fixture.schema)
                .target("6").load().migrate();
            insertReceipt(fixture, "INVITATION_ISSUE", "current", "{\"invitationId\":\"current-id\"}");
            fixture.jdbc.update("insert into employees(public_id,employee_number,created_at) values(gen_random_uuid(),'CURRENT',now())");
            fixture.jdbc.update("insert into enrollment_invitations(public_id,employee_id,intended_identity_ref,secret_hash,status,expires_at,issued_at,expected_issuer,expected_subject) select gen_random_uuid(),id,'IDV-1',repeat('a',64),'ISSUED',now()+interval '1 day',now(),'https://idp.example','member' from employees");
            assertEquals(3, fixture.flyway().migrate().migrationsExecuted);
            assertEquals("{\"invitationId\":\"current-id\"}", fixture.jdbc.queryForObject("select result_payload from command_receipts", String.class));
            assertEquals(0L, fixture.jdbc.queryForObject("select generation from auth_generation", Long.class));
            assertEquals(1, fixture.jdbc.queryForObject("select count(*) from enrollment_invitations where status='ISSUED'", Integer.class));
        }
    }

    private static void insertReceipt(PostgresFixture fixture, String type, String key, String payload) {
        fixture.jdbc.update("insert into command_receipts(actor_id,command_type,idempotency_key,request_hash,result_status,result_payload,created_at,expires_at) values('actor',?,?,repeat('a',64),'SUCCESS',?,now(),now()+interval '7 days')", type, key, payload);
    }

    @Test
    void upgradesPriorSchemaAndEnforcesAppendOnlyAudit() {
        try (var fixture = new PostgresFixture()) {
            Flyway.configure().dataSource(fixture.url, fixture.username, fixture.password).schemas(fixture.schema)
                .target("1").load().migrate();
            fixture.jdbc.update("insert into audit_events(event_id, actor_id, action, aggregate_type, created_at) values(gen_random_uuid(), 'actor', 'CREATE', 'EMPLOYEE', now())");
            assertEquals(8, fixture.flyway().migrate().migrationsExecuted);
            assertEquals(1, fixture.jdbc.queryForObject("select count(*) from audit_events", Integer.class));
            assertThrows(DataAccessException.class, () -> fixture.jdbc.update("update audit_events set action='EDIT'"));
            assertThrows(DataAccessException.class, () -> fixture.jdbc.update("delete from audit_events"));
            assertThrows(DataAccessException.class, () -> fixture.jdbc.execute("truncate audit_events"));
            assertEquals(0, fixture.flyway().migrate().migrationsExecuted);
        }
    }

    @Test
    void runtimeCannotBypassAuditProtection() throws Exception {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            fixture.grantRuntime();
            assertThrows(IllegalStateException.class, () -> new RuntimeDatabaseGuard(
                new org.springframework.jdbc.datasource.DriverManagerDataSource(fixture.url, fixture.username, fixture.password)).afterPropertiesSet());
            new RuntimeDatabaseGuard(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                fixture.url, fixture.runtimeUsername, fixture.runtimePassword)).afterPropertiesSet();
            var runtime = fixture.runtime();
            assertEquals(false, runtime.queryForObject("select rolsuper from pg_roles where rolname=current_user", Boolean.class));
            runtime.update("insert into audit_events(event_id,actor_id,action,aggregate_type,created_at) values(gen_random_uuid(),'actor','CREATE','EMPLOYEE',now())");
            assertThrows(DataAccessException.class, () -> runtime.execute("update audit_events set action='EDIT'"));
            assertThrows(DataAccessException.class, () -> runtime.execute("delete from audit_events"));
            assertThrows(DataAccessException.class, () -> runtime.execute("truncate audit_events"));
            assertThrows(DataAccessException.class, () -> runtime.execute("alter table audit_events disable trigger audit_events_append_only"));
            assertThrows(DataAccessException.class, () -> runtime.execute("set session_replication_role=replica"));
            assertEquals(1, runtime.queryForObject("select count(*) from audit_events", Integer.class));
        }
    }

    @Test
    void explicitMigrationRunnerMigratesCleanSchema() {
        try (var fixture = new PostgresFixture()) {
            var configuration = Map.of("PEOPLECORE_MIGRATION_DB_URL", fixture.url,
                "PEOPLECORE_MIGRATION_DB_USERNAME", fixture.username, "PEOPLECORE_MIGRATION_DB_PASSWORD", fixture.password);
            assertEquals(9, MigrationRunner.migrate(configuration));
            assertEquals(0, MigrationRunner.migrate(configuration));
            assertThrows(IllegalArgumentException.class, () -> MigrationRunner.migrate(Map.of()));
        }
    }
}
