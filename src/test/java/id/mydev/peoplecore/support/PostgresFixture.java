package id.mydev.peoplecore.support;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

public final class PostgresFixture implements AutoCloseable {
    public final String username = System.getenv().getOrDefault("PEOPLECORE_TEST_DB_USERNAME", "peoplecore_test");
    public final String password = System.getenv().getOrDefault("PEOPLECORE_TEST_DB_PASSWORD", "peoplecore_test");
    public final String schema = "test_" + UUID.randomUUID().toString().replace("-", "");
    public final String url;
    public final JdbcTemplate jdbc;
    private final JdbcTemplate admin;
    public final String runtimeUsername = "runtime_" + UUID.randomUUID().toString().replace("-", "");
    public final String runtimePassword = UUID.randomUUID().toString();

    public JdbcTemplate runtime() {
        return new JdbcTemplate(new DriverManagerDataSource(url, runtimeUsername, runtimePassword));
    }

    public void grantRuntime() {
        jdbc.execute("create role " + runtimeUsername + " login nosuperuser nocreatedb nocreaterole noreplication password '" + runtimePassword + "'");
        jdbc.execute("grant usage on schema " + schema + " to " + runtimeUsername);
        jdbc.execute("grant select, insert, update, delete on all tables in schema " + schema + " to " + runtimeUsername);
        jdbc.execute("revoke update, delete, truncate on audit_events from " + runtimeUsername);
        jdbc.execute("grant usage, select on all sequences in schema " + schema + " to " + runtimeUsername);
    }

    public PostgresFixture() {
        String baseUrl = System.getenv().getOrDefault("PEOPLECORE_TEST_DB_URL", "jdbc:postgresql://localhost:54339/peoplecore_tests");
        var source = new DriverManagerDataSource(baseUrl, username, password);
        admin = new JdbcTemplate(source);
        String database = admin.queryForObject("select current_database()", String.class);
        if (!"peoplecore_tests".equals(database)) {
            throw new IllegalArgumentException("Integration tests require the isolated peoplecore_tests database");
        }
        admin.execute("create schema " + schema);
        url = baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, username, password));
    }

    public Flyway flyway() {
        return Flyway.configure().dataSource(url, username, password).schemas(schema).load();
    }

    @Override
    public void close() {
        admin.execute("drop schema " + schema + " cascade");
        admin.execute("drop role if exists " + runtimeUsername);
    }
}
