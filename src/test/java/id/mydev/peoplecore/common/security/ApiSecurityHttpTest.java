package id.mydev.peoplecore.common.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.RequestBody;
import tools.jackson.databind.JsonNode;

import id.mydev.peoplecore.common.api.PayloadLimits;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Import(ApiSecurityHttpTest.Controller.class)
class ApiSecurityHttpTest {
    @RestController
    static class Controller {
        @GetMapping("/secured") public String read() { return "OK"; }
        @PostMapping("/secured") public String write() { return "OK"; }
        @PostMapping(value = "/bounded", consumes = "application/json")
        public JsonNode bounded(@RequestBody JsonNode value) { return value; }
    }
    @Autowired WebApplicationContext context;
    @Autowired RequestBodyLimitFilter bodyFilter;
    @Autowired org.springframework.security.web.SecurityFilterChain securityChain;
    @Autowired org.springframework.boot.web.servlet.FilterRegistrationBean<RequestBodyLimitFilter> bodyFilterRegistration;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(new RequestCorrelationFilter())
            .apply(springSecurity()).build();
    }

    @Test
    void securityFilterAuthenticationUsesSameJsonContractAndChallenge() throws Exception {
        mvc.perform(get("/secured"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.key").value("UNAUTHORIZED"))
            .andExpect(jsonPath("$.message").exists())
            .andExpect(jsonPath("$.timestamp").exists())
            .andExpect(jsonPath("$.details").doesNotExist())
            .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("Basic")));
    }

    @Test
    void httpBodyAndJsonStructureLimitsUseApplicationErrorContract() throws Exception {
        mvc.perform(post("/bounded").with(user("actor")).with(csrf()).contentType("application/json")
                .content(" ".repeat(id.mydev.peoplecore.common.api.PayloadLimits.MAX_BODY_BYTES + 1)))
            .andExpect(status().isContentTooLarge()).andExpect(jsonPath("$.key").value("PAYLOAD_TOO_LARGE"))
            .andExpect(jsonPath("$.timestamp").exists()).andExpect(header().exists("X-Request-Id"));
        mvc.perform(post("/bounded").with(user("actor")).with(csrf()).contentType("application/json")
                .content("[".repeat(65) + "0" + "]".repeat(65)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.key").value("MALFORMED_JSON"));
        mvc.perform(post("/bounded").with(user("actor")).with(csrf()).contentType("application/json")
                .content("{\"value\":1}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.value").value(1));
    }

    @Test
    void unauthenticatedJsonRequestIsRejectedWithoutReadingBody() throws Exception {
        var bodyRead = new java.util.concurrent.atomic.AtomicBoolean();
        mvc.perform(get("/secured").contentType("application/json").with(request -> {
            return new org.springframework.mock.web.MockHttpServletRequest(request.getServletContext()) {
                {
                    setMethod("GET");
                    setRequestURI("/secured");
                    setServletPath("/secured");
                    setContentType("application/json");
                }
                @Override public jakarta.servlet.ServletInputStream getInputStream() {
                    bodyRead.set(true);
                    throw new AssertionError("Unauthenticated request body must not be read");
                }
            };
        })).andExpect(status().isUnauthorized());
        org.junit.jupiter.api.Assertions.assertFalse(bodyRead.get());
        var filters = securityChain.getFilters();
        int authorizationIndex = java.util.stream.IntStream.range(0, filters.size())
            .filter(index -> filters.get(index) instanceof org.springframework.security.web.access.intercept.AuthorizationFilter)
            .findFirst().orElseThrow();
        org.junit.jupiter.api.Assertions.assertTrue(filters.indexOf(bodyFilter) > authorizationIndex);
        org.junit.jupiter.api.Assertions.assertFalse(bodyFilterRegistration.isEnabled());
    }

    @Test
    void securityFilterCsrfDenialUsesSameContract() throws Exception {
        mvc.perform(post("/secured").with(user("actor")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.key").value("ACCESS_DENIED"))
            .andExpect(jsonPath("$.timestamp").exists());
    }
}
