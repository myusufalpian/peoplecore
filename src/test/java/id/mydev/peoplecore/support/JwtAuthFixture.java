package id.mydev.peoplecore.support;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;

public final class JwtAuthFixture {
    private JwtAuthFixture() { }

    public static JwtAuthenticationToken token(String issuer, String subject) {
        Instant now = Instant.now();
        Jwt jwt = Jwt.withTokenValue("test-token")
            .header("alg", "none")
            .issuer(issuer)
            .subject(subject)
            .issuedAt(now)
            .expiresAt(now.plusSeconds(300))
            .build();
        return new JwtAuthenticationToken(jwt, java.util.List.of());
    }

    public static Authentication named(String issuer, String subject) {
        return token(issuer, subject);
    }
}
