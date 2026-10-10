package id.mydev.peoplecore.identity.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

import id.mydev.peoplecore.identity.infrastructure.properties.JwtSecurityProperties;
@Configuration
@EnableConfigurationProperties(JwtSecurityProperties.class)
public class IdentityConfig {
    @Bean
    Clock hrisClock() {
        return Clock.systemUTC();
    }
}
