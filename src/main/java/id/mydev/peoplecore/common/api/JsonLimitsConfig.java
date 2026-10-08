package id.mydev.peoplecore.common.api;

import org.springframework.boot.jackson.autoconfigure.JsonFactoryBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JsonLimitsConfig {
    @Bean
    JsonFactoryBuilderCustomizer jsonReadLimits() {
        return builder -> builder.streamReadConstraints(PayloadLimits.JSON_CONSTRAINTS);
    }
}
