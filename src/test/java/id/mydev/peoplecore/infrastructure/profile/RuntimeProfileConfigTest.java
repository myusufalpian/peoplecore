package id.mydev.peoplecore.infrastructure.profile;

import id.mydev.peoplecore.PeoplecoreApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeProfileConfigTest {
    @Test
    void workerStartsNonWebWithMigrationDisabledDespiteOverrides() {
        var application = new SpringApplication(PeoplecoreApplication.class);
        application.setDefaultProperties(Map.of("peoplecore.runtime.role", "worker", "spring.flyway.enabled", "true",
            "spring.main.web-application-type", "servlet", "spring.datasource.url", "jdbc:h2:mem:worker",
            "spring.datasource.username", "sa", "spring.datasource.password", "", "spring.jpa.hibernate.ddl-auto", "none"));
        try (var context = application.run()) {
            assertFalse(context instanceof WebApplicationContext);
            assertEquals(RuntimeProfileConfig.RuntimeRole.WORKER, context.getBean(RuntimeProfileConfig.RuntimeRole.class));
            assertEquals("false", context.getEnvironment().getProperty("spring.flyway.enabled"));
        }
    }

    @Test
    void apiIsDefaultAndUnknownRoleFailsAtStartup() {
        assertEquals(RuntimeProfileConfig.RuntimeRole.API, RuntimeEnvironmentPostProcessor.role(new MockEnvironment()));
        assertThrows(IllegalArgumentException.class, () -> RuntimeEnvironmentPostProcessor.role(new MockEnvironment().withProperty("peoplecore.runtime.role", "invalid")));
    }
}
