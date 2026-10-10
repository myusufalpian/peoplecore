package id.mydev.peoplecore.identity.api.controller;

import id.mydev.peoplecore.identity.application.service.AccountAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class MeControllerTest {

    private static final Instant NOW = Instant.parse("2026-10-08T10:00:00Z");
    private static final UUID ACCOUNT_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID EMPLOYEE_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");

    static class FakeAccess extends AccountAccessService {
        FakeAccess() {
            super(null, null, null, null, null);
        }

        @Override
        public AccessSnapshot describeAccess(Authentication caller) {
            if (!"hr".equals(caller.getName())) {
                throw new AccessDeniedException("Denied by fake");
            }
            return new AccessSnapshot(ACCOUNT_ID, "ACTIVE", null,
                List.of("EMPLOYEE", "HR_ADMIN"), EMPLOYEE_ID, 7L);
        }
    }

    @TestConfiguration
    static class Fakes {
        @Bean
        @Primary
        AccountAccessService access() {
            return new FakeAccess();
        }
    }

    @Autowired WebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void accessReturnsLiveAccountRolesAndGeneration() throws Exception {
        mvc.perform(get("/api/v1/me/access").with(user("hr")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.accountId").value(ACCOUNT_ID.toString()))
            .andExpect(jsonPath("$.data.status").value("ACTIVE"))
            .andExpect(jsonPath("$.data.roles[0]").value("EMPLOYEE"))
            .andExpect(jsonPath("$.data.employeeId").value(EMPLOYEE_ID.toString()))
            .andExpect(jsonPath("$.data.authorizationGeneration").value(7))
            .andExpect(jsonPath("$.metadata").exists());
    }

    @Test
    void deniedAccessUsesForbiddenContract() throws Exception {
        mvc.perform(get("/api/v1/me/access").with(user("other")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.key").value("ACCESS_DENIED"));
    }

    @Test
    void unauthenticatedAccessIsUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/me/access"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.key").value("UNAUTHORIZED"));
    }
}
