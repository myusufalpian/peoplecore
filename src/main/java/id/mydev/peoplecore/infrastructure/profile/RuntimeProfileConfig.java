package id.mydev.peoplecore.infrastructure.profile;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class RuntimeProfileConfig {
    public enum RuntimeRole { API, WORKER }

    @Bean
    RuntimeRole runtimeRole(Environment environment) {
        return RuntimeEnvironmentPostProcessor.role(environment);
    }
}
