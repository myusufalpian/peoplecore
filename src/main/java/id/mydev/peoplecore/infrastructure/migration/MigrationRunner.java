package id.mydev.peoplecore.infrastructure.migration;

import org.flywaydb.core.Flyway;
import org.springframework.util.Assert;

import java.util.Map;

public final class MigrationRunner {
    private MigrationRunner() { }

    public static void main(String[] args) {
        migrate(System.getenv());
    }

    public static int migrate(Map<String, String> environment) {
        String url = required(environment, "PEOPLECORE_MIGRATION_DB_URL");
        String username = required(environment, "PEOPLECORE_MIGRATION_DB_USERNAME");
        String password = required(environment, "PEOPLECORE_MIGRATION_DB_PASSWORD");
        return Flyway.configure().dataSource(url, username, password).load().migrate().migrationsExecuted;
    }

    private static String required(Map<String, String> environment, String key) {
        String value = environment.get(key);
        Assert.hasText(value, key + " is required");
        return value;
    }
}
