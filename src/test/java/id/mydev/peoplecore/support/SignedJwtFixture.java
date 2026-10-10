package id.mydev.peoplecore.support;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;

public final class SignedJwtFixture {
    private SignedJwtFixture() { }

    public static RSAKey generateKey(String keyId) {
        try {
            return new RSAKeyGenerator(2048).keyID(keyId).generate();
        } catch (Exception ex) {
            throw new IllegalStateException("Test key generation failed", ex);
        }
    }

    public static String mint(RSAKey key, String issuer, String audience, String subject, Instant expiresAt) {
        try {
            var claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(subject)
                .audience(audience)
                .expirationTime(Date.from(expiresAt))
                .issueTime(new Date())
                .jwtID(UUID.randomUUID().toString())
                .build();
            SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(key.toRSAPrivateKey()));
            return jwt.serialize();
        } catch (Exception ex) {
            throw new IllegalStateException("Test token minting failed", ex);
        }
    }
}
