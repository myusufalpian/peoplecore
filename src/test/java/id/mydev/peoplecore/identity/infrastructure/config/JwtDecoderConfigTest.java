package id.mydev.peoplecore.identity.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class JwtDecoderConfigTest {

    private static final AutoConfigurations CONFIGURATIONS = AutoConfigurations.of(
        id.mydev.peoplecore.identity.infrastructure.config.SecurityJwtConfig.class,
        id.mydev.peoplecore.identity.infrastructure.config.IdentityConfig.class);

    @Test
    void decoderRequiresCompleteConfiguration() {
        new ApplicationContextRunner()
            .withConfiguration(CONFIGURATIONS)
            .withPropertyValues(
                "peoplecore.security.jwt.issuer-uri=https://idp.example",
                "peoplecore.security.jwt.jwks-uri=https://idp.example/.well-known/jwks.json")
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void decoderLoadsWithCompleteConfiguration() {
        new ApplicationContextRunner()
            .withConfiguration(CONFIGURATIONS)
            .withPropertyValues(
                "peoplecore.security.jwt.issuer-uri=https://idp.example",
                "peoplecore.security.jwt.audience=hris-api",
                "peoplecore.security.jwt.jwks-uri=https://idp.example/.well-known/jwks.json")
            .run(context -> assertThat(context).hasNotFailed()
                .hasSingleBean(org.springframework.security.oauth2.jwt.JwtDecoder.class));
    }
}
