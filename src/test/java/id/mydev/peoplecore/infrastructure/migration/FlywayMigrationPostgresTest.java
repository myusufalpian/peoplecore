package id.mydev.peoplecore.infrastructure.migration;

import id.mydev.peoplecore.support.PostgresFixture;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("postgres")
class FlywayMigrationPostgresTest {
    @Test
    void upgradesPriorSchemaAndEnforcesAppendOnlyAudit() {
        try (var fixture = new PostgresFixture()) {
            Flyway.configure().dataSource(fixture.url, fixture.username, fixture.password).schemas(fixture.schema)
                .target("1").load().migrate();
            fixture.jdbc.update("insert into audit_events(event_id, actor_id, action, aggregate_type, created_at) values(gen_random_uuid(), 'actor', 'CREATE', 'EMPLOYEE', now())");
            assertEquals(1, fixture.flyway().migrate().migrationsExecuted);
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
            assertEquals(2, MigrationRunner.migrate(configuration));
            assertEquals(0, MigrationRunner.migrate(configuration));
            assertThrows(IllegalArgumentException.class, () -> MigrationRunner.migrate(Map.of()));
        }
    }
}
