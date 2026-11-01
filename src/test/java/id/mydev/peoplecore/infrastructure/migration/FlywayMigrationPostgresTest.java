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
    void upgradePreservesDeliveredProfileSnapshot() {
        try (var fixture = new PostgresFixture()) {
            Flyway.configure().dataSource(fixture.url, fixture.username, fixture.password).schemas(fixture.schema)
                .target("13").load().migrate();
            fixture.jdbc.update("insert into employees(public_id,employee_number,created_at) values(gen_random_uuid(),'PROFILE-UPGRADE',now())");
            fixture.jdbc.update("insert into profile_proposal(public_id,employee_id,state,created_at,updated_at) "
                + "select gen_random_uuid(),id,'DRAFT',now(),now() from employees");
            fixture.jdbc.update("insert into profile_revision(public_id,proposal_id,revision_number,evaluated_at,evaluated_org_unit,route_reviewer_account_public_id,created_at) "
                + "select gen_random_uuid(),id,1,now(),'ENG',gen_random_uuid(),now() from profile_proposal");
            fixture.jdbc.update("insert into profile_revision_change(revision_id,field_name,proposed_value,baseline_version) "
                + "select id,'personalPhone','+6212345678',0 from profile_revision");
            fixture.jdbc.update("update profile_revision set sealed=true");
            fixture.jdbc.update("insert into profile_assignment(public_id,proposal_id,revision_id,assignee_account_public_id,created_at) "
                + "select gen_random_uuid(),proposal_id,id,route_reviewer_account_public_id,now() from profile_revision");
            fixture.jdbc.update("update profile_proposal set state='PENDING',current_revision_id=(select id from profile_revision),"
                + "current_assignment_id=(select id from profile_assignment)");
            fixture.jdbc.update("insert into profile_route_evaluation(proposal_id,revision_id,evaluated_at,org_unit,result,reviewer_account_public_id) "
                + "select proposal_id,id,now(),'ENG','MATCHED',route_reviewer_account_public_id from profile_revision");
            insertReceipt(fixture, "PROFILE_SUBMIT", "profile-upgrade", "{\"status\":\"PENDING\"}");
            var tables = java.util.List.of("employees", "profile_proposal", "profile_revision", "profile_revision_change",
                "profile_assignment", "profile_route_evaluation", "command_receipts");
            var before = new java.util.HashMap<String, String>();
            for (String table : tables) {
                before.put(table, fixture.jdbc.queryForObject("select jsonb_agg(to_jsonb(t) order by id)::text from " + table + " t", String.class));
            }
            fixture.flyway().migrate();
            for (String table : tables) {
                assertEquals(before.get(table), fixture.jdbc.queryForObject(
                    "select jsonb_agg(to_jsonb(t) order by id)::text from " + table + " t", String.class), table);
            }
            assertEquals(0, fixture.flyway().migrate().migrationsExecuted);
        }
    }

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
            assertEquals(13, fixture.flyway().migrate().migrationsExecuted);
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
            assertEquals(14, fixture.flyway().migrate().migrationsExecuted);
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
            assertEquals(14, fixture.flyway().migrate().migrationsExecuted);
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
            assertEquals(19, fixture.flyway().migrate().migrationsExecuted);
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
            assertEquals(20, MigrationRunner.migrate(configuration));
            assertEquals(0, MigrationRunner.migrate(configuration));
            assertThrows(IllegalArgumentException.class, () -> MigrationRunner.migrate(Map.of()));
        }
    }
}
