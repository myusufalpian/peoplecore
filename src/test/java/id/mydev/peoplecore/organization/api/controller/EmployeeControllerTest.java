package id.mydev.peoplecore.organization.api.controller;

import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.organization.api.dto.EmployeeDto;
import id.mydev.peoplecore.organization.application.command.EmployeeCommands;
import id.mydev.peoplecore.organization.application.command.EmployeeResults.AssignmentResult;
import id.mydev.peoplecore.organization.application.command.EmployeeResults.EmployeeResult;
import id.mydev.peoplecore.organization.application.service.EmployeeService;
import id.mydev.peoplecore.organization.domain.exception.AssignmentOverlapException;
import id.mydev.peoplecore.organization.domain.exception.AssignmentValidationException;
import id.mydev.peoplecore.organization.domain.exception.EmployeeNumberConflictException;
import id.mydev.peoplecore.organization.domain.model.Employee;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class EmployeeControllerTest {

    private static final Instant NOW = Instant.parse("2026-10-08T10:00:00Z");
    private static final UUID EMPLOYEE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ASSIGNMENT_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");

    static class FakeEmployees extends EmployeeService {
        FakeEmployees() {
            super(null, null, null, null, null, null, null);
        }

        @Override
        public Employee get(UUID publicId, Authentication caller) {
            if (!"hr".equals(caller.getName())) {
                throw new AccessDeniedException("Denied by fake");
            }
            if (EMPLOYEE_ID.equals(publicId)) {
                return new Employee(EMPLOYEE_ID, "EMP-001", NOW);
            }
            throw new ResourceNotFoundException();
        }

        @Override
        public List<AssignmentResult> assignmentHistory(UUID employeePublicId, Authentication caller) {
            return List.of(new AssignmentResult(ASSIGNMENT_ID, employeePublicId, "ENG", "L3",
                Instant.parse("2026-01-01T00:00:00Z"), null));
        }
    }

    static class FakeCommands extends EmployeeCommands {
        FakeCommands() {
            super(null, null, null);
        }

        private static void allow(Authentication caller) {
            if (!"hr".equals(caller.getName())) {
                throw new AccessDeniedException("Denied by fake");
            }
        }

        @Override
        public EmployeeResult create(Authentication caller, String key, String employeeNumber) {
            allow(caller);
            if ("EMP-DUPLICATE".equals(employeeNumber)) {
                throw new EmployeeNumberConflictException("Taken");
            }
            return new EmployeeResult(UUID.randomUUID(), employeeNumber, "ACTIVE", NOW);
        }

        @Override
        public EmployeeResult updateNumber(Authentication caller, String key, UUID employeeId,
                                           String employeeNumber) {
            allow(caller);
            return new EmployeeResult(employeeId, employeeNumber, "ACTIVE", NOW);
        }

        @Override
        public AssignmentResult addAssignment(Authentication caller, String key, UUID employeeId,
                                              String orgUnit, UUID managerId, String jobLevel,
                                              Instant validFrom, Instant validTo) {
            allow(caller);
            if (!EMPLOYEE_ID.equals(employeeId)) {
                throw new ResourceNotFoundException();
            }
            if ("OVERLAP".equals(orgUnit)) {
                throw new AssignmentOverlapException("Overlap by fake");
            }
            if ("SELF".equals(orgUnit)) {
                throw new AssignmentValidationException("Invalid by fake");
            }
            return new AssignmentResult(ASSIGNMENT_ID, employeeId, orgUnit, jobLevel, validFrom, validTo);
        }
    }

    @TestConfiguration
    static class Fakes {
        @Bean
        @Primary
        EmployeeService employees() {
            return new FakeEmployees();
        }

        @Bean
        @Primary
        EmployeeCommands commands() {
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
    void createReturnsEnvelopeLocationAndNoSuccessFlag() throws Exception {
        mvc.perform(post("/api/v1/employees").with(user("hr")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "key-1")
                .content("{\"employeeNumber\":\"EMP-001\"}"))
            .andExpect(status().isCreated())
            .andExpect(header().exists(HttpHeaders.LOCATION))
            .andExpect(jsonPath("$.data.employeeNumber").value("EMP-001"))
            .andExpect(jsonPath("$.metadata").exists())
            .andExpect(jsonPath("$.success").doesNotExist())
            .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    void missingIdempotencyKeyIsRejected() throws Exception {
        mvc.perform(post("/api/v1/employees").with(user("hr")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"employeeNumber\":\"EMP-001\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void malformedIdempotencyKeyIsClientError() throws Exception {
        mvc.perform(post("/api/v1/employees").with(user("hr")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "k".repeat(129))
                .content("{\"employeeNumber\":\"EMP-001\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.key").value("INVALID_IDEMPOTENCY_KEY"))
            .andExpect(jsonPath("$.timestamp").exists());
        mvc.perform(post("/api/v1/employees").with(user("hr")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "   ")
                .content("{\"employeeNumber\":\"EMP-001\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.key").value("INVALID_IDEMPOTENCY_KEY"));
    }

    @Test
    void createValidationConflictAndDenialUseErrorContract() throws Exception {
        mvc.perform(post("/api/v1/employees").with(user("hr")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "key-2")
                .content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.key").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.details").isArray())
            .andExpect(jsonPath("$.timestamp").exists());
        mvc.perform(post("/api/v1/employees").with(user("hr")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "key-3")
                .content("{\"employeeNumber\":\"EMP-DUPLICATE\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.key").value("EMPLOYEE_CONFLICT"));
        mvc.perform(post("/api/v1/employees").with(user("other")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "key-4")
                .content("{\"employeeNumber\":\"EMP-001\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.key").value("ACCESS_DENIED"));
    }

    @Test
    void unauthenticatedReadsUseUnauthorizedContract() throws Exception {
        mvc.perform(get("/api/v1/employees/" + EMPLOYEE_ID))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.key").value("UNAUTHORIZED"))
            .andExpect(header().exists(HttpHeaders.WWW_AUTHENTICATE));
    }

    @Test
    void updateReturnsCurrentRepresentation() throws Exception {
        mvc.perform(put("/api/v1/employees/" + EMPLOYEE_ID).with(user("hr")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "key-5")
                .content("{\"employeeNumber\":\"EMP-002\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.employeeNumber").value("EMP-002"));
    }

    @Test
    void getReturnsDetailWithAssignments() throws Exception {
        mvc.perform(get("/api/v1/employees/" + EMPLOYEE_ID).with(user("hr")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.employee.employeeNumber").value("EMP-001"))
            .andExpect(jsonPath("$.data.assignments[0].orgUnit").value("ENG"))
            .andExpect(jsonPath("$.data.assignments[0].id").value(ASSIGNMENT_ID.toString()));
        mvc.perform(get("/api/v1/employees/" + UUID.randomUUID()).with(user("hr")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.key").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void addAssignmentReturnsCreatedOrDomainConflict() throws Exception {
        mvc.perform(post("/api/v1/employees/" + EMPLOYEE_ID + "/assignments").with(user("hr")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "key-6")
                .content("{\"orgUnit\":\"ENG\",\"jobLevel\":\"L3\",\"validFrom\":\"2026-01-01T00:00:00Z\"}"))
            .andExpect(status().isCreated())
            .andExpect(header().exists(HttpHeaders.LOCATION))
            .andExpect(jsonPath("$.data.jobLevel").value("L3"));
        mvc.perform(post("/api/v1/employees/" + EMPLOYEE_ID + "/assignments").with(user("hr")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "key-7")
                .content("{\"orgUnit\":\"OVERLAP\",\"jobLevel\":\"L3\",\"validFrom\":\"2026-01-01T00:00:00Z\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.key").value("ASSIGNMENT_OVERLAP"));
        mvc.perform(post("/api/v1/employees/" + EMPLOYEE_ID + "/assignments").with(user("hr")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "key-8")
                .content("{\"orgUnit\":\"SELF\",\"jobLevel\":\"L3\",\"validFrom\":\"2026-01-01T00:00:00Z\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.key").value("ASSIGNMENT_INVALID"));
        mvc.perform(post("/api/v1/employees/" + UUID.randomUUID() + "/assignments").with(user("hr")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "key-9")
                .content("{\"orgUnit\":\"ENG\",\"jobLevel\":\"L3\",\"validFrom\":\"2026-01-01T00:00:00Z\"}"))
            .andExpect(status().isNotFound());
    }
}
