package id.mydev.peoplecore.identity.infrastructure.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import id.mydev.peoplecore.identity.infrastructure.properties.JwtSecurityProperties;
@Configuration
public class SecurityJwtConfig {

    @Bean
    @ConditionalOnProperty("peoplecore.security.jwt.jwks-uri")
    JwtDecoder jwtDecoder(JwtSecurityProperties properties) {
        org.springframework.util.Assert.hasText(properties.issuerUri(),
            "peoplecore.security.jwt.issuer-uri is required when JWT security is enabled");
        org.springframework.util.Assert.hasText(properties.audience(),
            "peoplecore.security.jwt.audience is required when JWT security is enabled");
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwksUri()).build();
        decoder.setJwtValidator(validators(properties.issuerUri(), properties.audience()));
        return decoder;
    }

    public static OAuth2TokenValidator<Jwt> validators(String issuerUri, String audience) {
        org.springframework.util.Assert.hasText(issuerUri, "issuerUri must not be blank");
        org.springframework.util.Assert.hasText(audience, "audience must not be blank");
        List<OAuth2TokenValidator<Jwt>> checks = new ArrayList<>();
        checks.add(JwtValidators.createDefaultWithIssuer(issuerUri));
        checks.add(new JwtClaimValidator<Instant>(JwtClaimNames.EXP,
            expiresAt -> expiresAt != null));
        checks.add(new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
            claimed -> claimed != null && claimed.contains(audience)));
        return new DelegatingOAuth2TokenValidator<>(checks);
    }
}
