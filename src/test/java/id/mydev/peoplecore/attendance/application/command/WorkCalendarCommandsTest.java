package id.mydev.peoplecore.attendance.application.command;

import id.mydev.peoplecore.attendance.application.command.WorkCalendarPayloads.AssignmentPayload;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarPayloads.PreviewPayload;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarPayloads.PublishCalendarPayload;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarPayloads.PublishSchedulePayload;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarPayloads.ScheduleDayPayload;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarResults.AssignmentResult;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarResults.RevisionResult;
import id.mydev.peoplecore.attendance.application.service.WorkCalendarService;
import id.mydev.peoplecore.attendance.application.trail.WorkCalendarTrailMapper;
import id.mydev.peoplecore.common.command.CommandExecutionService;
import id.mydev.peoplecore.common.command.CommandExecutionService.AuditDescriptor;
import id.mydev.peoplecore.common.command.CommandExecutionService.OutboxDescriptor;
import id.mydev.peoplecore.common.command.DefaultCommandResultSerializer;
import id.mydev.peoplecore.identity.domain.model.HrisRole;
import id.mydev.peoplecore.identity.domain.model.RoleAssignment;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.identity.domain.repository.RoleAssignmentRepository;
import id.mydev.peoplecore.identity.domain.repository.UserAccountRepository;
import id.mydev.peoplecore.organization.application.command.EmployeeResults.EmployeeResult;
import id.mydev.peoplecore.organization.application.service.EmployeeService;
import id.mydev.peoplecore.support.JwtAuthFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(properties = {
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "peoplecore.organization.allowed-units=ENG,FIN,OPS"
})
@Transactional
class WorkCalendarCommandsTest {

    static final String ISSUER = "https://idp.example";
    static final Instant NOW = Instant.parse("2026-10-08T10:00:00Z");

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    static class FakeExecution extends CommandExecutionService {
        private final DefaultCommandResultSerializer codec =
            new DefaultCommandResultSerializer(new ObjectMapper());

        FakeExecution() {
            super(null, null, null, null, null, null, null, null);
        }

        @Override
        public <T> T executeCommand(CommandContext context, Supplier<T> domainAction,
                                    Class<T> resultType,
                                    Function<T, AuditDescriptor> auditMapping,
                                    Function<T, OutboxDescriptor> outboxMapping) {
            T result = domainAction.get();
            auditMapping.apply(result);
            outboxMapping.apply(result);
            return codec.deserialize(codec.serialize(result), resultType);
        }
    }

    @TestConfiguration
    static class FakeCommands {
        @Bean
        @Primary
        WorkCalendarCommands workCalendarCommands(WorkCalendarService calendars,
                                                  WorkCalendarTrailMapper trail) {
            return new WorkCalendarCommands(calendars, new FakeExecution(), trail);
        }
    }

    @Autowired WorkCalendarCommands commands;
    @Autowired WorkCalendarService calendars;
    @Autowired EmployeeService employees;
    @Autowired UserAccountRepository accountRepository;
    @Autowired RoleAssignmentRepository roleRepository;
    @Autowired id.mydev.peoplecore.attendance.domain.repository.WorkScheduleRevisionRepository scheduleRevisions;
    @Autowired jakarta.persistence.EntityManager entityManager;

    private Authentication hrCaller;
    private Authentication scopedCaller;
    private UUID employeePublicId;

    @BeforeEach
    void seedAccess() {
        UserAccount hr = accountRepository.save(new UserAccount(UUID.randomUUID(), ISSUER, "hr-1", NOW));
        roleRepository.save(new RoleAssignment(hr.getId(), HrisRole.HR_ADMIN, null, NOW.minusSeconds(60), NOW));
        hrCaller = JwtAuthFixture.token(ISSUER, "hr-1");

        UserAccount scoped = accountRepository.save(new UserAccount(UUID.randomUUID(), ISSUER, "hr-eng", NOW));
        roleRepository.save(new RoleAssignment(scoped.getId(), HrisRole.HR_ADMIN, "ENG", NOW.minusSeconds(60), NOW));
        scopedCaller = JwtAuthFixture.token(ISSUER, "hr-eng");

        EmployeeResult target = employees.create(UUID.randomUUID(), "EMP-TARGET", hrCaller);
        employeePublicId = target.employeeId();
        employees.addAssignment(employeePublicId, UUID.randomUUID(), "ENG", null, "STAFF",
            NOW.minusSeconds(3600), null, hrCaller);
    }

    @AfterEach
    void clearCaller() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void commandPathPublishesScheduleWithSerializableReceipt() {
        RevisionResult result = commands.publishSchedule(hrCaller, "schedule-key-1",
            new PublishSchedulePayload(UUID.randomUUID(), "Asia/Jakarta", weekdaySchedule()));
        assertEquals("PUBLISHED", result.status());
        assertNotNull(result.publicId());
    }

    @Test
    void commandPathPublishesCalendarWithSerializableReceipt() {
        RevisionResult result = commands.publishCalendar(hrCaller, "calendar-key-1",
            new PublishCalendarPayload(UUID.randomUUID(), "JKT", List.of()));
        assertEquals("PUBLISHED", result.status());
        assertNotNull(result.publicId());
    }

    @Test
    void commandPathAssignsScheduleWithSerializableReceipt() {
        UUID scheduleId = commands.publishSchedule(hrCaller, "schedule-key-2",
            new PublishSchedulePayload(UUID.randomUUID(), "Asia/Jakarta", weekdaySchedule())).publicId();
        AssignmentResult result = commands.assignSchedule(scopedCaller, "assign-key-1",
            new AssignmentPayload(UUID.randomUUID(), employeePublicId, scheduleId,
                LocalDate.of(2026, 10, 8), null, null, null,
                fingerprintFor(scheduleId, LocalDate.of(2026, 10, 8))));
        assertEquals(employeePublicId, result.employeePublicId());
        assertNotNull(result.publicId());
    }

    @Test
    void commandPathAssignsCalendarWithSerializableReceipt() {
        UUID calendarId = commands.publishCalendar(hrCaller, "calendar-key-2",
            new PublishCalendarPayload(UUID.randomUUID(), "JKT", List.of())).publicId();
        AssignmentResult result = commands.assignCalendar(scopedCaller, "assign-key-2",
            new AssignmentPayload(UUID.randomUUID(), employeePublicId, calendarId,
                LocalDate.of(2026, 10, 8), null, null, null,
                fingerprintFor(calendarId, LocalDate.of(2026, 10, 8))));
        assertEquals(employeePublicId, result.employeePublicId());
        assertNotNull(result.publicId());
    }

    private String fingerprintFor(UUID revisionId, LocalDate fromDate) {
        entityManager.flush();
        boolean schedule = scheduleRevisions.findByPublicId(revisionId).isPresent();
        return calendars.previewImpact(new PreviewPayload(
            schedule ? revisionId : null, schedule ? null : revisionId,
            fromDate, fromDate.plusDays(366), List.of(employeePublicId), null, null),
            hrCaller).fingerprint();
    }

    private static List<ScheduleDayPayload> weekdaySchedule() {
        return List.of(
            new ScheduleDayPayload(1, LocalTime.of(9, 0), LocalTime.of(17, 0), 0),
            new ScheduleDayPayload(2, LocalTime.of(9, 0), LocalTime.of(17, 0), 0),
            new ScheduleDayPayload(3, LocalTime.of(9, 0), LocalTime.of(17, 0), 0),
            new ScheduleDayPayload(4, LocalTime.of(9, 0), LocalTime.of(17, 0), 0),
            new ScheduleDayPayload(5, LocalTime.of(9, 0), LocalTime.of(17, 0), 0));
    }
}
