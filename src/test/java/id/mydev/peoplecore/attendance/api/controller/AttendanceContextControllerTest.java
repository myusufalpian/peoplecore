package id.mydev.peoplecore.attendance.api.controller;

import id.mydev.peoplecore.attendance.application.command.WorkCalendarCommands;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarPayloads.AssignmentPayload;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarPayloads.PreviewPayload;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarPayloads.PublishCalendarPayload;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarPayloads.PublishSchedulePayload;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarResults.AssignmentResult;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarResults.ImpactPreview;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarResults.OccurrenceListResult;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarResults.OccurrenceView;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarResults.RevisionResult;
import id.mydev.peoplecore.attendance.application.service.WorkCalendarService;
import id.mydev.peoplecore.attendance.domain.exception.ContextChangedException;
import id.mydev.peoplecore.attendance.domain.exception.WorkContextException;
import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class AttendanceContextControllerTest {

    static final UUID EMPLOYEE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID SCHEDULE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID ASSIGNMENT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");

    static class FakeCalendars extends WorkCalendarService {
        FakeCalendars() {
            super(null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        }

        @Override
        public ImpactPreview previewImpact(PreviewPayload payload, Authentication caller) {
            if (payload.fingerprint() != null) {
                throw new ContextChangedException("stale");
            }
            return new ImpactPreview(payload.employeePublicIds(), 7L, 0L, "fingerprint-value");
        }

        @Override
        public OccurrenceListResult queryOccurrences(UUID employeePublicId, LocalDate fromDate,
                                                     LocalDate toDate, Authentication caller) {
            if (!EMPLOYEE_ID.equals(employeePublicId)) {
                throw new ResourceNotFoundException();
            }
            return new OccurrenceListResult(List.of(
                new OccurrenceView(ASSIGNMENT_ID, EMPLOYEE_ID, LocalDate.of(2026, 10, 12),
                    Instant.parse("2026-10-12T02:00:00Z"), Instant.parse("2026-10-12T10:00:00Z"),
                    "Asia/Jakarta", false, null, false, null),
                new OccurrenceView(null, EMPLOYEE_ID, LocalDate.of(2026, 10, 10),
                    null, null, null, false, null, false, "NON_WORKING_DATE")));
        }
    }

    static class FakeCommands extends WorkCalendarCommands {
        FakeCommands() {
            super(null, null, null);
        }

        @Override
        public RevisionResult publishSchedule(Authentication caller, String idempotencyKey,
                                              PublishSchedulePayload payload) {
            return new RevisionResult(SCHEDULE_ID, "PUBLISHED", NOW);
        }

        @Override
        public RevisionResult publishCalendar(Authentication caller, String idempotencyKey,
                                              PublishCalendarPayload payload) {
            if (payload.location().contains("missing")) {
                throw new ResourceNotFoundException();
            }
            return new RevisionResult(SCHEDULE_ID, "PUBLISHED", NOW);
        }

        @Override
        public AssignmentResult assignSchedule(Authentication caller, String idempotencyKey,
                                               AssignmentPayload payload) {
            if (payload.fromDate().isBefore(LocalDate.of(2026, 10, 1))) {
                throw new WorkContextException(WorkContextException.OVERLAPPING_SCHEDULE, "overlap");
            }
            return new AssignmentResult(ASSIGNMENT_ID, payload.employeePublicId(),
                payload.revisionPublicId(), payload.fromDate(), payload.toDate());
        }

        @Override
        public AssignmentResult assignCalendar(Authentication caller, String idempotencyKey,
                                               AssignmentPayload payload) {
            return new AssignmentResult(ASSIGNMENT_ID, payload.employeePublicId(),
                payload.revisionPublicId(), payload.fromDate(), payload.toDate());
        }
    }

    @Autowired WebApplicationContext context;

    @org.springframework.boot.test.context.TestConfiguration
    static class Fakes {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        WorkCalendarService workCalendarService() {
            return new FakeCalendars();
        }

        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        WorkCalendarCommands workCalendarCommands() {
            return new FakeCommands();
        }
    }

    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
            .apply(springSecurity())
            .build();
    }

    @Test
    void publishScheduleReturnsCreated() throws Exception {
        mvc.perform(post("/api/v1/attendance/schedule-revisions")
                .with(user("hr").roles("HR"))
                .with(csrf())
                .header("Idempotency-Key", "schedule-key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"days\":[{\"weekday\":1,\"startTime\":\"09:00:00\","
                    + "\"endTime\":\"17:00:00\",\"overnightOffset\":0}]}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.id").value(SCHEDULE_ID.toString()))
            .andExpect(jsonPath("$.data.status").value("PUBLISHED"));
    }

    @Test
    void publishScheduleRequiresIdempotencyKey() throws Exception {
        mvc.perform(post("/api/v1/attendance/schedule-revisions")
                .with(user("hr").roles("HR"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"days\":[{\"weekday\":1,\"startTime\":\"09:00:00\","
                    + "\"endTime\":\"17:00:00\",\"overnightOffset\":0}]}"))
            .andExpect(status().is4xxClientError());
    }

    @Test
    void publishScheduleWithExplicitTimezoneReturnsCreated() throws Exception {
        mvc.perform(post("/api/v1/attendance/schedule-revisions")
                .with(user("hr").roles("HR"))
                .with(csrf())
                .header("Idempotency-Key", "schedule-key-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"timezone\":\"Asia/Jakarta\",\"days\":[{\"weekday\":2,"
                    + "\"startTime\":\"09:00:00\",\"endTime\":\"17:00:00\",\"overnightOffset\":0}]}"))
            .andExpect(status().isCreated());
    }

    @Test
    void publishScheduleRejectsEmptyDays() throws Exception {
        mvc.perform(post("/api/v1/attendance/schedule-revisions")
                .with(user("hr").roles("HR"))
                .with(csrf())
                .header("Idempotency-Key", "schedule-key-3")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"days\":[]}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void publishCalendarReturnsCreated() throws Exception {
        mvc.perform(post("/api/v1/attendance/calendar-revisions")
                .with(user("hr").roles("HR"))
                .with(csrf())
                .header("Idempotency-Key", "calendar-key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"location\":\"JKT\",\"holidays\":[]}"))
            .andExpect(status().isCreated());
    }

    @Test
    void previewReturnsFingerprint() throws Exception {
        mvc.perform(post("/api/v1/attendance/publish-preview")
                .with(user("hr").roles("HR"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scheduleRevisionId\":\"" + SCHEDULE_ID + "\","
                    + "\"fromDate\":\"2026-10-08\",\"toDate\":\"2026-10-15\","
                    + "\"employeeIds\":[\"" + EMPLOYEE_ID + "\"]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.fingerprint").value("fingerprint-value"));
    }

    @Test
    void previewWithStaleFingerprintConflicts() throws Exception {
        mvc.perform(post("/api/v1/attendance/publish-preview")
                .with(user("hr").roles("HR"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromDate\":\"2026-10-08\",\"toDate\":\"2026-10-15\","
                    + "\"employeeIds\":[\"" + EMPLOYEE_ID + "\"],\"fingerprint\":\"stale\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.key").value("CONTEXT_CHANGED"));
    }

    @Test
    void assignScheduleReturnsCreated() throws Exception {
        mvc.perform(post("/api/v1/employees/" + EMPLOYEE_ID + "/schedule-assignments")
                .with(user("hr").roles("HR"))
                .with(csrf())
                .header("Idempotency-Key", "assign-key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"revisionId\":\"" + SCHEDULE_ID + "\",\"fromDate\":\"2026-10-08\","
                    + "\"expectedFingerprint\":\"fingerprint-value\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.employeeId").value(EMPLOYEE_ID.toString()));
    }

    @Test
    void assignScheduleRejectsMissingFingerprint() throws Exception {
        mvc.perform(post("/api/v1/employees/" + EMPLOYEE_ID + "/schedule-assignments")
                .with(user("hr").roles("HR"))
                .with(csrf())
                .header("Idempotency-Key", "assign-key-missing")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"revisionId\":\"" + SCHEDULE_ID + "\",\"fromDate\":\"2026-10-08\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void assignScheduleOverlapConflictsWithReason() throws Exception {
        mvc.perform(post("/api/v1/employees/" + EMPLOYEE_ID + "/schedule-assignments")
                .with(user("hr").roles("HR"))
                .with(csrf())
                .header("Idempotency-Key", "assign-key-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"revisionId\":\"" + SCHEDULE_ID + "\",\"fromDate\":\"2026-09-01\","
                    + "\"expectedFingerprint\":\"fingerprint-value\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.key").value("OVERLAPPING_SCHEDULE"));
    }

    @Test
    void assignCalendarReturnsCreated() throws Exception {
        mvc.perform(post("/api/v1/employees/" + EMPLOYEE_ID + "/calendar-assignments")
                .with(user("hr").roles("HR"))
                .with(csrf())
                .header("Idempotency-Key", "assign-key-3")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"revisionId\":\"" + SCHEDULE_ID + "\",\"fromDate\":\"2026-10-08\","
                    + "\"expectedFingerprint\":\"fingerprint-value\"}"))
            .andExpect(status().isCreated());
    }

    @Test
    void workContextListsEffectiveAndBlockedDates() throws Exception {
        mvc.perform(get("/api/v1/employees/" + EMPLOYEE_ID + "/work-context")
                .with(user("hr").roles("HR"))
                .param("from", "2026-10-08")
                .param("to", "2026-10-15"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.occurrences[0].pinned").value(false))
            .andExpect(jsonPath("$.data.occurrences[1].blockedReason").value("NON_WORKING_DATE"));
    }

    @Test
    void workContextUnknownEmployeeNotFound() throws Exception {
        mvc.perform(get("/api/v1/employees/" + UUID.randomUUID() + "/work-context")
                .with(user("hr").roles("HR"))
                .param("from", "2026-10-08")
                .param("to", "2026-10-15"))
            .andExpect(status().isNotFound());
    }
}
