package id.mydev.peoplecore.approval.api.controller;

import id.mydev.peoplecore.approval.api.dto.ApprovalDto;
import id.mydev.peoplecore.approval.application.command.ApprovalPayloads.RulePayload;
import id.mydev.peoplecore.approval.application.command.ApprovalRuleCommands;
import id.mydev.peoplecore.approval.application.command.ApprovalResults.ActivationResult;
import id.mydev.peoplecore.approval.application.command.ApprovalResults.RoutePreview;
import id.mydev.peoplecore.approval.application.command.ApprovalResults.RuleSetResult;
import id.mydev.peoplecore.approval.application.service.ApprovalRuleService;
import id.mydev.peoplecore.approval.domain.exception.RulePublishException;
import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
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
class ApprovalRuleControllerTest {

    private static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");
    private static final UUID SET_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID REVIEWER_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    static class FakeRules extends ApprovalRuleService {
        FakeRules() {
            super(null, null, null, null, null, null, null, null, null);
        }

        @Override
        public RoutePreview previewRoute(String orgUnit, String jobLevel, UUID employeePublicId,
                                         Authentication caller) {
            return new RoutePreview(false, null, null, null, "NO_MATCH", orgUnit, jobLevel);
        }
    }

    static class FakeCommands extends ApprovalRuleCommands {
        FakeCommands() {
            super(null, null, null);
        }

        @Override
        public RuleSetResult publish(Authentication caller, String idempotencyKey,
                                     UUID ruleSetPublicId, List<RulePayload> ruleInputs) {
            if (ruleInputs.isEmpty()) {
                throw new RulePublishException("Empty by fake");
            }
            return new RuleSetResult(SET_ID, "PUBLISHED", ruleInputs.size(), NOW);
        }

        @Override
        public ActivationResult activate(Authentication caller, String idempotencyKey, UUID ruleSetPublicId) {
            if (!SET_ID.equals(ruleSetPublicId)) {
                throw new ResourceNotFoundException();
            }
            return new ActivationResult(SET_ID, NOW);
        }
    }

    @TestConfiguration
    static class Fakes {
        @Bean
        @Primary
        ApprovalRuleService approvalRuleService() {
            return new FakeRules();
        }

        @Bean
        @Primary
        ApprovalRuleCommands approvalRuleCommands() {
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
    void publishReturnsCreatedLocationAndBody() throws Exception {
        mvc.perform(post("/api/v1/approval/profile-rule-sets").with(user("hr")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "key-1")
                .content("{\"rules\":[{\"ruleId\":\"route-eng\",\"priority\":10,"
                    + "\"orgUnit\":\"ENG\",\"jobLevel\":\"L3\",\"reviewerAccountId\":\"" + REVIEWER_ID + "\"}]}"))
            .andExpect(status().isCreated())
            .andExpect(header().exists(HttpHeaders.LOCATION))
            .andExpect(jsonPath("$.data.id").value(SET_ID.toString()))
            .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
            .andExpect(jsonPath("$.metadata").exists());
    }

    @Test
    void publishRejectsEmptySnapshotAndMissingKey() throws Exception {
        mvc.perform(post("/api/v1/approval/profile-rule-sets").with(user("hr")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "key-2")
                .content("{\"rules\":[]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.key").value("VALIDATION_ERROR"));
        mvc.perform(post("/api/v1/approval/profile-rule-sets").with(user("hr")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rules\":[{\"ruleId\":\"r\",\"priority\":1}]}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void publishNormalizesBlankPredicates() throws Exception {
        mvc.perform(post("/api/v1/approval/profile-rule-sets").with(user("hr")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "key-5")
                .content("{\"rules\":[{\"ruleId\":\"route-any\",\"priority\":1,"
                    + "\"orgUnit\":\"\",\"jobLevel\":\"\",\"reviewerAccountId\":\"" + REVIEWER_ID + "\"}]}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.status").value("PUBLISHED"));
        mvc.perform(post("/api/v1/approval/profile-rule-sets").with(user("hr")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "key-6")
                .content("{\"rules\":[{\"ruleId\":\"route-bare\",\"priority\":1,"
                    + "\"reviewerAccountId\":\"" + REVIEWER_ID + "\"}]}"))
            .andExpect(status().isCreated());
    }

    @Test
    void activateAndPreviewFollowContract() throws Exception {
        mvc.perform(post("/api/v1/approval/profile-rule-sets/" + SET_ID + "/activate")
                .with(user("hr")).with(csrf())
                .header("Idempotency-Key", "key-3"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.ruleSetId").value(SET_ID.toString()));
        mvc.perform(post("/api/v1/approval/profile-rule-sets/" + UUID.randomUUID() + "/activate")
                .with(user("hr")).with(csrf())
                .header("Idempotency-Key", "key-4"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.key").value("RESOURCE_NOT_FOUND"));
        mvc.perform(post("/api/v1/approval/profile-routes/preview").with(user("hr")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orgUnit\":\"ENG\",\"jobLevel\":\"L3\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.matched").value(false))
            .andExpect(jsonPath("$.data.blockedReason").value("NO_MATCH"));
    }
}
