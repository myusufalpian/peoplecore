package id.mydev.peoplecore.infrastructure.profile;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

public class RuntimeEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    public static RuntimeProfileConfig.RuntimeRole role(Environment environment) {
        return switch (environment.getProperty("peoplecore.runtime.role", "api")) {
            case "api" -> RuntimeProfileConfig.RuntimeRole.API;
            case "worker" -> RuntimeProfileConfig.RuntimeRole.WORKER;
            default -> throw new IllegalArgumentException("peoplecore.runtime.role must be api or worker");
        };
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        var runtimeRole = role(environment);
        environment.getPropertySources().addFirst(new MapPropertySource("runtime-role", Map.of(
            "spring.main.web-application-type", runtimeRole == RuntimeProfileConfig.RuntimeRole.WORKER ? "none" : "servlet",
            "spring.flyway.enabled", "false")));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
