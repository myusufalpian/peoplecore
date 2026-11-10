package id.mydev.peoplecore.infrastructure.migration;

import id.mydev.peoplecore.support.PostgresFixture;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("postgres")
class RuntimeProvisioningPostgresTest {
    private static final Path PROVISION_SCRIPT = Path.of("local", "postgres", "provision-runtime.sql");
    private static final String PSQL_META_PREFIX = "\\";
    private static final String GEXEC = "\\gexec";
    private static final String RUNTIME_PASSWORD_VARIABLE = ":'runtime_password'";
    private static final String SCRIPT_RUNTIME_ROLE = "peoplecore_runtime";
    private static final String SCRIPT_SCHEMA = "SCHEMA public";
    private static final Pattern PUBLIC_SCHEMA_REFERENCE = Pattern.compile("(?i)\\b(?:schema\\s+public\\b|public\\s*\\.)");

    @Test
    void provisioningRevokesStalePrivilegesBeforeRegranting() throws IOException, SQLException {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            createRuntimeRoleWithStalePrivileges(fixture);
            executeProvisioningScript(fixture);

            assertEquals(false, hasTablePrivilege(fixture, "audit_events", "UPDATE"));
            assertEquals(false, hasTablePrivilege(fixture, "audit_events", "DELETE"));
            assertEquals(false, hasTablePrivilege(fixture, "audit_events", "TRUNCATE"));
            assertEquals(true, hasTablePrivilege(fixture, "audit_events", "SELECT"));
            assertEquals(true, hasTablePrivilege(fixture, "audit_events", "INSERT"));
            assertEquals(false, hasTablePrivilege(fixture, "leave_ledger", "UPDATE"));
            assertEquals(false, hasTablePrivilege(fixture, "leave_ledger", "DELETE"));
            assertEquals(true, hasTablePrivilege(fixture, "leave_ledger", "SELECT"));
            assertEquals(true, hasTablePrivilege(fixture, "leave_ledger", "INSERT"));
            assertEquals(true, hasTablePrivilege(fixture, "employees", "DELETE"));
            assertEquals(true, hasTablePrivilege(fixture, "leave_reservation", "UPDATE"));
            for (String table : List.of("role_assignments", "account_bindings", "employee_assignments",
                    "auth_generation", "enrollment_invitations", "activation_attempts",
                    "activation_budget_slots", "activation_admission_lock")) {
                assertEquals(true, hasTablePrivilege(fixture, table, "SELECT"), table);
            }
            List<String> unreadableTables = fixture.jdbc.queryForList("SELECT tablename FROM pg_tables "
                    + "WHERE schemaname=current_schema() AND tablename <> 'flyway_schema_history' "
                    + "AND NOT has_table_privilege(?,format('%I.%I',schemaname,tablename),'SELECT') ORDER BY tablename",
                String.class, fixture.runtimeUsername);
            assertEquals(List.of(), unreadableTables);
            var runtime = fixture.runtime();
            long account = fixture.jdbc.queryForObject("INSERT INTO user_accounts(public_id,oidc_issuer,oidc_subject,created_at) "
                + "VALUES (gen_random_uuid(),'https://identity.example',gen_random_uuid()::text,now()) RETURNING id", Long.class);
            long role = runtime.queryForObject("INSERT INTO role_assignments(account_id,role,effective_from,created_at) "
                + "VALUES (?,'EMPLOYEE',now(),now()) RETURNING id", Long.class, account);
            runtime.update("UPDATE role_assignments SET effective_to=now() WHERE id=?", role);
            runtime.queryForObject("SELECT id FROM activation_admission_lock WHERE id=1 FOR UPDATE", Integer.class);
            runtime.update("UPDATE auth_generation SET generation=generation WHERE id=1");
            assertThrows(DataAccessException.class, () -> runtime.update("DELETE FROM role_assignments WHERE id=?", role));
            assertEquals(false, fixture.jdbc.queryForObject(
                "select rolsuper from pg_roles where rolname=?", Boolean.class, fixture.runtimeUsername));
        }
    }

    private static void createRuntimeRoleWithStalePrivileges(PostgresFixture fixture) {
        fixture.jdbc.execute("create role " + fixture.runtimeUsername
            + " login nosuperuser nocreatedb nocreaterole noreplication password '"
            + fixture.runtimePassword + "'");
        fixture.jdbc.execute("grant update, delete, truncate on " + fixture.schema + ".audit_events to "
            + fixture.runtimeUsername);
        fixture.jdbc.execute("grant update, delete on " + fixture.schema + ".leave_ledger to "
            + fixture.runtimeUsername);
    }

    private static boolean hasTablePrivilege(PostgresFixture fixture, String table, String privilege) {
        Boolean granted = fixture.jdbc.queryForObject("select has_table_privilege(?,?,?)", Boolean.class,
            fixture.runtimeUsername, fixture.schema + "." + table, privilege);
        return Boolean.TRUE.equals(granted);
    }

    private static void executeProvisioningScript(PostgresFixture fixture) throws IOException, SQLException {
        String script = rewrittenScript(fixture);
        try (Connection connection = DriverManager.getConnection(fixture.url, fixture.username, fixture.password);
             Statement statement = connection.createStatement()) {
            String pending = "";
            for (String rawLine : script.split("\n")) {
                String line = rawLine.strip();
                if (line.isEmpty() || line.startsWith(PSQL_META_PREFIX)) {
                    continue;
                }
                boolean generateAndExecute = line.endsWith(GEXEC);
                if (generateAndExecute) {
                    line = line.substring(0, line.length() - GEXEC.length()).strip();
                }
                pending = pending + ' ' + line;
                if (!generateAndExecute && !line.endsWith(";")) {
                    continue;
                }
                if (generateAndExecute) {
                    executeGeneratedStatements(statement, pending.strip());
                } else {
                    executeStatement(statement, pending.strip());
                }
                pending = "";
            }
            if (!pending.isEmpty()) {
                throw new IllegalStateException("Unterminated provisioning statement: " + pending);
            }
        }
    }

    private static String rewrittenScript(PostgresFixture fixture) throws IOException {
        String script = Files.readString(provisioningScriptPath())
            .replace(RUNTIME_PASSWORD_VARIABLE, quoteLiteral(fixture.runtimePassword))
            .replace(SCRIPT_RUNTIME_ROLE, fixture.runtimeUsername)
            .replace(SCRIPT_SCHEMA, "SCHEMA " + fixture.schema);
        if (PUBLIC_SCHEMA_REFERENCE.matcher(script).find()) {
            throw new IllegalStateException(
                "Provisioning script still references the shared public schema after substitution");
        }
        return script;
    }

    private static Path provisioningScriptPath() {
        String workingDirectory = System.getProperty("user.dir");
        for (Path directory = Path.of(workingDirectory).toAbsolutePath(); directory != null;
             directory = directory.getParent()) {
            Path candidate = directory.resolve(PROVISION_SCRIPT);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Cannot locate " + PROVISION_SCRIPT + " from " + workingDirectory);
    }

    private static void executeStatement(Statement statement, String sql) throws SQLException {
        statement.execute(sql);
    }

    private static void executeGeneratedStatements(Statement statement, String query) throws SQLException {
        List<String> generated = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery(query)) {
            while (rows.next()) {
                generated.add(rows.getString(1));
            }
        }
        for (String sql : generated) {
            executeStatement(statement, sql);
        }
    }

    private static String quoteLiteral(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
