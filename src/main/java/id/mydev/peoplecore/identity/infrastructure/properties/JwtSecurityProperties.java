package id.mydev.peoplecore.identity.infrastructure.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "peoplecore.security.jwt")
public record JwtSecurityProperties(String issuerUri, String audience, String jwksUri) { }
