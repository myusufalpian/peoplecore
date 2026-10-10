package id.mydev.peoplecore.identity.api.controller;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import id.mydev.peoplecore.identity.domain.model.AccountBinding;
import id.mydev.peoplecore.identity.domain.model.HrisRole;
import id.mydev.peoplecore.identity.domain.model.RoleAssignment;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.identity.domain.repository.AccountBindingRepository;
import id.mydev.peoplecore.identity.domain.repository.RoleAssignmentRepository;
import id.mydev.peoplecore.identity.infrastructure.config.SecurityJwtConfig;
import id.mydev.peoplecore.identity.domain.repository.UserAccountRepository;
import id.mydev.peoplecore.organization.domain.model.Employee;
import id.mydev.peoplecore.organization.domain.repository.EmployeeRepository;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "peoplecore.organization.allowed-units=ENG",
        "peoplecore.security.jwt.issuer-uri=https://idp.test",
        "peoplecore.security.jwt.audience=hris-api",
        "peoplecore.security.jwt.jwks-uri=https://idp.test/.well-known/jwks.json"
    })
class ApiJwtSecurityTest {

    private static final String ISSUER = "https://idp.test";
    private static final String AUDIENCE = "hris-api";

    private static final RSAKey SIGNING_KEY;
    private static final RSAKey FOREIGN_KEY;

    static {
        try {
            SIGNING_KEY = new RSAKeyGenerator(2048).keyID("test-key").generate();
            FOREIGN_KEY = new RSAKeyGenerator(2048).keyID("foreign-key").generate();
        } catch (Exception ex) {
            throw new IllegalStateException("Test key generation failed", ex);
        }
    }

    @TestConfiguration
    static class TestDecoder {
        @Bean
        @Primary
        JwtDecoder testJwtDecoder() throws Exception {
            NimbusJwtDecoder decoder =
                NimbusJwtDecoder.withPublicKey(SIGNING_KEY.toRSAPublicKey()).build();
            decoder.setJwtValidator(SecurityJwtConfig.validators(ISSUER, AUDIENCE));
            return decoder;
        }
    }

    @Autowired WebApplicationContext context;
    @Autowired UserAccountRepository accounts;
    @Autowired RoleAssignmentRepository roles;
    @Autowired AccountBindingRepository bindings;
    @Autowired EmployeeRepository employeeRepository;

    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        if (accounts.findByOidcIssuerAndOidcSubject(ISSUER, "hr-1").isEmpty()) {
            Instant now = Instant.now();
            UserAccount hr = accounts.save(new UserAccount(UUID.randomUUID(), ISSUER, "hr-1", now));
            roles.save(new RoleAssignment(hr.getId(), HrisRole.HR_ADMIN, null, now.minusSeconds(60), now));
            roles.save(new RoleAssignment(hr.getId(), HrisRole.SYSTEM_ADMIN, null, now.minusSeconds(60), now));
            var employee = employeeRepository.save(
                new Employee(UUID.randomUUID(), "EMP-JWT", now));
            employee.linkAccount(hr.getId());
            employeeRepository.save(employee);
            bindings.save(new AccountBinding(employee.getId(), employee.getPublicId(),
                hr.getId(), ISSUER, "hr-1", now));
        }
    }

    private static String token(String issuer, String audience, Instant expiresAt, Instant notBefore,
                                RSAKey key) throws Exception {
        var claims = new JWTClaimsSet.Builder()
            .issuer(issuer)
            .subject("hr-1")
            .expirationTime(Date.from(expiresAt))
            .issueTime(new Date())
            .jwtID(UUID.randomUUID().toString());
        if (audience != null) {
            claims.audience(audience);
        }
        if (notBefore != null) {
            claims.notBeforeTime(Date.from(notBefore));
        }
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(),
            claims.build());
        jwt.sign(new RSASSASigner(key.toRSAPrivateKey()));
        return jwt.serialize();
    }

    private static String validToken() throws Exception {
        return token(ISSUER, AUDIENCE, Instant.now().plusSeconds(300), null, SIGNING_KEY);
    }

    private static String tokenWithoutExpiry() throws Exception {
        var claims = new JWTClaimsSet.Builder()
            .issuer(ISSUER)
            .subject("hr-1")
            .audience(AUDIENCE)
            .issueTime(new Date())
            .jwtID(UUID.randomUUID().toString())
            .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(), claims);
        jwt.sign(new RSASSASigner(SIGNING_KEY.toRSAPrivateKey()));
        return jwt.serialize();
    }

    @Test
    void validBearerTokenReachesProtectedApi() throws Exception {
        mvc.perform(get("/api/v1/me/access").header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("ACTIVE"))
            .andExpect(jsonPath("$.data.roles[0]").value("HR_ADMIN"))
            .andExpect(jsonPath("$.metadata").exists())
            .andExpect(jsonPath("$.success").doesNotExist());
    }

    @Test
    void tokenWithoutExpiryIsRejected() throws Exception {
        mvc.perform(get("/api/v1/me/access").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenWithoutExpiry()))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.key").value("UNAUTHORIZED"));
    }

    @Test
    void unknownSubjectIsForbiddenWithoutDisclosure() throws Exception {
        mvc.perform(get("/api/v1/me/access").header(HttpHeaders.AUTHORIZATION, "Bearer " + strangerToken()))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.key").value("ACCESS_DENIED"));
    }

    private static String strangerToken() throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(ISSUER).subject("stranger")
            .audience(AUDIENCE).expirationTime(Date.from(Instant.now().plusSeconds(300)))
            .issueTime(new Date()).jwtID(UUID.randomUUID().toString()).build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(), claims);
        jwt.sign(new RSASSASigner(SIGNING_KEY.toRSAPrivateKey()));
        return jwt.serialize();
    }

    @Test
    void wrongIssuerIsRejected() throws Exception {
        String token = token("https://idp.other", AUDIENCE, Instant.now().plusSeconds(300), null, SIGNING_KEY);
        mvc.perform(get("/api/v1/me/access").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.key").value("UNAUTHORIZED"))
            .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("Bearer")));
    }

    @Test
    void wrongAudienceIsRejected() throws Exception {
        String token = token(ISSUER, "other-api", Instant.now().plusSeconds(300), null, SIGNING_KEY);
        mvc.perform(get("/api/v1/me/access").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.key").value("UNAUTHORIZED"));
    }

    @Test
    void missingAudienceIsRejected() throws Exception {
        String token = token(ISSUER, null, Instant.now().plusSeconds(300), null, SIGNING_KEY);
        mvc.perform(get("/api/v1/me/access").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.key").value("UNAUTHORIZED"));
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        String token = token(ISSUER, AUDIENCE, Instant.now().minusSeconds(60), null, SIGNING_KEY);
        mvc.perform(get("/api/v1/me/access").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.key").value("UNAUTHORIZED"));
    }

    @Test
    void notYetValidTokenIsRejected() throws Exception {
        String token = token(ISSUER, AUDIENCE, Instant.now().plusSeconds(600),
            Instant.now().plusSeconds(300), SIGNING_KEY);
        mvc.perform(get("/api/v1/me/access").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.key").value("UNAUTHORIZED"));
    }

    @Test
    void foreignSignatureIsRejected() throws Exception {
        String token = token(ISSUER, AUDIENCE, Instant.now().plusSeconds(300), null, FOREIGN_KEY);
        mvc.perform(get("/api/v1/me/access").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.key").value("UNAUTHORIZED"));
    }

    @Test
    void missingTokenIsRejected() throws Exception {
        mvc.perform(get("/api/v1/me/access"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.key").value("UNAUTHORIZED"));
    }
}
