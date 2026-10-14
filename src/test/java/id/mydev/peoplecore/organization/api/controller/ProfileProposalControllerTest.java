package id.mydev.peoplecore.organization.api.controller;

import id.mydev.peoplecore.common.api.InvalidPaginationException;
import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.organization.application.command.ProfilePayloads.ChangePayload;
import id.mydev.peoplecore.organization.application.command.ProfileProposalCommands;
import id.mydev.peoplecore.organization.application.command.ProfileResults.ContactCorrectionResult;
import id.mydev.peoplecore.organization.application.command.ProfileResults.ContactProfileResult;
import id.mydev.peoplecore.organization.application.command.ProfileResults.ProposalDetailResult;
import id.mydev.peoplecore.organization.application.command.ProfileResults.ProposalHistoryResult;
import id.mydev.peoplecore.organization.application.command.ProfileResults.ProposalQueueResult;
import id.mydev.peoplecore.organization.application.command.ProfileResults.ProposalResult;
import id.mydev.peoplecore.organization.application.command.ProfileResults.ProposalSummary;
import id.mydev.peoplecore.organization.application.service.ProfileProposalService;
import id.mydev.peoplecore.organization.domain.exception.ProfileConflictException;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ProfileProposalControllerTest {

    private static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");
    private static final UUID PROPOSAL_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID EMPLOYEE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID REVISION_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID ASSIGNMENT_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");

    static class FakeProposals extends ProfileProposalService {
        FakeProposals() {
            super(null, null, null, null, null, null, null, null, null, null, null, null, null);
        }

        @Override
        public UUID ownEmployeePublicId(Authentication caller) {
            return EMPLOYEE_ID;
        }

        @Override
        public ProposalDetailResult getProposal(UUID proposalPublicId, Authentication caller) {
            if (!PROPOSAL_ID.equals(proposalPublicId)) {
                throw new ResourceNotFoundException();
            }
            return new ProposalDetailResult(
                new ProposalResult(PROPOSAL_ID, EMPLOYEE_ID, "PENDING", 1, REVISION_ID, 2,
                    ASSIGNMENT_ID, null),
                List.of(new id.mydev.peoplecore.organization.application.command.ProfileResults.ChangeView(
                    "personalPhone", null, "+62 812 345 678")),
                new id.mydev.peoplecore.organization.application.command.ProfileResults.AssignmentView(
                    ASSIGNMENT_ID, UUID.randomUUID(), "ACTIVE", null, NOW, true), List.of("APPROVE"));
        }

        @Override
        public ProposalHistoryResult getHistory(UUID proposalPublicId, Authentication caller,
                                                int page, int size, int decisionPage, int evaluationPage, int assignmentPage) {
            if (!PROPOSAL_ID.equals(proposalPublicId)) {
                throw new ResourceNotFoundException();
            }
            if (page < 0 || size < 1 || size > 100) {
                throw new InvalidPaginationException();
            }
            return new ProposalHistoryResult(
                new ProposalResult(PROPOSAL_ID, EMPLOYEE_ID, "PENDING", 1, REVISION_ID, 2,
                    ASSIGNMENT_ID, null),
                List.of(new id.mydev.peoplecore.organization.application.command.ProfileResults.RevisionView(
                    null, null, "UNKNOWN", REVISION_ID, 2, null, NOW, "ENG", "L3", null, null,
                    List.of(new id.mydev.peoplecore.organization.application.command.ProfileResults.ChangeView(
                        "personalPhone", null, "+62 812 345 678")))),
                List.of(new id.mydev.peoplecore.organization.application.command.ProfileResults.DecisionView(
                    REVISION_ID, ASSIGNMENT_ID, "RETURN", null, "Fix format", NOW)),
                List.of(new id.mydev.peoplecore.organization.application.command.ProfileResults.EvaluationView(
                    REVISION_ID, NOW, null, "ENG", "L3", "BLOCKED", null, null, "NO_MATCH")),
                List.of(), 1, 1, 1, 0);
        }

        @Override
        public ProposalQueueResult queueProposals(Authentication caller, int page, int size) {
            if (page < 0 || size < 1 || size > 100) {
                throw new InvalidPaginationException();
            }
            return new ProposalQueueResult(List.of(
                new ProposalSummary(PROPOSAL_ID, EMPLOYEE_ID, "PENDING", null, ASSIGNMENT_ID,
                    UUID.randomUUID(), null, NOW)), page, size, 1);
        }

        @Override
        public ContactProfileResult getContactProfile(UUID employeePublicId, Authentication caller) {
            return new ContactProfileResult(EMPLOYEE_ID, "+62 812 345 678", null, null,
                null, null, null, 1, 0, 0, 0);
        }
    }

    static class FakeCommands extends ProfileProposalCommands {
        FakeCommands() {
            super(null, null, null);
        }

        @Override
        public ProposalResult create(Authentication caller, String idempotencyKey, UUID employeePublicId,
                                     List<ChangePayload> changes) {
            return new ProposalResult(PROPOSAL_ID, EMPLOYEE_ID, "DRAFT", 0, REVISION_ID, 1, null, null);
        }

        @Override
        public ProposalResult submit(Authentication caller, String idempotencyKey, UUID proposalPublicId,
                                     long expectedVersion) {
            return new ProposalResult(PROPOSAL_ID, EMPLOYEE_ID, "PENDING", 1, REVISION_ID, 2,
                ASSIGNMENT_ID, null);
        }

        @Override
        public ProposalResult edit(Authentication caller, String idempotencyKey, UUID proposalPublicId,
                                   long expectedVersion, List<ChangePayload> changes) {
            return new ProposalResult(PROPOSAL_ID, EMPLOYEE_ID, "DRAFT", 1, REVISION_ID, 2, null, null);
        }

        @Override
        public ProposalResult resubmit(Authentication caller, String idempotencyKey, UUID proposalPublicId,
                                       long expectedVersion, List<ChangePayload> changes) {
            return new ProposalResult(PROPOSAL_ID, EMPLOYEE_ID, "PENDING", 2, REVISION_ID, 3,
                ASSIGNMENT_ID, null);
        }

        @Override
        public ProposalResult withdraw(Authentication caller, String idempotencyKey, UUID proposalPublicId,
                                       long expectedVersion) {
            return new ProposalResult(PROPOSAL_ID, EMPLOYEE_ID, "CANCELLED", 2, REVISION_ID, 2,
                ASSIGNMENT_ID, null);
        }

        @Override
        public ProposalResult reassign(Authentication caller, String idempotencyKey, UUID proposalPublicId,
                                       long expectedVersion, UUID assignmentPublicId,
                                       UUID replacementAccountPublicId, String reason) {
            return new ProposalResult(PROPOSAL_ID, EMPLOYEE_ID, "PENDING", 2, REVISION_ID, 2,
                ASSIGNMENT_ID, null);
        }

        @Override
        public ProposalResult retryRoute(Authentication caller, String idempotencyKey, UUID proposalPublicId,
                                         long expectedVersion) {
            return new ProposalResult(PROPOSAL_ID, EMPLOYEE_ID, "PENDING", 2, REVISION_ID, 2,
                ASSIGNMENT_ID, null);
        }

        @Override
        public ProposalResult decide(Authentication caller, String idempotencyKey, UUID proposalPublicId,
                                     long expectedVersion, UUID revisionPublicId, UUID assignmentPublicId,
                                     String commandType, String reason) {
            if ("PROFILE_APPROVE".equals(commandType)) {
                throw new ProfileConflictException("Conflict by fake");
            }
            if ("PROFILE_REJECT".equals(commandType)) {
                return new ProposalResult(PROPOSAL_ID, EMPLOYEE_ID, "REJECTED", 2, REVISION_ID, 2,
                    ASSIGNMENT_ID, null);
            }
            return new ProposalResult(PROPOSAL_ID, EMPLOYEE_ID, "RETURNED", 2, REVISION_ID, 2,
                ASSIGNMENT_ID, null);
        }

        @Override
        public ContactCorrectionResult masterCorrect(Authentication caller, String idempotencyKey,
                                                      UUID employeePublicId, long expectedPhoneVersion,
                                                      long expectedEmailVersion, long expectedAddressVersion,
                                                      long expectedEmergencyVersion, List<ChangePayload> changes,
                                                      String reason) {
            return new ContactCorrectionResult(EMPLOYEE_ID, 2, 0, 0, 0);
        }
    }

    @TestConfiguration
    static class Fakes {
        @Bean
        @Primary
        ProfileProposalService profileProposalService() {
            return new FakeProposals();
        }

        @Bean
        @Primary
        ProfileProposalCommands profileProposalCommands() {
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
    void createReturnsCreatedLocationAndBody() throws Exception {
        mvc.perform(post("/api/v1/employees/me/profile-proposals").with(user("emp-1")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "key-1")
                .content("{\"changes\":[{\"fieldName\":\"personalPhone\","
                    + "\"proposedValue\":\"+62 812 345 678\"}]}"))
            .andExpect(status().isCreated())
            .andExpect(header().exists(HttpHeaders.LOCATION))
            .andExpect(jsonPath("$.data.proposalId").value(PROPOSAL_ID.toString()))
            .andExpect(jsonPath("$.data.state").value("DRAFT"))
            .andExpect(jsonPath("$.metadata").exists());
    }

    @Test
    void explicitNullProposedValuesAreRejectedAtHttpBoundary() throws Exception {
        mvc.perform(post("/api/v1/employees/me/profile-proposals").with(user("emp-1")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", "null-only")
                .content("{\"changes\":[{\"fieldName\":\"personalEmail\",\"proposedValue\":null}]}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/employees/me/profile-proposals").with(user("emp-1")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", "mixed-null")
                .content("{\"changes\":[{\"fieldName\":\"personalPhone\",\"proposedValue\":\"081234567\"},"
                    + "{\"fieldName\":\"personalEmail\",\"proposedValue\":null}]}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void unknownFieldRejectedAndMissingKeyIsBadRequest() throws Exception {
        mvc.perform(post("/api/v1/employees/me/profile-proposals").with(user("emp-1")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "key-2")
                .content("{\"changes\":[],\"salary\":\"100\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/employees/me/profile-proposals").with(user("emp-1")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"changes\":[{\"fieldName\":\"personalPhone\","
                    + "\"proposedValue\":\"+62 812 345 678\"}]}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void submitDecideAndReadsFollowContract() throws Exception {
        mvc.perform(post("/api/v1/profile-proposals/" + PROPOSAL_ID + "/submit")
                .with(user("emp-1")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "key-3")
                .content("{\"expectedVersion\":0}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.state").value("PENDING"))
            .andExpect(jsonPath("$.data.assignmentId").value(ASSIGNMENT_ID.toString()));
        mvc.perform(post("/api/v1/profile-proposals/" + PROPOSAL_ID + "/approve")
                .with(user("hr-1")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "key-4")
                .content("{\"expectedVersion\":1,\"revisionId\":\"" + REVISION_ID
                    + "\",\"assignmentId\":\"" + ASSIGNMENT_ID + "\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.key").value("PROFILE_CHANGE_CONFLICT"));
        mvc.perform(post("/api/v1/profile-proposals/" + PROPOSAL_ID + "/return")
                .with(user("hr-1")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "key-5")
                .content("{\"expectedVersion\":1,\"revisionId\":\"" + REVISION_ID
                    + "\",\"assignmentId\":\"" + ASSIGNMENT_ID + "\",\"reason\":\"Fix format\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.state").value("RETURNED"));
        mvc.perform(get("/api/v1/profile-proposals/" + PROPOSAL_ID).with(user("emp-1")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.proposal.proposalId").value(PROPOSAL_ID.toString()));
        mvc.perform(get("/api/v1/profile-proposals/" + UUID.randomUUID()).with(user("emp-1")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.key").value("RESOURCE_NOT_FOUND"));
        mvc.perform(get("/api/v1/profile-proposals/" + PROPOSAL_ID + "/history").with(user("emp-1")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.revisions[0].changes[0].proposedValue")
                .value("+62 812 345 678"))
            .andExpect(jsonPath("$.data.decisions[0].action").value("RETURN"))
            .andExpect(jsonPath("$.data.evaluations[0].result").value("BLOCKED"))
            .andExpect(jsonPath("$.metadata.totalRevisions").value(1));
        mvc.perform(get("/api/v1/profile-proposals/" + PROPOSAL_ID + "/history?page=0&size=0")
                .with(user("emp-1")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.key").value("INVALID_PAGINATION"));
        mvc.perform(get("/api/v1/profile-proposals").with(user("hr-1")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.proposals[0].proposalId").value(PROPOSAL_ID.toString()))
            .andExpect(jsonPath("$.metadata.total").value(1));
        mvc.perform(get("/api/v1/profile-proposals?page=0&size=101").with(user("hr-1")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.key").value("INVALID_PAGINATION"));
        mvc.perform(get("/api/v1/employees/me/contact-profile").with(user("emp-1")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.personalPhone").value("+62 812 345 678"));
    }

    @Test
    void lifecycleEndpointsFollowContract() throws Exception {
        mvc.perform(patch("/api/v1/profile-proposals/" + PROPOSAL_ID).with(user("emp-1")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "key-10")
                .content("{\"expectedVersion\":0,\"changes\":[{\"fieldName\":\"personalPhone\","
                    + "\"proposedValue\":\"+62 812 000 001\"}]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.state").value("DRAFT"));
        mvc.perform(post("/api/v1/profile-proposals/" + PROPOSAL_ID + "/resubmit")
                .with(user("emp-1")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "key-11")
                .content("{\"expectedVersion\":1,\"changes\":[{\"fieldName\":\"personalPhone\","
                    + "\"proposedValue\":\"+62 812 000 002\"}]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.state").value("PENDING"));
        mvc.perform(post("/api/v1/profile-proposals/" + PROPOSAL_ID + "/reject")
                .with(user("hr-1")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "key-12")
                .content("{\"expectedVersion\":1,\"revisionId\":\"" + REVISION_ID
                    + "\",\"assignmentId\":\"" + ASSIGNMENT_ID + "\",\"reason\":\"Unverifiable\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.state").value("REJECTED"));
        mvc.perform(post("/api/v1/profile-proposals/" + PROPOSAL_ID + "/reassign")
                .with(user("hr-1")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "key-13")
                .content("{\"expectedVersion\":1,\"assignmentId\":\"" + ASSIGNMENT_ID
                    + "\",\"replacementAccountId\":\"" + UUID.randomUUID()
                    + "\",\"reason\":\"Covering leave\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.state").value("PENDING"));
        mvc.perform(post("/api/v1/profile-proposals/" + PROPOSAL_ID + "/retry-route")
                .with(user("hr-1")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "key-14")
                .content("{\"expectedVersion\":1}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.state").value("PENDING"));
        mvc.perform(post("/api/v1/profile-proposals/" + PROPOSAL_ID + "/withdraw")
                .with(user("emp-1")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "key-15")
                .content("{\"expectedVersion\":1}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.state").value("CANCELLED"));
    }

    @Test
    void masterCorrectReturnsUpdatedProfile() throws Exception {
        mvc.perform(patch("/api/v1/employees/" + EMPLOYEE_ID + "/contact-profile")
                .with(user("hr-1")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "key-6")
                .content("{\"expectedPhoneVersion\":1,\"expectedEmailVersion\":0,"
                    + "\"expectedAddressVersion\":0,\"expectedEmergencyVersion\":0,"
                    + "\"changes\":[{\"fieldName\":\"personalPhone\","
                    + "\"proposedValue\":\"+62 812 999 999\"}],\"reason\":\"HR correction\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.personalPhone").value("+62 812 345 678"))
            .andExpect(jsonPath("$.data.phoneVersion").value(1));
    }
}
