package id.mydev.peoplecore.identity.api.controller;

import id.mydev.peoplecore.identity.api.dto.EnrollmentDto;
import id.mydev.peoplecore.identity.application.command.EnrollmentCommands;
import id.mydev.peoplecore.identity.application.command.EnrollmentResults.BindingResult;
import id.mydev.peoplecore.identity.application.command.EnrollmentResults.IssuedInvitation;
import id.mydev.peoplecore.identity.domain.exception.ActivationThrottledException;
import id.mydev.peoplecore.identity.application.command.EnrollmentResults.InvitationResult;
import id.mydev.peoplecore.identity.application.command.EnrollmentResults.OffboardResult;
import id.mydev.peoplecore.identity.application.command.EnrollmentResults.RebindResult;
import id.mydev.peoplecore.identity.application.command.EnrollmentResults.RehireResult;
import id.mydev.peoplecore.identity.application.command.EnrollmentResults.RestoreResult;
import id.mydev.peoplecore.identity.domain.exception.BindingConflictException;
import id.mydev.peoplecore.identity.domain.exception.InvalidRoleException;
import id.mydev.peoplecore.identity.domain.exception.InvitationConflictException;
import id.mydev.peoplecore.identity.domain.exception.InvitationExpiredException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class EnrollmentControllerTest {

    private static final Instant NOW = Instant.parse("2026-10-08T10:00:00Z");
    private static final UUID EMPLOYEE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID INVITATION_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    static class FakeCommands extends EnrollmentCommands {
        FakeCommands() {
            super(null, null, null, null);
        }

        private static void allow(Authentication caller) {
            if (!"hr".equals(caller.getName())) {
                throw new AccessDeniedException("Denied by fake");
            }
        }

        @Override
        public IssuedInvitation issue(Authentication caller, String key, UUID employeeId,
                                      String intendedIdentityRef, String expectedIssuer, String expectedSubject) {
            allow(caller);
            if (EMPLOYEE_ID.equals(employeeId)) {
                return new IssuedInvitation(new InvitationResult(INVITATION_ID, employeeId, intendedIdentityRef,
                    "ISSUED", "PENDING", NOW.plus(Duration.ofDays(7))), "raw-secret");
            }
            throw new InvitationConflictException("No invitation by fake");
        }

        @Override
        public IssuedInvitation reissue(Authentication caller, String key, UUID employeeId,
                                        String intendedIdentityRef, String expectedIssuer, String expectedSubject) {
            return issue(caller, key, employeeId, intendedIdentityRef, expectedIssuer, expectedSubject);
        }

        @Override
        public BindingResult activate(Authentication caller, String key, UUID invitationId, String secret) {
            if (INVITATION_ID.equals(invitationId) && "raw-secret".equals(secret)) {
                return new BindingResult(EMPLOYEE_ID, invitationId, "https://idp.example", "emp-1", NOW);
            }
            if ("throttled-secret".equals(secret)) {
                throw new ActivationThrottledException("Throttled by fake", 60);
            }
            if ("expired-secret".equals(secret)) {
                throw new InvitationExpiredException("Expired by fake");
            }
            if ("taken-secret".equals(secret)) {
                throw new BindingConflictException("Taken by fake");
            }
            throw new InvitationConflictException("Invalid by fake");
        }

        @Override
        public OffboardResult offboard(Authentication caller, String key, UUID employeeId,
                                       Instant accessEndsAt, LocalTime cutoffTime, String cutoffTimezone,
                                       String reason) {
            allow(caller);
            return new OffboardResult(employeeId, null);
        }

        @Override
        public RebindResult rebind(Authentication caller, String key, UUID employeeId,
                                   String newIssuer, String newSubject, String reason) {
            allow(caller);
            return new RebindResult(employeeId, UUID.randomUUID());
        }

        @Override
        public RehireResult rehire(Authentication caller, String key, UUID employeeId, String reason) {
            allow(caller);
            return new RehireResult(employeeId);
        }

        @Override
        public RestoreResult restoreAccess(Authentication caller, String key, UUID employeeId,
                                           List<String> roles, String scope, String reason) {
            allow(caller);
            if (roles.contains("SUPERUSER")) {
                throw new InvalidRoleException("Role by fake");
            }
            return new RestoreResult(employeeId, UUID.randomUUID(), roles, scope);
        }
    }

    @TestConfiguration
    static class Fakes {
        @Bean
        @Primary
        EnrollmentCommands commands() {
            return new FakeCommands();
        }
    }

    @Autowired WebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void issueReturnsSecretOnceWithLocation() throws Exception {
        mvc.perform(post("/api/v1/enrollments/invitations").with(user("hr")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "key-1")
                .content("{\"employeeId\":\"" + EMPLOYEE_ID + "\",\"intendedIdentityRef\":\"hrd@example.id\","
                    + "\"expectedIssuer\":\"https://idp.example\",\"expectedSubject\":\"emp-1\"}"))
            .andExpect(status().isCreated())
            .andExpect(header().exists(HttpHeaders.LOCATION))
            .andExpect(jsonPath("$.data.secret").value("raw-secret"))
            .andExpect(jsonPath("$.data.deliveryStatus").value("PENDING"))
            .andExpect(jsonPath("$.metadata").exists());
    }

    @Test
    void issueValidationAndConflict() throws Exception {
        mvc.perform(post("/api/v1/enrollments/invitations").with(user("hr")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "key-2")
                .content("{\"intendedIdentityRef\":\"hrd@example.id\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.key").value("VALIDATION_ERROR"));
        mvc.perform(post("/api/v1/enrollments/invitations").with(user("hr")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "key-3")
                .content("{\"employeeId\":\"" + UUID.randomUUID() + "\",\"intendedIdentityRef\":\"hrd@example.id\","
                    + "\"expectedIssuer\":\"https://idp.example\",\"expectedSubject\":\"emp-1\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.key").value("INVITATION_CONFLICT"));
    }

    @Test
    void activationReturnsBindingOrDomainConflict() throws Exception {
        mvc.perform(post("/api/v1/enrollments/activations").with(user("hr")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "key-4")
                .content("{\"invitationId\":\"" + INVITATION_ID + "\",\"secret\":\"raw-secret\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.employeeId").value(EMPLOYEE_ID.toString()))
            .andExpect(jsonPath("$.data.issuer").value("https://idp.example"));
        mvc.perform(post("/api/v1/enrollments/activations").with(user("hr")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "key-5")
                .content("{\"invitationId\":\"" + INVITATION_ID + "\",\"secret\":\"expired-secret\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.key").value("INVITATION_EXPIRED"));
        mvc.perform(post("/api/v1/enrollments/activations").with(user("hr")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "key-6")
                .content("{\"invitationId\":\"" + INVITATION_ID + "\",\"secret\":\"taken-secret\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.key").value("BINDING_CONFLICT"));
        mvc.perform(post("/api/v1/enrollments/activations").with(user("hr")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "key-7")
                .content("{\"invitationId\":\"" + INVITATION_ID + "\",\"secret\":\"wrong\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.key").value("INVITATION_CONFLICT"));
    }

    @Test
    void malformedIdempotencyKeyIsClientError() throws Exception {
        mvc.perform(post("/api/v1/enrollments/invitations").with(user("hr")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "k".repeat(200))
                .content("{\"employeeId\":\"" + EMPLOYEE_ID + "\",\"intendedIdentityRef\":\"hrd@example.id\","
                    + "\"expectedIssuer\":\"https://idp.example\",\"expectedSubject\":\"emp-1\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.key").value("INVALID_IDEMPOTENCY_KEY"));
    }

    @Test
    void throttledActivationReturnsRetryableStatus() throws Exception {
        mvc.perform(post("/api/v1/enrollments/activations").with(user("hr")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "key-7b")
                .content("{\"invitationId\":\"" + INVITATION_ID + "\",\"secret\":\"throttled-secret\"}"))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.key").value("ACTIVATION_THROTTLED"))
            .andExpect(header().exists("Retry-After"));
    }

    @Test
    void lifecycleActionsReturnSimpleStatus() throws Exception {
        mvc.perform(post("/api/v1/enrollments/employees/" + EMPLOYEE_ID + "/invitations").with(user("hr")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "key-8")
                .content("{\"intendedIdentityRef\":\"hrd@example.id\","
                    + "\"expectedIssuer\":\"https://idp.example\",\"expectedSubject\":\"emp-1\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.secret").value("raw-secret"));
        mvc.perform(post("/api/v1/enrollments/employees/" + EMPLOYEE_ID + "/offboard").with(user("hr")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "key-9")
                .content("{\"accessEndsAt\":\"2026-11-01T00:00:00Z\",\"reason\":\"Done\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value("OFFBOARDED"));
        mvc.perform(post("/api/v1/enrollments/employees/" + EMPLOYEE_ID + "/rebind").with(user("hr")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "missing-verification")
                .content("{\"newIssuer\":\"https://idp.example\",\"newSubject\":\"emp-2\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/enrollments/employees/" + EMPLOYEE_ID + "/rebind").with(user("hr")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "key-10")
                .content("{\"newIssuer\":\"https://idp.example\",\"newSubject\":\"emp-2\",\"reason\":\"IDV-100\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value("REBOUND"));
        mvc.perform(post("/api/v1/enrollments/employees/" + EMPLOYEE_ID + "/rehire").with(user("hr")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "key-11")
                .content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value("REHIRED"));
        mvc.perform(post("/api/v1/enrollments/employees/" + EMPLOYEE_ID + "/access-restore").with(user("hr")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "key-12")
                .content("{\"roles\":[\"EMPLOYEE\"]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value("ACCESS_RESTORED"));
        mvc.perform(post("/api/v1/enrollments/employees/" + EMPLOYEE_ID + "/access-restore").with(user("hr")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "key-13")
                .content("{\"roles\":[\"SUPERUSER\"]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.key").value("INVALID_ROLE"));
    }

    @Test
    void restoreValidationRejectsEmptyAndNullRoles() throws Exception {
        mvc.perform(post("/api/v1/enrollments/employees/" + EMPLOYEE_ID + "/access-restore").with(user("hr")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "key-14")
                .content("{\"roles\":[]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.key").value("VALIDATION_ERROR"));
        mvc.perform(post("/api/v1/enrollments/employees/" + EMPLOYEE_ID + "/access-restore").with(user("hr")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "key-15")
                .content("{\"roles\":[null]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.key").value("VALIDATION_ERROR"));
    }
}
