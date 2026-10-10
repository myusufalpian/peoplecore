package id.mydev.peoplecore;

import com.nimbusds.jose.jwk.RSAKey;
import id.mydev.peoplecore.identity.application.service.EnrollmentService;
import id.mydev.peoplecore.identity.domain.model.HrisRole;
import id.mydev.peoplecore.identity.domain.model.RoleAssignment;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.identity.domain.repository.RoleAssignmentRepository;
import id.mydev.peoplecore.identity.domain.repository.UserAccountRepository;
import id.mydev.peoplecore.identity.infrastructure.config.SecurityJwtConfig;
import id.mydev.peoplecore.support.PostgresFixture;
import id.mydev.peoplecore.support.SignedJwtFixture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("postgres")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {
        "peoplecore.runtime.role=api",
        "spring.jpa.hibernate.ddl-auto=validate",
        "peoplecore.organization.allowed-units=ENG,FIN",
        "peoplecore.security.jwt.issuer-uri=https://idp.pgtest",
        "peoplecore.security.jwt.audience=hris-api",
        "peoplecore.security.jwt.jwks-uri=https://idp.pgtest/.well-known/jwks.json"
    })
class IdentityOrganizationApiPostgresTest {

    private static final String ISSUER = "https://idp.pgtest";
    private static final String AUDIENCE = "hris-api";
    private static final RSAKey SIGNING_KEY = SignedJwtFixture.generateKey("pgtest-key");

    private static final PostgresFixture DATABASE = new PostgresFixture();

    static {
        DATABASE.flyway().migrate();
        DATABASE.grantRuntime();
    }

    @TestConfiguration
    static class TestDecoder {
        @Bean
        @Primary
        JwtDecoder testJwtDecoder() {
            NimbusJwtDecoder decoder;
            try {
                decoder = NimbusJwtDecoder.withPublicKey(SIGNING_KEY.toRSAPublicKey()).build();
            } catch (Exception ex) {
                throw new IllegalStateException("Test decoder setup failed", ex);
            }
            decoder.setJwtValidator(SecurityJwtConfig.validators(ISSUER, AUDIENCE));
            return decoder;
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> DATABASE.url);
        properties.add("spring.datasource.username", () -> DATABASE.runtimeUsername);
        properties.add("spring.datasource.password", () -> DATABASE.runtimePassword);
    }

    @Autowired WebApplicationContext context;
    @Autowired UserAccountRepository accounts;
    @Autowired RoleAssignmentRepository roles;
    @Autowired EnrollmentService enrollments;
    @Autowired ObjectMapper mapper;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterAll
    static void closeDatabase() {
        DATABASE.close();
    }

    private String hrToken(String subject) {
        Instant now = Instant.now();
        UserAccount account = accounts.findByOidcIssuerAndOidcSubject(ISSUER, subject)
            .orElseGet(() -> accounts.save(new UserAccount(UUID.randomUUID(), ISSUER, subject, now)));
        if (roles.findByAccountId(account.getId()).isEmpty()) {
            roles.save(new RoleAssignment(account.getId(), HrisRole.HR_ADMIN, null,
                now.minusSeconds(60), now));
            roles.save(new RoleAssignment(account.getId(), HrisRole.SYSTEM_ADMIN, null,
                now.minusSeconds(60), now));
        }
        return SignedJwtFixture.mint(SIGNING_KEY, ISSUER, AUDIENCE, subject, now.plusSeconds(600));
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private UUID createEmployee(String token, String number, String key) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/employees")
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key)
                .content("{\"employeeNumber\":\"" + number + "\"}"))
            .andExpect(status().isCreated())
            .andReturn();
        JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
        return UUID.fromString(body.get("data").get("id").stringValue());
    }

    @Test
    void identicalRetryReturnsSameEmployeeWithoutDuplicate() throws Exception {
        String token = hrToken("hr-retry-" + UUID.randomUUID());
        String number = "EMP-R-" + UUID.randomUUID();
        UUID first = createEmployee(token, number, "retry-key");
        MvcResult replayed = mvc.perform(post("/api/v1/employees")
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "retry-key")
                .content("{\"employeeNumber\":\"" + number + "\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.employeeNumber").value(number))
            .andReturn();
        JsonNode body = mapper.readTree(replayed.getResponse().getContentAsString());
        assertEquals(first, UUID.fromString(body.get("data").get("id").stringValue()));
    }

    @Test
    void issueRetryReturnsMetadataWithoutSecret() throws Exception {
        String token = hrToken("hr-resecret-" + UUID.randomUUID());
        UUID employee = createEmployee(token, "EMP-RS-" + UUID.randomUUID(), "emp-" + UUID.randomUUID());
        String key = "invite-" + UUID.randomUUID();
        String body = "{\"employeeId\":\"" + employee + "\",\"intendedIdentityRef\":\"hrd@example.id\","
            + "\"expectedIssuer\":\"" + ISSUER + "\",\"expectedSubject\":\"member-" + UUID.randomUUID() + "\"}";
        MvcResult first = mvc.perform(post("/api/v1/enrollments/invitations")
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key)
                .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.secret").isNotEmpty())
            .andReturn();
        JsonNode firstData = mapper.readTree(first.getResponse().getContentAsString()).get("data");
        MvcResult replayed = mvc.perform(post("/api/v1/enrollments/invitations")
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key)
                .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.id").value(firstData.get("id").stringValue()))
            .andReturn();
        assertTrue(mapper.readTree(replayed.getResponse().getContentAsString()).get("data").get("secret").isNull());
    }

    @Test
    void changedReasonWithSameKeyConflicts() throws Exception {
        String token = hrToken("hr-conflict-" + UUID.randomUUID());
        UUID employee = createEmployee(token, "EMP-C-" + UUID.randomUUID(), "emp-" + UUID.randomUUID());
        String key = "offboard-" + UUID.randomUUID();
        String offboard = "/api/v1/enrollments/employees/" + employee + "/offboard";
        mvc.perform(post(offboard)
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key)
                .content("{\"accessEndsAt\":\"2026-11-01T00:00:00Z\",\"reason\":\"First\"}"))
            .andExpect(status().isOk());
        mvc.perform(post(offboard)
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key)
                .content("{\"accessEndsAt\":\"2026-11-01T00:00:00Z\",\"reason\":\"Changed\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.key").value("COMMAND_CONFLICT"));
    }

    @Test
    void replayAfterScopeLossIsDenied() throws Exception {
        String subject = "hr-scope-" + UUID.randomUUID();
        String token = hrToken(subject);
        UUID employee = createEmployee(token, "EMP-S-" + UUID.randomUUID(), "emp-" + UUID.randomUUID());
        mvc.perform(post("/api/v1/employees/" + employee + "/assignments")
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "assign-seed")
                .content("{\"orgUnit\":\"ENG\",\"jobLevel\":\"L3\",\"validFrom\":\"2026-01-01T00:00:00Z\"}"))
            .andExpect(status().isCreated());
        String key = "update-" + UUID.randomUUID();
        String newNumber = "EMP-S2-" + UUID.randomUUID();
        mvc.perform(put("/api/v1/employees/" + employee)
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key)
                .content("{\"employeeNumber\":\"" + newNumber + "\"}"))
            .andExpect(status().isOk());
        Instant now = Instant.now();
        UserAccount account = accounts.findByOidcIssuerAndOidcSubject(ISSUER, subject).orElseThrow();
        for (RoleAssignment role : roles.findByAccountId(account.getId())) {
            role.endAt(now);
            roles.save(role);
        }
        roles.save(new RoleAssignment(account.getId(), HrisRole.HR_ADMIN, "FIN",
            now.minusSeconds(10), now));
        mvc.perform(get("/api/v1/employees/" + employee)
                .header(HttpHeaders.AUTHORIZATION, bearer(token)))
            .andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/employees/" + employee)
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key)
                .content("{\"employeeNumber\":\"" + newNumber + "\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.key").value("ACCESS_DENIED"));
    }

    @Test
    void activationReplayAfterRebindIsDenied() throws Exception {
        String hrSubject = "hr-bind-" + UUID.randomUUID();
        String hr = hrToken(hrSubject);
        UUID employee = createEmployee(hr, "EMP-B-" + UUID.randomUUID(), "emp-" + UUID.randomUUID());
        String subject = "member-" + UUID.randomUUID();
        String inviteKey = "invite-" + UUID.randomUUID();
        MvcResult issued = mvc.perform(post("/api/v1/enrollments/invitations")
                .header(HttpHeaders.AUTHORIZATION, bearer(hr))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", inviteKey)
                .content("{\"employeeId\":\"" + employee + "\",\"intendedIdentityRef\":\"hrd@example.id\","
                    + "\"expectedIssuer\":\"" + ISSUER + "\",\"expectedSubject\":\"" + subject + "\"}"))
            .andExpect(status().isCreated())
            .andReturn();
        JsonNode invitation = mapper.readTree(issued.getResponse().getContentAsString()).get("data");
        UUID invitationId = UUID.fromString(invitation.get("id").stringValue());
        String secret = invitation.get("secret").stringValue();
        String member = SignedJwtFixture.mint(SIGNING_KEY, ISSUER, AUDIENCE, subject,
            Instant.now().plusSeconds(600));
        String activationBody = "{\"invitationId\":\"" + invitationId + "\",\"secret\":\"" + secret + "\"}";
        String activationKey = "activate-" + UUID.randomUUID();
        mvc.perform(post("/api/v1/enrollments/activations")
                .header(HttpHeaders.AUTHORIZATION, bearer(member))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", activationKey)
                .content(activationBody))
            .andExpect(status().isOk());
        mvc.perform(post("/api/v1/enrollments/activations")
                .header(HttpHeaders.AUTHORIZATION, bearer(member))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", activationKey)
                .content(activationBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.employeeId").value(employee.toString()));
        String nextSubject = "member-next-" + UUID.randomUUID();
        mvc.perform(post("/api/v1/enrollments/employees/" + employee + "/rebind")
                .header(HttpHeaders.AUTHORIZATION, bearer(hr))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "rebind-" + UUID.randomUUID())
                .content("{\"newIssuer\":\"" + ISSUER + "\",\"newSubject\":\"" + nextSubject + "\",\"reason\":\"Email changed\"}"))
            .andExpect(status().isOk());
        mvc.perform(post("/api/v1/enrollments/activations")
                .header(HttpHeaders.AUTHORIZATION, bearer(member))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", activationKey)
                .content(activationBody))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.key").value("ACCESS_DENIED"));
    }
}
