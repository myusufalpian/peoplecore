package id.mydev.peoplecore.common.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CurrentAccessConfig {
    @Bean
    @ConditionalOnMissingBean(CurrentAccessPolicy.class)
    CurrentAccessPolicy currentAccessPolicy() {
        return new CurrentAccessPolicy() { };
    }
}
