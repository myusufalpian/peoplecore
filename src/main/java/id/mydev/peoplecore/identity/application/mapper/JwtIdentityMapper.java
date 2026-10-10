package id.mydev.peoplecore.identity.application.mapper;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Objects;

public final class JwtIdentityMapper {
    private JwtIdentityMapper() { }

    public record OidcIdentity(String issuer, String subject) {
        public OidcIdentity {
            if (issuer == null || issuer.isBlank() || subject == null || subject.isBlank()) {
                throw new IllegalArgumentException("issuer and subject must not be blank");
            }
        }
    }

    public static OidcIdentity toIdentity(Jwt token) {
        Objects.requireNonNull(token, "token must not be null");
        String issuer = token.getClaimAsString("iss");
        if (issuer == null && token.getIssuer() != null) {
            issuer = token.getIssuer().toString();
        }
        return new OidcIdentity(issuer, token.getSubject());
    }
}
