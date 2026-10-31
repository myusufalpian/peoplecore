package id.mydev.peoplecore.attendance.application.service;

import id.mydev.peoplecore.attendance.application.command.WorkCalendarPayloads.AssignmentPayload;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarPayloads.HolidayPayload;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarPayloads.PreviewPayload;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarPayloads.PublishCalendarPayload;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarPayloads.PublishSchedulePayload;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarPayloads.ScheduleDayPayload;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarResults.AssignmentResult;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarResults.ImpactPreview;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarResults.OccurrenceListResult;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarResults.OccurrenceView;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarResults.RevisionResult;
import id.mydev.peoplecore.attendance.domain.exception.ContextChangedException;
import id.mydev.peoplecore.attendance.domain.exception.WorkContextException;
import id.mydev.peoplecore.attendance.domain.model.AttendanceOccurrence;
import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.common.command.AdmissionGuard;
import id.mydev.peoplecore.identity.domain.model.AccountBinding;
import id.mydev.peoplecore.identity.domain.model.HrisRole;
import id.mydev.peoplecore.identity.domain.model.RoleAssignment;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.identity.domain.repository.AccountBindingRepository;
import id.mydev.peoplecore.identity.domain.repository.RoleAssignmentRepository;
import id.mydev.peoplecore.identity.domain.repository.UserAccountRepository;
import id.mydev.peoplecore.organization.application.command.EmployeeResults.EmployeeResult;
import id.mydev.peoplecore.organization.application.service.EmployeeService;
import id.mydev.peoplecore.organization.domain.model.Employee;
import id.mydev.peoplecore.organization.domain.repository.EmployeeRepository;
import id.mydev.peoplecore.support.JwtAuthFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "peoplecore.organization.allowed-units=ENG,FIN,OPS"
})
@Transactional
class WorkCalendarServiceTest {

    static final String ISSUER = "https://idp.example";
    static final Instant NOW = Instant.parse("2026-10-08T10:00:00Z");
    static final LocalDate MONDAY = LocalDate.of(2026, 10, 12);
    static final LocalDate SATURDAY = LocalDate.of(2026, 10, 10);
    static final LocalDate SUNDAY = LocalDate.of(2026, 10, 11);

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, java.time.ZoneOffset.UTC);
        }
    }

    @Autowired WorkCalendarService calendars;
    @Autowired AttendanceOccurrenceResolver resolver;
    @Autowired EmployeeService employees;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired UserAccountRepository accountRepository;
    @Autowired RoleAssignmentRepository roleRepository;
    @Autowired AccountBindingRepository bindingRepository;
    @Autowired AdmissionGuard admission;
    @Autowired JdbcTemplate jdbc;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @Autowired id.mydev.peoplecore.attendance.domain.repository.WorkScheduleRevisionRepository scheduleRevisionRepository;
    @Autowired id.mydev.peoplecore.attendance.domain.repository.HolidayCalendarRevisionRepository calendarRevisionRepository;
    @Autowired id.mydev.peoplecore.attendance.domain.repository.WorkScheduleDayRepository scheduleDayRepository;
    @Autowired id.mydev.peoplecore.attendance.domain.repository.EmployeeScheduleAssignmentRepository scheduleAssignmentRepository;
    @Autowired id.mydev.peoplecore.attendance.domain.repository.EmployeeCalendarAssignmentRepository calendarAssignmentRepository;
    @Autowired id.mydev.peoplecore.attendance.domain.repository.AttendanceOccurrenceRepository occurrenceRepository;
    @Autowired id.mydev.peoplecore.attendance.domain.repository.HolidayDateRepository holidayDateRepository;
    @Autowired AttendanceAccountGuard accountGuard;
    @Autowired id.mydev.peoplecore.identity.application.service.AccountAccessService access;

    private Authentication hrCaller;
    private Authentication scopedCaller;
    private Authentication memberCaller;
    private UUID employeePublicId;
    private UUID ownEmployeePublicId;
    private UUID finEmployeePublicId;
    private Long hrAccountId;
    private Long scopedAccountId;

    @Autowired WorkRevisionQueryService revisionQueries;

    @Test
    void annualContextReadUsesRangeQueriesWithoutPinning() {
        UUID schedule = publishWeekdays();
        UUID calendar = calendars.publishCalendar(new PublishCalendarPayload(UUID.randomUUID(), "JKT", List.of()), hrCaller).publicId();
        assignSchedule(ownEmployeePublicId, schedule, LocalDate.of(2026, 10, 8), hrCaller);
        assignCalendar(ownEmployeePublicId, calendar, LocalDate.of(2026, 10, 8), hrCaller);
        entityManager.flush();
        entityManager.clear();
        var stats = entityManager.getEntityManagerFactory().unwrap(org.hibernate.SessionFactory.class).getStatistics();
        boolean enabled = stats.isStatisticsEnabled();
        stats.setStatisticsEnabled(true);
        stats.clear();
        try {
            var result = calendars.queryOccurrences(ownEmployeePublicId, LocalDate.of(2026, 10, 8),
                LocalDate.of(2027, 10, 9), memberCaller);
            assertEquals(366, result.occurrences().size());
            long queries = stats.getPrepareStatementCount();
            assertTrue(queries < 50, "Annual context must use range queries, actual SQL count: " + queries);
            assertEquals(0, occurrenceRepository.count());
        } finally {
            stats.setStatisticsEnabled(enabled);
        }
    }

    @Test
    void revisionCatalogProvidesPublishedContentToScopedHr() {
        UUID schedule = publishWeekdays();
        UUID calendar = calendars.publishCalendar(new PublishCalendarPayload(UUID.randomUUID(), "JKT",
            List.of(new HolidayPayload(MONDAY, "Holiday"))), hrCaller).publicId();
        entityManager.flush();
        assertEquals(schedule, revisionQueries.schedules(scopedCaller, 0, 20).data().getFirst().id());
        assertEquals(5, revisionQueries.schedule(scopedCaller, schedule).days().size());
        assertEquals(calendar, revisionQueries.calendars(scopedCaller, 0, 20).data().getFirst().id());
        assertEquals("Holiday", revisionQueries.calendar(scopedCaller, calendar).holidays().getFirst().holidayName());
    }

    @Test
    void revisionCatalogDeniesEmployeeAndUnknownRevision() {
        UUID schedule = publishWeekdays();
        assertThrows(AccessDeniedException.class, () -> revisionQueries.schedule(memberCaller, schedule));
        assertThrows(AccessDeniedException.class, () -> revisionQueries.calendars(memberCaller, 0, 20));
        assertThrows(ResourceNotFoundException.class, () -> revisionQueries.schedule(hrCaller, UUID.randomUUID()));
        assertThrows(ResourceNotFoundException.class, () -> revisionQueries.calendar(hrCaller, UUID.randomUUID()));
    }

    @Test
    void revisionCatalogValidatesPaginationAndHidesDrafts() {
        scheduleRevisionRepository.saveAndFlush(new id.mydev.peoplecore.attendance.domain.model.WorkScheduleRevision(
            UUID.randomUUID(), "Asia/Jakarta", NOW));
        assertTrue(revisionQueries.schedules(hrCaller, 0, 20).data().isEmpty());
        assertThrows(id.mydev.peoplecore.common.api.InvalidPaginationException.class,
            () -> revisionQueries.schedules(hrCaller, 0, 101));
    }

    @BeforeEach
    void seedAccess() {
        UserAccount hr = accountRepository.save(new UserAccount(UUID.randomUUID(), ISSUER, "hr-1", NOW));
        hrAccountId = hr.getId();
        roleRepository.save(new RoleAssignment(hr.getId(), HrisRole.HR_ADMIN, null, NOW.minusSeconds(60), NOW));
        hrCaller = JwtAuthFixture.token(ISSUER, "hr-1");

        UserAccount scoped = accountRepository.save(new UserAccount(UUID.randomUUID(), ISSUER, "hr-eng", NOW));
        scopedAccountId = scoped.getId();
        roleRepository.save(new RoleAssignment(scoped.getId(), HrisRole.HR_ADMIN, "ENG", NOW.minusSeconds(60), NOW));
        scopedCaller = JwtAuthFixture.token(ISSUER, "hr-eng");

        UserAccount member = accountRepository.save(new UserAccount(UUID.randomUUID(), ISSUER, "emp-1", NOW));
        roleRepository.save(new RoleAssignment(member.getId(), HrisRole.EMPLOYEE, null, NOW.minusSeconds(60), NOW));
        memberCaller = JwtAuthFixture.token(ISSUER, "emp-1");

        EmployeeResult target = employees.create(UUID.randomUUID(), "EMP-TARGET", hrCaller);
        employeePublicId = target.employeeId();
        employees.addAssignment(employeePublicId, UUID.randomUUID(), "ENG", null, "STAFF",
            NOW.minusSeconds(3600), null, hrCaller);

        EmployeeResult fin = employees.create(UUID.randomUUID(), "EMP-FIN", hrCaller);
        finEmployeePublicId = fin.employeeId();
        employees.addAssignment(finEmployeePublicId, UUID.randomUUID(), "FIN", null, "STAFF",
            NOW.minusSeconds(3600), null, hrCaller);

        EmployeeResult own = employees.create(UUID.randomUUID(), "EMP-OWN", hrCaller);
        ownEmployeePublicId = own.employeeId();
        Employee entity = employeeRepository.findByPublicId(ownEmployeePublicId).orElseThrow();
        entity.linkAccount(member.getId());
        employeeRepository.save(entity);
        bindingRepository.save(new AccountBinding(
            entity.getId(), ownEmployeePublicId, member.getId(), ISSUER, "emp-1", NOW));
    }

    @AfterEach
    void clearCaller() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void admissionRegistersContextCommands() {
        assertEquals(AdmissionGuard.Mode.EXCLUSIVE, admission.modeForCommand("SCHEDULE_PUBLISH"));
        assertEquals(AdmissionGuard.Mode.EXCLUSIVE, admission.modeForCommand("CALENDAR_PUBLISH"));
        assertEquals(AdmissionGuard.Mode.EXCLUSIVE, admission.modeForCommand("SCHEDULE_ASSIGN"));
        assertEquals(AdmissionGuard.Mode.EXCLUSIVE, admission.modeForCommand("CALENDAR_ASSIGN"));
        assertEquals(AdmissionGuard.Mode.SHARED, admission.modeForCommand("WORK_CONTEXT_QUERY"));
        assertEquals(AdmissionGuard.Mode.SHARED, admission.modeForCommand("OCCURRENCE_RESOLVE"));
    }

    @Test
    void publishSchedulePersistsWeekdays() {
        RevisionResult result = calendars.publishSchedule(
            new PublishSchedulePayload(UUID.randomUUID(), "Asia/Jakarta", weekdaySchedule()), hrCaller);
        assertEquals("PUBLISHED", result.status());
        assertNotNull(result.publishedAt());
    }

    @Test
    void publishScheduleRejectsDuplicateWeekday() {
        List<ScheduleDayPayload> days = List.of(
            new ScheduleDayPayload(1, LocalTime.of(9, 0), LocalTime.of(17, 0), 0),
            new ScheduleDayPayload(1, LocalTime.of(9, 0), LocalTime.of(17, 0), 0));
        assertThrows(IllegalArgumentException.class, () -> calendars.publishSchedule(
            new PublishSchedulePayload(UUID.randomUUID(), "Asia/Jakarta", days), hrCaller));
    }

    @Test
    void publishScheduleRejectsSameDayInvertedWindow() {
        List<ScheduleDayPayload> days =
            List.of(new ScheduleDayPayload(1, LocalTime.of(17, 0), LocalTime.of(9, 0), 0));
        assertThrows(IllegalArgumentException.class, () -> calendars.publishSchedule(
            new PublishSchedulePayload(UUID.randomUUID(), "Asia/Jakarta", days), hrCaller));
    }

    @Test
    void publishScheduleRejectsNonPilotTimezone() {
        assertThrows(IllegalArgumentException.class, () -> calendars.publishSchedule(
            new PublishSchedulePayload(UUID.randomUUID(), "Mars/Olympus", weekdaySchedule()), hrCaller));
        assertThrows(IllegalArgumentException.class, () -> calendars.publishSchedule(
            new PublishSchedulePayload(UUID.randomUUID(), "America/New_York", weekdaySchedule()), hrCaller));
    }

    @Test
    void publishScheduleRejectsOverlappingNeighborOccurrences() {
        List<ScheduleDayPayload> overlapping = List.of(
            new ScheduleDayPayload(1, LocalTime.of(22, 0), LocalTime.of(6, 0), 1),
            new ScheduleDayPayload(2, LocalTime.of(5, 0), LocalTime.of(13, 0), 0));
        assertThrows(IllegalArgumentException.class, () -> calendars.publishSchedule(
            new PublishSchedulePayload(UUID.randomUUID(), "Asia/Jakarta", overlapping), hrCaller));
        List<ScheduleDayPayload> sundaySpill = List.of(
            new ScheduleDayPayload(7, LocalTime.of(22, 0), LocalTime.of(2, 0), 1),
            new ScheduleDayPayload(1, LocalTime.of(1, 0), LocalTime.of(9, 0), 0));
        assertThrows(IllegalArgumentException.class, () -> calendars.publishSchedule(
            new PublishSchedulePayload(UUID.randomUUID(), "Asia/Jakarta", sundaySpill), hrCaller));
    }

    @Test
    void publishScheduleRejectsDuplicatePublicId() {
        UUID publicId = UUID.randomUUID();
        calendars.publishSchedule(
            new PublishSchedulePayload(publicId, "Asia/Jakarta", weekdaySchedule()), hrCaller);
        WorkContextException ex = assertThrows(WorkContextException.class, () -> calendars.publishSchedule(
            new PublishSchedulePayload(publicId, "Asia/Jakarta", weekdaySchedule()), hrCaller));
        assertEquals(WorkContextException.UNPUBLISHED_REVISION, ex.getReason());
    }

    @Test
    void publishRequiresOrganizationWideHr() {
        assertThrows(AccessDeniedException.class, () -> calendars.publishSchedule(
            new PublishSchedulePayload(UUID.randomUUID(), "Asia/Jakarta", weekdaySchedule()), scopedCaller));
        assertThrows(AccessDeniedException.class, () -> calendars.publishCalendar(
            new PublishCalendarPayload(UUID.randomUUID(), "JKT", List.of()), scopedCaller));
    }

    @Test
    void publishCalendarAcceptsExplicitEmptyHolidays() {
        RevisionResult result = calendars.publishCalendar(
            new PublishCalendarPayload(UUID.randomUUID(), "JKT", List.of()), hrCaller);
        assertEquals("PUBLISHED", result.status());
    }

    @Test
    void publishCalendarRejectsDuplicateHolidayDate() {
        List<HolidayPayload> holidays = List.of(
            new HolidayPayload(MONDAY, "Cutoff"),
            new HolidayPayload(MONDAY, "Cutoff again"));
        assertThrows(IllegalArgumentException.class, () -> calendars.publishCalendar(
            new PublishCalendarPayload(UUID.randomUUID(), "JKT", holidays), hrCaller));
    }

    @Test
    void queryResolvesMondayWithoutPinning() {
        UUID scheduleId = publishWeekdays();
        UUID calendarId = calendars.publishCalendar(
            new PublishCalendarPayload(UUID.randomUUID(), "JKT", List.of()), hrCaller).publicId();
        assignSchedule(employeePublicId, scheduleId, LocalDate.of(2026, 10, 8), scopedCaller);
        assignCalendar(employeePublicId, calendarId, LocalDate.of(2026, 10, 8), scopedCaller);

        OccurrenceListResult listed = calendars.queryOccurrences(employeePublicId,
            LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 15), hrCaller);
        assertEquals(7, listed.occurrences().size());
        OccurrenceView monday = listed.occurrences().stream()
            .filter(view -> MONDAY.equals(view.workDate())).findFirst().orElseThrow();
        assertEquals(Instant.parse("2026-10-12T02:00:00Z"), monday.startAt());
        assertEquals(Instant.parse("2026-10-12T10:00:00Z"), monday.endAt());
        assertFalse(monday.holiday());
        assertFalse(monday.pinned());
        assertNull(monday.blockedReason());
        assertEquals(0, jdbc.queryForObject("select count(*) from attendance_occurrence", Integer.class));
    }

    @Test
    void queryReportsBlockedReasonsPerDate() {
        UUID scheduleId = publishWeekdays();
        UUID calendarId = calendars.publishCalendar(
            new PublishCalendarPayload(UUID.randomUUID(), "JKT", List.of()), hrCaller).publicId();
        assignSchedule(employeePublicId, scheduleId, LocalDate.of(2026, 10, 8), scopedCaller);
        assignCalendar(employeePublicId, calendarId, LocalDate.of(2026, 10, 8), scopedCaller);

        OccurrenceListResult listed = calendars.queryOccurrences(employeePublicId,
            LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 15), hrCaller);
        assertEquals(7, listed.occurrences().size());
        OccurrenceView saturday = listed.occurrences().stream()
            .filter(view -> SATURDAY.equals(view.workDate())).findFirst().orElseThrow();
        assertFalse(saturday.pinned());
        assertEquals(WorkContextException.NON_WORKING_DATE, saturday.blockedReason());
        assertNull(saturday.startAt());
    }

    @Test
    void queryReportsMissingSchedulePerDate() {
        UUID calendarId = calendars.publishCalendar(
            new PublishCalendarPayload(UUID.randomUUID(), "JKT", List.of()), hrCaller).publicId();
        assignCalendar(employeePublicId, calendarId, LocalDate.of(2026, 10, 8), scopedCaller);

        OccurrenceListResult listed = calendars.queryOccurrences(employeePublicId,
            LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 13), hrCaller);
        assertEquals(1, listed.occurrences().size());
        assertEquals(WorkContextException.MISSING_SCHEDULE, listed.occurrences().get(0).blockedReason());
    }

    @Test
    void queryFlagsHolidayWithoutPinning() {
        UUID scheduleId = publishWeekdays();
        UUID calendarId = calendars.publishCalendar(new PublishCalendarPayload(UUID.randomUUID(),
            "JKT", List.of(new HolidayPayload(MONDAY, "Cuti Bersama"))), hrCaller).publicId();
        assignSchedule(employeePublicId, scheduleId, LocalDate.of(2026, 10, 8), scopedCaller);
        assignCalendar(employeePublicId, calendarId, LocalDate.of(2026, 10, 8), scopedCaller);

        OccurrenceListResult listed = calendars.queryOccurrences(employeePublicId,
            MONDAY, MONDAY.plusDays(1), hrCaller);
        assertEquals(1, listed.occurrences().size());
        assertTrue(listed.occurrences().get(0).holiday());
        assertEquals("Cuti Bersama", listed.occurrences().get(0).holidayName());
        assertFalse(listed.occurrences().get(0).pinned());
    }

    @Test
    void querySupportsOvernightStartDateAttribution() {
        UUID scheduleId = calendars.publishSchedule(new PublishSchedulePayload(UUID.randomUUID(),
            "Asia/Jakarta", overnightSunday()), hrCaller).publicId();
        UUID calendarId = calendars.publishCalendar(
            new PublishCalendarPayload(UUID.randomUUID(), "JKT", List.of()), hrCaller).publicId();
        assignSchedule(employeePublicId, scheduleId, LocalDate.of(2026, 10, 8), scopedCaller);
        assignCalendar(employeePublicId, calendarId, LocalDate.of(2026, 10, 8), scopedCaller);

        OccurrenceListResult listed = calendars.queryOccurrences(employeePublicId,
            SUNDAY, SUNDAY.plusDays(1), hrCaller);
        assertEquals(Instant.parse("2026-10-11T15:00:00Z"), listed.occurrences().get(0).startAt());
        assertEquals(Instant.parse("2026-10-11T23:00:00Z"), listed.occurrences().get(0).endAt());
    }

    @Test
    void pinnedEvidenceSurvivesReplacement() {
        UUID first = publishWeekdays();
        UUID calendarId = calendars.publishCalendar(
            new PublishCalendarPayload(UUID.randomUUID(), "JKT", List.of()), hrCaller).publicId();
        AssignmentResult assignment =
            assignSchedule(employeePublicId, first, LocalDate.of(2026, 10, 8), scopedCaller);
        assignCalendar(employeePublicId, calendarId, LocalDate.of(2026, 10, 8), scopedCaller);
        Long internalId = employeeRepository.findByPublicId(employeePublicId).orElseThrow().getId();
        AttendanceOccurrence pinned = resolver.resolveOrPin(internalId, MONDAY);

        UUID second = publishWeekdays();
        assignScheduleWithSupersedes(employeePublicId, second, LocalDate.of(2026, 10, 13),
            assignment.publicId(), "Shift pattern change", scopedCaller);

        OccurrenceListResult listed = calendars.queryOccurrences(employeePublicId,
            LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 15), hrCaller);
        OccurrenceView monday = listed.occurrences().stream()
            .filter(view -> MONDAY.equals(view.workDate())).findFirst().orElseThrow();
        assertTrue(monday.pinned());
        assertEquals(pinned.getPublicId(), monday.publicId());
        assertEquals(pinned.getStartAt(), monday.startAt());
        OccurrenceView october13 = listed.occurrences().stream()
            .filter(view -> LocalDate.of(2026, 10, 13).equals(view.workDate())).findFirst().orElseThrow();
        assertNull(october13.blockedReason());
    }

    @Test
    void replacementPreservesEarlierCoverage() {
        UUID first = publishWeekdays();
        UUID calendarId = calendars.publishCalendar(
            new PublishCalendarPayload(UUID.randomUUID(), "JKT", List.of()), hrCaller).publicId();
        AssignmentResult assignment =
            assignSchedule(employeePublicId, first, LocalDate.of(2026, 10, 8), scopedCaller);
        assignCalendar(employeePublicId, calendarId, LocalDate.of(2026, 10, 8), scopedCaller);
        UUID second = publishWeekdays();
        assignScheduleWithSupersedes(employeePublicId, second, LocalDate.of(2026, 10, 19),
            assignment.publicId(), "Pattern change", scopedCaller);

        OccurrenceListResult listed = calendars.queryOccurrences(employeePublicId,
            LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 13), hrCaller);
        assertNull(listed.occurrences().get(0).blockedReason());
        assertEquals(1, jdbc.queryForObject(
            "select count(*) from employee_schedule_assignment where superseded = true", Integer.class));
        assertEquals(1, jdbc.queryForObject(
            "select count(*) from employee_schedule_assignment where superseded = false "
                + "and from_date = '2026-10-08' and to_date = '2026-10-19'",
            Integer.class));
    }

    @Test
    void reviseMiddlePreservesSuffixCoverage() {
        UUID first = publishWeekdays();
        UUID calendarId = calendars.publishCalendar(
            new PublishCalendarPayload(UUID.randomUUID(), "JKT", List.of()), hrCaller).publicId();
        AssignmentResult assignment =
            assignSchedule(employeePublicId, first, LocalDate.of(2026, 10, 8), scopedCaller);
        assignCalendar(employeePublicId, calendarId, LocalDate.of(2026, 10, 8), scopedCaller);
        AssignmentResult replacement = calendars.assignSchedule(assignPayload(employeePublicId, first,
            LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 12), assignment.publicId(),
            "Temporary pattern", null), scopedCaller);
        assertEquals(assignment.publicId(),
            jdbc.queryForObject("select supersedes_public_id from employee_schedule_assignment "
                + "where public_id = '" + replacement.publicId() + "'", UUID.class));

        OccurrenceListResult listed = calendars.queryOccurrences(employeePublicId,
            LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 14), hrCaller);
        for (OccurrenceView view : listed.occurrences()) {
            if (view.workDate().getDayOfWeek().getValue() <= 5) {
                assertNull(view.blockedReason(), "Coverage lost on " + view.workDate());
            }
        }
    }

    @Test
    void reviseRejectsForeignSupersedesId() {
        UUID finSchedule = publishWeekdays();
        AssignmentResult victim = calendars.assignSchedule(assignPayload(finEmployeePublicId, finSchedule,
            LocalDate.of(2026, 10, 8), null, null, null, null), hrCaller);
        UUID scheduleId = publishWeekdays();
        assertThrows(ResourceNotFoundException.class, () -> calendars.assignSchedule(
            assignPayload(employeePublicId, scheduleId, LocalDate.of(2026, 10, 8), null,
                victim.publicId(), null, null), scopedCaller));
        assertEquals(0, jdbc.queryForObject(
            "select count(*) from employee_schedule_assignment where public_id = '"
                + victim.publicId() + "' and superseded = true",
            Integer.class));

        UUID finCalendar = calendars.publishCalendar(
            new PublishCalendarPayload(UUID.randomUUID(), "JKT", List.of()), hrCaller).publicId();
        AssignmentResult calendarVictim = calendars.assignCalendar(assignPayload(finEmployeePublicId,
            finCalendar, LocalDate.of(2026, 10, 8), null, null, null, null), hrCaller);
        UUID calendarId = calendars.publishCalendar(
            new PublishCalendarPayload(UUID.randomUUID(), "JKT", List.of()), hrCaller).publicId();
        assertThrows(ResourceNotFoundException.class, () -> calendars.assignCalendar(
            assignPayload(employeePublicId, calendarId, LocalDate.of(2026, 10, 8), null,
                calendarVictim.publicId(), null, null), scopedCaller));
        assertEquals(0, jdbc.queryForObject(
            "select count(*) from employee_calendar_assignment where public_id = '"
                + calendarVictim.publicId() + "' and superseded = true",
            Integer.class));
    }

    @Test
    void reviseRejectsAlreadyRevisedOriginal() {
        UUID scheduleId = publishWeekdays();
        AssignmentResult first =
            assignSchedule(employeePublicId, scheduleId, LocalDate.of(2026, 10, 8), scopedCaller);
        UUID second = publishWeekdays();
        assignScheduleWithSupersedes(employeePublicId, second, LocalDate.of(2026, 10, 13),
            first.publicId(), "First revise", scopedCaller);
        WorkContextException ex = assertThrows(WorkContextException.class,
            () -> calendars.assignSchedule(assignPayload(employeePublicId, second,
                LocalDate.of(2026, 10, 15), null, first.publicId(), "Stale revise", null), scopedCaller));
        assertEquals(WorkContextException.ASSIGNMENT_STALE, ex.getReason());
    }

    @Test
    void reviseRejectsStartOutsideOriginal() {
        UUID scheduleId = publishWeekdays();
        AssignmentResult first = calendars.assignSchedule(assignPayload(employeePublicId, scheduleId,
            LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 20), null, null, null), scopedCaller);
        assertThrows(IllegalArgumentException.class, () -> calendars.assignSchedule(
            assignPayload(employeePublicId, scheduleId, LocalDate.of(2026, 10, 20), null,
                first.publicId(), null, null), scopedCaller));
    }

    @Test
    void reviseStoresBackdateReasonOnNewRow() {
        UUID scheduleId = publishWeekdays();
        AssignmentResult result = calendars.assignSchedule(assignPayload(employeePublicId, scheduleId,
            LocalDate.of(2026, 10, 1), null, null, "Fix missing context", null), scopedCaller);
        assertEquals("Fix missing context", jdbc.queryForObject(
            "select backdate_reason from employee_schedule_assignment where public_id = '"
                + result.publicId() + "'",
            String.class));
    }

    @Test
    void assignRejectsOverlappingInterval() {
        UUID scheduleId = publishWeekdays();
        assignSchedule(employeePublicId, scheduleId, LocalDate.of(2026, 10, 8), scopedCaller);
        WorkContextException ex = assertThrows(WorkContextException.class,
            () -> calendars.assignSchedule(assignPayload(employeePublicId, scheduleId,
                LocalDate.of(2026, 10, 10), null, null, null, null), scopedCaller));
        assertEquals(WorkContextException.OVERLAPPING_SCHEDULE, ex.getReason());
    }

    @Test
    void assignRejectsBackdateWithoutReason() {
        UUID scheduleId = publishWeekdays();
        assertThrows(IllegalArgumentException.class,
            () -> calendars.assignSchedule(assignPayload(employeePublicId, scheduleId,
                LocalDate.of(2026, 10, 1), null, null, null, null), scopedCaller));
        AssignmentResult result = calendars.assignSchedule(assignPayload(employeePublicId,
            scheduleId, LocalDate.of(2026, 10, 1), null, null, "Fix missing context", null), scopedCaller);
        assertEquals(LocalDate.of(2026, 10, 1), result.fromDate());
    }

    @Test
    void assignRejectsUnknownRevision() {
        assertThrows(ResourceNotFoundException.class,
            () -> calendars.assignSchedule(assignPayload(employeePublicId, UUID.randomUUID(),
                LocalDate.of(2026, 10, 8), null, null, null, null), scopedCaller));
    }

    @Test
    void assignRejectsDraftRevision() {
        UUID draftId = UUID.randomUUID();
        scheduleRevisionRepository.save(new id.mydev.peoplecore.attendance.domain.model.WorkScheduleRevision(
            draftId, "Asia/Jakarta", NOW));
        WorkContextException ex = assertThrows(WorkContextException.class,
            () -> calendars.assignSchedule(assignPayload(employeePublicId, draftId,
                LocalDate.of(2026, 10, 8), null, null, null, null), scopedCaller));
        assertEquals(WorkContextException.UNPUBLISHED_REVISION, ex.getReason());
    }

    @Test
    void assignCalendarRejectsDraftRevision() {
        UUID draftId = UUID.randomUUID();
        calendarRevisionRepository.save(
            new id.mydev.peoplecore.attendance.domain.model.HolidayCalendarRevision(
                draftId, "JKT", NOW));
        WorkContextException ex = assertThrows(WorkContextException.class,
            () -> calendars.assignCalendar(assignPayload(employeePublicId, draftId,
                LocalDate.of(2026, 10, 8), null, null, null, null), scopedCaller));
        assertEquals(WorkContextException.UNPUBLISHED_REVISION, ex.getReason());
    }

    @Test
    void publishCalendarRejectsBlankHolidayName() {
        assertThrows(IllegalArgumentException.class, () -> calendars.publishCalendar(
            new PublishCalendarPayload(UUID.randomUUID(), "JKT",
                List.of(new HolidayPayload(MONDAY, " "))), hrCaller));
    }

    @Test
    void queryRejectsInvertedRange() {
        assertThrows(IllegalArgumentException.class, () -> calendars.queryOccurrences(employeePublicId,
            LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 8), hrCaller));
    }

    @Test
    void previewRejectsWideRangeAndTooManyEmployees() {
        assertThrows(IllegalArgumentException.class, () -> calendars.previewImpact(new PreviewPayload(
            null, null, LocalDate.of(2026, 1, 1), LocalDate.of(2027, 6, 1),
            List.of(employeePublicId), null, null), hrCaller));
        List<UUID> tooMany = new java.util.ArrayList<>();
        for (int index = 0; index < 501; index++) {
            tooMany.add(UUID.randomUUID());
        }
        assertThrows(IllegalArgumentException.class, () -> calendars.previewImpact(new PreviewPayload(
            null, null, LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 15), tooMany, null, null), hrCaller));
    }

    @Test
    void resolveOrPinTwiceReturnsSameEvidence() {
        UUID scheduleId = publishWeekdays();
        UUID calendarId = calendars.publishCalendar(
            new PublishCalendarPayload(UUID.randomUUID(), "JKT", List.of()), hrCaller).publicId();
        assignSchedule(employeePublicId, scheduleId, LocalDate.of(2026, 10, 8), scopedCaller);
        assignCalendar(employeePublicId, calendarId, LocalDate.of(2026, 10, 8), scopedCaller);
        Long internalId = employeeRepository.findByPublicId(employeePublicId).orElseThrow().getId();
        AttendanceOccurrence first = resolver.resolveOrPin(internalId, MONDAY);
        AttendanceOccurrence second = resolver.resolveOrPin(internalId, MONDAY);
        assertEquals(first.getPublicId(), second.getPublicId());
    }

    @Test
    void queryReportsSeededOverlappingSchedules() {
        UUID scheduleId = publishWeekdays();
        UUID calendarId = calendars.publishCalendar(
            new PublishCalendarPayload(UUID.randomUUID(), "JKT", List.of()), hrCaller).publicId();
        assignSchedule(employeePublicId, scheduleId, LocalDate.of(2026, 10, 8), scopedCaller);
        assignCalendar(employeePublicId, calendarId, LocalDate.of(2026, 10, 8), scopedCaller);
        Long internalId = employeeRepository.findByPublicId(employeePublicId).orElseThrow().getId();
        Long revisionId = scheduleRevisionRepository.findByPublicId(scheduleId).orElseThrow().getId();
        scheduleAssignmentRepository.save(
            new id.mydev.peoplecore.attendance.domain.model.EmployeeScheduleAssignment(UUID.randomUUID(),
                internalId, revisionId, LocalDate.of(2026, 10, 12), null, NOW, null, null));

        OccurrenceListResult listed = calendars.queryOccurrences(employeePublicId,
            MONDAY, MONDAY.plusDays(1), hrCaller);
        assertEquals(WorkContextException.OVERLAPPING_SCHEDULE, listed.occurrences().get(0).blockedReason());
    }

    @Test
    void queryReportsSeededOverlappingCalendars() {
        UUID scheduleId = publishWeekdays();
        UUID calendarId = calendars.publishCalendar(
            new PublishCalendarPayload(UUID.randomUUID(), "JKT", List.of()), hrCaller).publicId();
        assignSchedule(employeePublicId, scheduleId, LocalDate.of(2026, 10, 8), scopedCaller);
        assignCalendar(employeePublicId, calendarId, LocalDate.of(2026, 10, 8), scopedCaller);
        Long internalId = employeeRepository.findByPublicId(employeePublicId).orElseThrow().getId();
        Long revisionId = calendarRevisionRepository.findByPublicId(calendarId).orElseThrow().getId();
        calendarAssignmentRepository.save(
            new id.mydev.peoplecore.attendance.domain.model.EmployeeCalendarAssignment(UUID.randomUUID(),
                internalId, revisionId, LocalDate.of(2026, 10, 12), null, NOW, null, null));

        OccurrenceListResult listed = calendars.queryOccurrences(employeePublicId,
            MONDAY, MONDAY.plusDays(1), hrCaller);
        assertEquals(WorkContextException.OVERLAPPING_CALENDAR, listed.occurrences().get(0).blockedReason());
    }

    @Test
    void queryReportsSeededUnpublishedRevision() {
        UUID draftId = UUID.randomUUID();
        scheduleRevisionRepository.save(new id.mydev.peoplecore.attendance.domain.model.WorkScheduleRevision(
            draftId, "Asia/Jakarta", NOW));
        Long draftInternalId = scheduleRevisionRepository.findByPublicId(draftId).orElseThrow().getId();
        UUID calendarId = calendars.publishCalendar(
            new PublishCalendarPayload(UUID.randomUUID(), "JKT", List.of()), hrCaller).publicId();
        assignCalendar(employeePublicId, calendarId, LocalDate.of(2026, 10, 8), scopedCaller);
        Long internalId = employeeRepository.findByPublicId(employeePublicId).orElseThrow().getId();
        scheduleAssignmentRepository.save(
            new id.mydev.peoplecore.attendance.domain.model.EmployeeScheduleAssignment(UUID.randomUUID(),
                internalId, draftInternalId, LocalDate.of(2026, 10, 8), null, NOW, null, null));

        OccurrenceListResult listed = calendars.queryOccurrences(employeePublicId,
            MONDAY, MONDAY.plusDays(1), hrCaller);
        assertEquals(WorkContextException.UNPUBLISHED_REVISION, listed.occurrences().get(0).blockedReason());
    }

    @Test
    void queryReportsSeededInvalidTimezone() {
        id.mydev.peoplecore.attendance.domain.model.WorkScheduleRevision revision =
            new id.mydev.peoplecore.attendance.domain.model.WorkScheduleRevision(
                UUID.randomUUID(), "Mars/Olympus", NOW);
        scheduleRevisionRepository.save(revision);
        revision.publish(NOW);
        scheduleRevisionRepository.save(revision);
        UUID calendarId = calendars.publishCalendar(
            new PublishCalendarPayload(UUID.randomUUID(), "JKT", List.of()), hrCaller).publicId();
        assignCalendar(employeePublicId, calendarId, LocalDate.of(2026, 10, 8), scopedCaller);
        Long internalId = employeeRepository.findByPublicId(employeePublicId).orElseThrow().getId();
        scheduleAssignmentRepository.save(
            new id.mydev.peoplecore.attendance.domain.model.EmployeeScheduleAssignment(UUID.randomUUID(),
                internalId, revision.getId(), LocalDate.of(2026, 10, 8), null, NOW, null, null));

        OccurrenceListResult listed = calendars.queryOccurrences(employeePublicId,
            MONDAY, MONDAY.plusDays(1), hrCaller);
        assertEquals(WorkContextException.MISSING_SCHEDULE, listed.occurrences().get(0).blockedReason());
    }

    @Test
    void assignSkipsBoundaryCheckForMissingPatternEdge() {
        UUID scheduleId = publishWeekdays();
        UUID calendarId = calendars.publishCalendar(
            new PublishCalendarPayload(UUID.randomUUID(), "JKT", List.of()), hrCaller).publicId();
        calendars.assignSchedule(assignPayload(employeePublicId, scheduleId,
            LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 13), null, null, null), scopedCaller);
        assignCalendar(employeePublicId, calendarId, LocalDate.of(2026, 10, 8), scopedCaller);
        AssignmentResult next = calendars.assignSchedule(assignPayload(employeePublicId, scheduleId,
            LocalDate.of(2026, 10, 13), null, null, null, null), scopedCaller);
        assertEquals(LocalDate.of(2026, 10, 13), next.fromDate());
    }

    @Test
    void assignSkipsBoundaryCheckForUnpublishedNeighbor() {
        EmployeeResult neighbor = employees.create(UUID.randomUUID(), "EMP-NEIGHBOR", hrCaller);
        UUID neighborPublicId = neighbor.employeeId();
        employees.addAssignment(neighborPublicId, UUID.randomUUID(), "ENG", null, "STAFF",
            NOW.minusSeconds(3600), null, hrCaller);
        UUID draftId = UUID.randomUUID();
        scheduleRevisionRepository.save(new id.mydev.peoplecore.attendance.domain.model.WorkScheduleRevision(
            draftId, "Asia/Jakarta", NOW));
        Long draftInternalId = scheduleRevisionRepository.findByPublicId(draftId).orElseThrow().getId();
        Long internalId = employeeRepository.findByPublicId(neighborPublicId).orElseThrow().getId();
        scheduleAssignmentRepository.save(
            new id.mydev.peoplecore.attendance.domain.model.EmployeeScheduleAssignment(UUID.randomUUID(),
                internalId, draftInternalId, LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 13),
                NOW, null, null));
        UUID scheduleId = publishWeekdays();
        AssignmentResult next = calendars.assignSchedule(assignPayload(neighborPublicId, scheduleId,
            LocalDate.of(2026, 10, 13), null, null, null, null), scopedCaller);
        assertEquals(LocalDate.of(2026, 10, 13), next.fromDate());
    }

    @Test
    void reviseStartingAtOriginalStartSkipsPrefix() {
        UUID scheduleId = publishWeekdays();
        AssignmentResult original = calendars.assignSchedule(assignPayload(employeePublicId, scheduleId,
            LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 20), null, null, null), scopedCaller);
        assertEquals(1, jdbc.queryForObject(
            "select count(*) from employee_schedule_assignment where superseded = false", Integer.class));
        AssignmentResult revised = calendars.assignSchedule(assignPayload(employeePublicId, scheduleId,
            LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 20), original.publicId(), null, null),
            scopedCaller);
        assertNotNull(revised.publicId());
        assertEquals(1, jdbc.queryForObject(
            "select count(*) from employee_schedule_assignment where superseded = false "
                + "and from_date = '2026-10-08'",
            Integer.class));
    }

    @Test
    void reviseRejectsStartBeforeOriginal() {
        UUID scheduleId = publishWeekdays();
        AssignmentResult original = calendars.assignSchedule(assignPayload(employeePublicId, scheduleId,
            LocalDate.of(2026, 10, 8), null, null, null, null), scopedCaller);
        assertThrows(IllegalArgumentException.class, () -> calendars.assignSchedule(
            assignPayload(employeePublicId, scheduleId, LocalDate.of(2026, 10, 1),
                null, original.publicId(), "Too early", null), scopedCaller));
    }

    @Test
    void reviseCalendarRejectsStartOutsideOriginal() {
        UUID calendarId = calendars.publishCalendar(
            new PublishCalendarPayload(UUID.randomUUID(), "JKT", List.of()), hrCaller).publicId();
        AssignmentResult original = calendars.assignCalendar(assignPayload(employeePublicId,
            calendarId, LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 20), null, null, null),
            scopedCaller);
        assertThrows(IllegalArgumentException.class, () -> calendars.assignCalendar(
            assignPayload(employeePublicId, calendarId, LocalDate.of(2026, 10, 20), null,
                original.publicId(), null, null), scopedCaller));
    }

    @Test
    void reviseCalendarBeyondOriginalEndSkipsSuffix() {
        UUID calendarId = calendars.publishCalendar(
            new PublishCalendarPayload(UUID.randomUUID(), "JKT", List.of()), hrCaller).publicId();
        AssignmentResult original = calendars.assignCalendar(assignPayload(employeePublicId,
            calendarId, LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 15), null, null, null),
            scopedCaller);
        AssignmentResult revised = calendars.assignCalendar(assignPayload(employeePublicId,
            calendarId, LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 20), original.publicId(),
            "Extended", null), scopedCaller);
        assertNotNull(revised.publicId());
        assertEquals(0, jdbc.queryForObject(
            "select count(*) from employee_calendar_assignment where superseded = false "
                + "and from_date = '2026-10-20'",
            Integer.class));
    }

    @Test
    void assignRejectsBoundaryOverlap() {
        UUID overnight = calendars.publishSchedule(new PublishSchedulePayload(UUID.randomUUID(),
            "Asia/Jakarta", List.of(
                new ScheduleDayPayload(1, LocalTime.of(22, 0), LocalTime.of(6, 0), 1))),
            hrCaller).publicId();
        UUID calendarId = calendars.publishCalendar(
            new PublishCalendarPayload(UUID.randomUUID(), "JKT", List.of()), hrCaller).publicId();
        calendars.assignSchedule(assignPayload(employeePublicId, overnight,
            LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 13), null, null, null), scopedCaller);
        calendars.assignCalendar(assignPayload(employeePublicId, calendarId,
            LocalDate.of(2026, 10, 8), null, null, null, null), scopedCaller);
        UUID early = calendars.publishSchedule(new PublishSchedulePayload(UUID.randomUUID(),
            "Asia/Jakarta", List.of(
                new ScheduleDayPayload(2, LocalTime.of(5, 0), LocalTime.of(13, 0), 0))),
            hrCaller).publicId();
        assertThrows(IllegalArgumentException.class, () -> calendars.assignSchedule(
            assignPayload(employeePublicId, early, LocalDate.of(2026, 10, 13), null, null, null, null),
            scopedCaller));
    }

    @Test
    void assignEnforcesExpectedFingerprint() {
        UUID scheduleId = publishWeekdays();
        UUID calendarId = calendars.publishCalendar(
            new PublishCalendarPayload(UUID.randomUUID(), "JKT", List.of()), hrCaller).publicId();
        AssignmentResult original = assignSchedule(employeePublicId, scheduleId,
            LocalDate.of(2026, 10, 8), scopedCaller);
        assignCalendar(employeePublicId, calendarId, LocalDate.of(2026, 10, 8), scopedCaller);

        ImpactPreview preview = calendars.previewImpact(new PreviewPayload(scheduleId, null,
            LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 10), List.of(employeePublicId),
            original.publicId(), null), scopedCaller);
        AssignmentResult revised = calendars.assignSchedule(assignPayload(employeePublicId, scheduleId,
            LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 10), original.publicId(), null,
            preview.fingerprint()), scopedCaller);
        assertNotNull(revised.publicId());

        assertThrows(ContextChangedException.class, () -> calendars.assignSchedule(
            assignPayload(employeePublicId, scheduleId, LocalDate.of(2026, 10, 8),
                LocalDate.of(2026, 10, 10), original.publicId(), null, preview.fingerprint()),
            scopedCaller));
    }

    @Test
    void assignRejectsMissingFingerprint() {
        UUID scheduleId = publishWeekdays();
        assertThrows(IllegalArgumentException.class, () -> calendars.assignSchedule(
            new AssignmentPayload(UUID.randomUUID(), employeePublicId, scheduleId,
                LocalDate.of(2026, 10, 8), null, null, null, null), scopedCaller));
    }

    @Test
    void assignRejectsBlankFingerprint() {
        UUID scheduleId = publishWeekdays();
        assertThrows(IllegalArgumentException.class, () -> calendars.assignSchedule(
            new AssignmentPayload(UUID.randomUUID(), employeePublicId, scheduleId,
                LocalDate.of(2026, 10, 8), null, null, null, " "), scopedCaller));
    }

    @Test
    void assignBindsFingerprintToRevisionLineage() {
        UUID scheduleId = publishWeekdays();
        UUID calendarId = calendars.publishCalendar(
            new PublishCalendarPayload(UUID.randomUUID(), "JKT", List.of()), hrCaller).publicId();
        AssignmentResult original = assignSchedule(employeePublicId, scheduleId,
            LocalDate.of(2026, 10, 8), scopedCaller);
        assignCalendar(employeePublicId, calendarId, LocalDate.of(2026, 10, 8), scopedCaller);

        ImpactPreview withoutLineage = calendars.previewImpact(new PreviewPayload(scheduleId, null,
            LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 10), List.of(employeePublicId),
            null, null), scopedCaller);
        assertThrows(ContextChangedException.class, () -> calendars.assignSchedule(
            assignPayload(employeePublicId, scheduleId, LocalDate.of(2026, 10, 8),
                LocalDate.of(2026, 10, 10), original.publicId(), null, withoutLineage.fingerprint()),
            scopedCaller));
    }

    @Test
    void reviseRejectsOverlapWithPinnedOccurrence() {
        UUID overnight = calendars.publishSchedule(new PublishSchedulePayload(UUID.randomUUID(),
            "Asia/Jakarta", List.of(
                new ScheduleDayPayload(1, LocalTime.of(22, 0), LocalTime.of(6, 0), 1),
                new ScheduleDayPayload(2, LocalTime.of(6, 0), LocalTime.of(14, 0), 0))),
            hrCaller).publicId();
        UUID calendarId = calendars.publishCalendar(
            new PublishCalendarPayload(UUID.randomUUID(), "JKT", List.of()), hrCaller).publicId();
        AssignmentResult original = assignSchedule(employeePublicId, overnight,
            LocalDate.of(2026, 10, 8), scopedCaller);
        assignCalendar(employeePublicId, calendarId, LocalDate.of(2026, 10, 8), scopedCaller);
        Long internalId = employeeRepository.findByPublicId(employeePublicId).orElseThrow().getId();
        AttendanceOccurrence pin = resolver.resolveOrPin(internalId, MONDAY);

        UUID shifted = calendars.publishSchedule(new PublishSchedulePayload(UUID.randomUUID(),
            "Asia/Jakarta", List.of(
                new ScheduleDayPayload(1, LocalTime.of(14, 0), LocalTime.of(22, 0), 0),
                new ScheduleDayPayload(2, LocalTime.of(5, 0), LocalTime.of(13, 0), 0))),
            hrCaller).publicId();
        WorkContextException ex = assertThrows(WorkContextException.class, () -> calendars.assignSchedule(
            assignPayload(employeePublicId, shifted, LocalDate.of(2026, 10, 8),
                null, original.publicId(), "Shift pattern", null), scopedCaller));
        assertEquals(WorkContextException.OVERLAPPING_SCHEDULE, ex.getReason());
        assertEquals(pin.getPublicId(), resolver.resolveOrPin(internalId, MONDAY).getPublicId());
    }

    @Test
    void reviseAcceptsPinnedOccurrenceOnTouchingBoundary() {
        UUID pattern = calendars.publishSchedule(new PublishSchedulePayload(UUID.randomUUID(),
            "Asia/Jakarta", List.of(
                new ScheduleDayPayload(1, LocalTime.of(22, 0), LocalTime.of(6, 0), 1),
                new ScheduleDayPayload(2, LocalTime.of(6, 0), LocalTime.of(14, 0), 0))),
            hrCaller).publicId();
        UUID calendarId = calendars.publishCalendar(
            new PublishCalendarPayload(UUID.randomUUID(), "JKT", List.of()), hrCaller).publicId();
        AssignmentResult original = assignSchedule(employeePublicId, pattern,
            LocalDate.of(2026, 10, 8), scopedCaller);
        assignCalendar(employeePublicId, calendarId, LocalDate.of(2026, 10, 8), scopedCaller);
        Long internalId = employeeRepository.findByPublicId(employeePublicId).orElseThrow().getId();
        resolver.resolveOrPin(internalId, MONDAY);

        UUID touching = calendars.publishSchedule(new PublishSchedulePayload(UUID.randomUUID(),
            "Asia/Jakarta", List.of(
                new ScheduleDayPayload(1, LocalTime.of(14, 0), LocalTime.of(22, 0), 0),
                new ScheduleDayPayload(2, LocalTime.of(6, 0), LocalTime.of(14, 0), 0))),
            hrCaller).publicId();
        AssignmentResult revised = calendars.assignSchedule(assignPayload(employeePublicId, touching,
            LocalDate.of(2026, 10, 8), null, original.publicId(), "Shift pattern", null), scopedCaller);
        assertNotNull(revised.publicId());
    }

    @Test
    void reviseRejectsOverlapWithPinnedOccurrenceBeyondFirstYear() {
        UUID overnight = publishOvernightMonday();
        UUID original = assignSchedule(employeePublicId, overnight, LocalDate.of(2026, 10, 8), scopedCaller)
            .publicId();
        assignCalendar(employeePublicId, publishEmptyCalendar(), LocalDate.of(2026, 10, 8), scopedCaller);
        Long internalId = employeeRepository.findByPublicId(employeePublicId).orElseThrow().getId();
        LocalDate farMonday = MONDAY.plusWeeks(60);
        resolver.resolveOrPin(internalId, farMonday);

        UUID shifted = calendars.publishSchedule(new PublishSchedulePayload(UUID.randomUUID(),
            "Asia/Jakarta", List.of(
                new ScheduleDayPayload(1, LocalTime.of(14, 0), LocalTime.of(22, 0), 0),
                new ScheduleDayPayload(2, LocalTime.of(5, 0), LocalTime.of(13, 0), 0))),
            hrCaller).publicId();
        WorkContextException ex = assertThrows(WorkContextException.class, () -> calendars.assignSchedule(
            assignPayload(employeePublicId, shifted, LocalDate.of(2026, 10, 8), null, original,
                "Shift pattern", null), scopedCaller));
        assertEquals(WorkContextException.OVERLAPPING_SCHEDULE, ex.getReason());
    }

    @Test
    void reviseRejectsHistoricalStartOverlapWithDistantPinnedOccurrence() {
        UUID overnight = publishOvernightMonday();
        UUID original = calendars.assignSchedule(assignPayload(employeePublicId, overnight,
            LocalDate.of(2024, 1, 1), null, null, "Backdated", null), scopedCaller).publicId();
        UUID calendarId = publishEmptyCalendar();
        calendars.assignCalendar(assignPayload(employeePublicId, calendarId,
            LocalDate.of(2024, 1, 1), null, null, "Backdated", null), scopedCaller);
        Long internalId = employeeRepository.findByPublicId(employeePublicId).orElseThrow().getId();
        resolver.resolveOrPin(internalId, MONDAY);

        UUID shifted = calendars.publishSchedule(new PublishSchedulePayload(UUID.randomUUID(),
            "Asia/Jakarta", List.of(
                new ScheduleDayPayload(1, LocalTime.of(14, 0), LocalTime.of(22, 0), 0),
                new ScheduleDayPayload(2, LocalTime.of(5, 0), LocalTime.of(13, 0), 0))),
            hrCaller).publicId();
        WorkContextException ex = assertThrows(WorkContextException.class, () -> calendars.assignSchedule(
            assignPayload(employeePublicId, shifted, LocalDate.of(2024, 1, 1), null, original,
                "Shift pattern", null), scopedCaller));
        assertEquals(WorkContextException.OVERLAPPING_SCHEDULE, ex.getReason());
    }

    @Test
    void reviseAcceptsShiftedPatternWhenPinnedNeighborOverridesIt() {
        UUID overnight = publishOvernightMonday();
        UUID original = assignSchedule(employeePublicId, overnight, LocalDate.of(2026, 10, 8), scopedCaller)
            .publicId();
        assignCalendar(employeePublicId, publishEmptyCalendar(), LocalDate.of(2026, 10, 8), scopedCaller);
        Long internalId = employeeRepository.findByPublicId(employeePublicId).orElseThrow().getId();
        AttendanceOccurrence mondayPin = resolver.resolveOrPin(internalId, MONDAY);
        AttendanceOccurrence tuesdayPin = resolver.resolveOrPin(internalId, MONDAY.plusDays(1));

        UUID shifted = calendars.publishSchedule(new PublishSchedulePayload(UUID.randomUUID(),
            "Asia/Jakarta", List.of(
                new ScheduleDayPayload(1, LocalTime.of(14, 0), LocalTime.of(22, 0), 0),
                new ScheduleDayPayload(2, LocalTime.of(5, 0), LocalTime.of(13, 0), 0))),
            hrCaller).publicId();
        AssignmentResult revised = calendars.assignSchedule(assignPayload(employeePublicId, shifted,
            LocalDate.of(2026, 10, 8), null, original, "Shift pattern", null), scopedCaller);
        assertNotNull(revised.publicId());
        assertEquals(mondayPin.getPublicId(), resolver.resolveOrPin(internalId, MONDAY).getPublicId());
        assertEquals(tuesdayPin.getPublicId(),
            resolver.resolveOrPin(internalId, MONDAY.plusDays(1)).getPublicId());
    }

    @Test
    void reviseWithRetainedPrefixAcceptsPinnedBoundary() {
        UUID overnight = publishOvernightMonday();
        UUID original = assignSchedule(employeePublicId, overnight, LocalDate.of(2026, 10, 8), scopedCaller)
            .publicId();
        assignCalendar(employeePublicId, publishEmptyCalendar(), LocalDate.of(2026, 10, 8), scopedCaller);
        Long internalId = employeeRepository.findByPublicId(employeePublicId).orElseThrow().getId();
        AttendanceOccurrence mondayPin = resolver.resolveOrPin(internalId, MONDAY);
        AttendanceOccurrence tuesdayPin = resolver.resolveOrPin(internalId, MONDAY.plusDays(1));

        UUID shifted = calendars.publishSchedule(new PublishSchedulePayload(UUID.randomUUID(),
            "Asia/Jakarta", List.of(
                new ScheduleDayPayload(1, LocalTime.of(14, 0), LocalTime.of(22, 0), 0),
                new ScheduleDayPayload(2, LocalTime.of(5, 0), LocalTime.of(13, 0), 0))),
            hrCaller).publicId();
        AssignmentResult revised = calendars.assignSchedule(assignPayload(employeePublicId, shifted,
            MONDAY.plusDays(1), null, original, "Shift pattern", null), scopedCaller);
        assertEquals(MONDAY.plusDays(1), revised.fromDate());
        assertEquals(mondayPin.getPublicId(), resolver.resolveOrPin(internalId, MONDAY).getPublicId());
        assertEquals(tuesdayPin.getPublicId(),
            resolver.resolveOrPin(internalId, MONDAY.plusDays(1)).getPublicId());
    }

    @Test
    void reviseWithRetainedSuffixAcceptsPinnedBoundary() {
        UUID adjacent = calendars.publishSchedule(new PublishSchedulePayload(UUID.randomUUID(),
            "Asia/Jakarta", List.of(
                new ScheduleDayPayload(2, LocalTime.of(6, 0), LocalTime.of(14, 0), 0),
                new ScheduleDayPayload(3, LocalTime.of(5, 0), LocalTime.of(13, 0), 0))),
            hrCaller).publicId();
        UUID original = assignSchedule(employeePublicId, adjacent, LocalDate.of(2026, 10, 8), scopedCaller)
            .publicId();
        assignCalendar(employeePublicId, publishEmptyCalendar(), LocalDate.of(2026, 10, 8), scopedCaller);
        Long internalId = employeeRepository.findByPublicId(employeePublicId).orElseThrow().getId();
        AttendanceOccurrence tuesdayPin = resolver.resolveOrPin(internalId, MONDAY.plusDays(1));
        AttendanceOccurrence wednesdayPin = resolver.resolveOrPin(internalId, MONDAY.plusDays(2));

        UUID spill = calendars.publishSchedule(new PublishSchedulePayload(UUID.randomUUID(),
            "Asia/Jakarta", List.of(
                new ScheduleDayPayload(2, LocalTime.of(22, 0), LocalTime.of(6, 0), 1))),
            hrCaller).publicId();
        AssignmentResult revised = calendars.assignSchedule(assignPayload(employeePublicId, spill,
            LocalDate.of(2026, 10, 8), MONDAY.plusDays(2), original, "Shift pattern", null), scopedCaller);
        assertEquals(LocalDate.of(2026, 10, 8), revised.fromDate());
        assertEquals(MONDAY.plusDays(2), revised.toDate());
        assertEquals(tuesdayPin.getPublicId(),
            resolver.resolveOrPin(internalId, MONDAY.plusDays(1)).getPublicId());
        assertEquals(wednesdayPin.getPublicId(),
            resolver.resolveOrPin(internalId, MONDAY.plusDays(2)).getPublicId());
    }

    @Test
    void assignAdjacentUsesCanonicalBoundaryOverNeighborPattern() {
        UUID daytime = calendars.publishSchedule(new PublishSchedulePayload(UUID.randomUUID(),
            "Asia/Jakarta", List.of(
                new ScheduleDayPayload(1, LocalTime.of(9, 0), LocalTime.of(17, 0), 0))),
            hrCaller).publicId();
        UUID original = calendars.assignSchedule(assignPayload(employeePublicId, daytime,
            LocalDate.of(2026, 10, 8), MONDAY.plusDays(1), null, null, null), scopedCaller).publicId();
        assignCalendar(employeePublicId, publishEmptyCalendar(), LocalDate.of(2026, 10, 8), scopedCaller);
        Long internalId = employeeRepository.findByPublicId(employeePublicId).orElseThrow().getId();
        AttendanceOccurrence mondayPin = resolver.resolveOrPin(internalId, MONDAY);

        UUID overnight = calendars.publishSchedule(new PublishSchedulePayload(UUID.randomUUID(),
            "Asia/Jakarta", List.of(
                new ScheduleDayPayload(1, LocalTime.of(22, 0), LocalTime.of(6, 0), 1))),
            hrCaller).publicId();
        AssignmentResult neighbor = calendars.assignSchedule(assignPayload(employeePublicId, overnight,
            LocalDate.of(2026, 10, 8), MONDAY.plusDays(1), original, "Switch to overnight", null),
            scopedCaller);
        assertEquals(MONDAY.plusDays(1), neighbor.toDate());

        UUID early = calendars.publishSchedule(new PublishSchedulePayload(UUID.randomUUID(),
            "Asia/Jakarta", List.of(
                new ScheduleDayPayload(2, LocalTime.of(5, 0), LocalTime.of(13, 0), 0))),
            hrCaller).publicId();
        AssignmentResult adjacent = assignSchedule(employeePublicId, early, MONDAY.plusDays(1), scopedCaller);
        assertEquals(MONDAY.plusDays(1), adjacent.fromDate());
        assertEquals(mondayPin.getPublicId(), resolver.resolveOrPin(internalId, MONDAY).getPublicId());
    }

    private UUID publishOvernightMonday() {
        return calendars.publishSchedule(new PublishSchedulePayload(UUID.randomUUID(),
            "Asia/Jakarta", List.of(
                new ScheduleDayPayload(1, LocalTime.of(22, 0), LocalTime.of(6, 0), 1),
                new ScheduleDayPayload(2, LocalTime.of(6, 0), LocalTime.of(14, 0), 0))),
            hrCaller).publicId();
    }

    private UUID publishEmptyCalendar() {
        return calendars.publishCalendar(
            new PublishCalendarPayload(UUID.randomUUID(), "JKT", List.of()), hrCaller).publicId();
    }

    @Test
    void publishCalendarRejectsDuplicatePublicId() {
        UUID publicId = UUID.randomUUID();
        calendars.publishCalendar(
            new PublishCalendarPayload(publicId, "JKT", List.of()), hrCaller);
        WorkContextException ex = assertThrows(WorkContextException.class, () -> calendars.publishCalendar(
            new PublishCalendarPayload(publicId, "JKT", List.of()), hrCaller));
        assertEquals(WorkContextException.UNPUBLISHED_REVISION, ex.getReason());
    }

    @Test
    void previewRejectsUnknownRevision() {
        assertThrows(ResourceNotFoundException.class, () -> calendars.previewImpact(new PreviewPayload(
            UUID.randomUUID(), null, LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 15),
            List.of(employeePublicId), null, null), hrCaller));
        assertThrows(ResourceNotFoundException.class, () -> calendars.previewImpact(new PreviewPayload(
            null, UUID.randomUUID(), LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 15),
            List.of(employeePublicId), null, null), hrCaller));
    }

    @Test
    void previewCountsPinnedEvidence() {
        UUID scheduleId = publishWeekdays();
        UUID calendarId = calendars.publishCalendar(
            new PublishCalendarPayload(UUID.randomUUID(), "JKT", List.of()), hrCaller).publicId();
        assignSchedule(employeePublicId, scheduleId, LocalDate.of(2026, 10, 8), scopedCaller);
        assignCalendar(employeePublicId, calendarId, LocalDate.of(2026, 10, 8), scopedCaller);
        Long internalId = employeeRepository.findByPublicId(employeePublicId).orElseThrow().getId();
        resolver.resolveOrPin(internalId, MONDAY);
        ImpactPreview preview = calendars.previewImpact(new PreviewPayload(scheduleId, calendarId,
            LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 15), List.of(employeePublicId),
            null, null), hrCaller);
        assertEquals(1L, preview.pinnedOccurrences());
    }

    @Test
    void assignAcceptsAdjacentNonOverlappingBoundary() {
        UUID scheduleId = publishWeekdays();
        UUID calendarId = calendars.publishCalendar(
            new PublishCalendarPayload(UUID.randomUUID(), "JKT", List.of()), hrCaller).publicId();
        calendars.assignSchedule(assignPayload(employeePublicId, scheduleId,
            LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 13), null, null, null), scopedCaller);
        assignCalendar(employeePublicId, calendarId, LocalDate.of(2026, 10, 8), scopedCaller);
        AssignmentResult next = calendars.assignSchedule(assignPayload(employeePublicId, scheduleId,
            LocalDate.of(2026, 10, 13), null, null, null, null), scopedCaller);
        assertEquals(LocalDate.of(2026, 10, 13), next.fromDate());
        assertNull(next.toDate());
    }

    @Test
    void reviseCalendarPreservesSuffixAndRejectsStaleOriginal() {
        UUID calendarId = calendars.publishCalendar(
            new PublishCalendarPayload(UUID.randomUUID(), "JKT", List.of()), hrCaller).publicId();
        AssignmentResult first = calendars.assignCalendar(assignPayload(employeePublicId,
            calendarId, LocalDate.of(2026, 10, 8), null, null, null, null), scopedCaller);
        AssignmentResult revised = calendars.assignCalendar(assignPayload(employeePublicId,
            calendarId, LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 12), first.publicId(),
            "Temporary calendar", null), scopedCaller);
        assertEquals(1, jdbc.queryForObject(
            "select count(*) from employee_calendar_assignment where superseded = false "
                + "and from_date = '2026-10-08' and to_date = '2026-10-10'",
            Integer.class));
        assertEquals(1, jdbc.queryForObject(
            "select count(*) from employee_calendar_assignment where superseded = false "
                + "and from_date = '2026-10-12' and to_date is null",
            Integer.class));
        assertEquals(revised.publicId(), jdbc.queryForObject(
            "select public_id from employee_calendar_assignment where superseded = false "
                + "and from_date = '2026-10-10'",
            UUID.class));
        WorkContextException ex = assertThrows(WorkContextException.class,
            () -> calendars.assignCalendar(assignPayload(employeePublicId, calendarId,
                LocalDate.of(2026, 10, 13), null, first.publicId(), "Stale revise", null), scopedCaller));
        assertEquals(WorkContextException.ASSIGNMENT_STALE, ex.getReason());
    }

    @Test
    void previewReturnsFingerprintAndDetectsStaleContext() {
        UUID scheduleId = publishWeekdays();
        UUID calendarId = calendars.publishCalendar(
            new PublishCalendarPayload(UUID.randomUUID(), "JKT", List.of()), hrCaller).publicId();
        calendars.assignSchedule(assignPayload(employeePublicId, scheduleId,
            LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 10), null, null, null), scopedCaller);
        PreviewPayload payload = new PreviewPayload(scheduleId, calendarId,
            LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 15), List.of(employeePublicId),
            null, null);
        ImpactPreview preview = calendars.previewImpact(payload, hrCaller);
        assertNotNull(preview.fingerprint());
        assertEquals(7L, preview.affectedDates());
        assertEquals(0L, preview.pinnedOccurrences());

        calendars.assignSchedule(assignPayload(employeePublicId, scheduleId,
            LocalDate.of(2026, 10, 12), null, null, null, null), scopedCaller);
        assertThrows(ContextChangedException.class, () -> calendars.previewImpact(new PreviewPayload(
            scheduleId, calendarId, LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 15),
            List.of(employeePublicId), null, preview.fingerprint()), hrCaller));
    }

    @Test
    void previewAcceptsMatchingFingerprint() {
        UUID scheduleId = publishWeekdays();
        UUID calendarId = calendars.publishCalendar(
            new PublishCalendarPayload(UUID.randomUUID(), "JKT", List.of()), hrCaller).publicId();
        assignSchedule(employeePublicId, scheduleId, LocalDate.of(2026, 10, 8), scopedCaller);
        assignCalendar(employeePublicId, calendarId, LocalDate.of(2026, 10, 8), scopedCaller);
        PreviewPayload payload = new PreviewPayload(scheduleId, calendarId,
            LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 15), List.of(employeePublicId),
            null, null);
        ImpactPreview preview = calendars.previewImpact(payload, hrCaller);
        ImpactPreview replay = calendars.previewImpact(new PreviewPayload(scheduleId, calendarId,
            LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 15),
            List.of(employeePublicId), null, preview.fingerprint()), hrCaller);
        assertEquals(preview.fingerprint(), replay.fingerprint());
    }

    @Test
    void previewRejectsUnknownEmployees() {
        assertThrows(ResourceNotFoundException.class, () -> calendars.previewImpact(new PreviewPayload(
            null, null, LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 15),
            List.of(UUID.randomUUID()), null, null), hrCaller));
        assertThrows(IllegalArgumentException.class, () -> calendars.previewImpact(new PreviewPayload(
            null, null, LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 15), List.of(), null, null),
            hrCaller));
        assertThrows(IllegalArgumentException.class, () -> calendars.previewImpact(new PreviewPayload(
            null, null, LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 8),
            List.of(employeePublicId), null, null), hrCaller));
    }

    @Test
    void previewAllowsScopedHr() {
        PreviewPayload payload = new PreviewPayload(null, null,
            LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 15), List.of(employeePublicId),
            null, null);
        ImpactPreview preview = calendars.previewImpact(payload, scopedCaller);
        assertNotNull(preview.fingerprint());
    }

    @Test
    void previewRejectsTargetOutsideScope() {
        assertThrows(ResourceNotFoundException.class, () -> calendars.previewImpact(new PreviewPayload(
            null, null, LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 15),
            List.of(finEmployeePublicId), null, null), scopedCaller));
        assertThrows(ResourceNotFoundException.class, () -> calendars.previewImpact(new PreviewPayload(
            null, null, LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 15),
            List.of(employeePublicId, finEmployeePublicId), null, null), scopedCaller));
    }

    @Test
    void memberReadsOwnWorkContext() {
        UUID scheduleId = publishWeekdays();
        UUID calendarId = calendars.publishCalendar(
            new PublishCalendarPayload(UUID.randomUUID(), "JKT", List.of()), hrCaller).publicId();
        calendars.assignSchedule(assignPayload(ownEmployeePublicId, scheduleId,
            LocalDate.of(2026, 10, 8), null, null, null, null), hrCaller);
        calendars.assignCalendar(assignPayload(ownEmployeePublicId, calendarId,
            LocalDate.of(2026, 10, 8), null, null, null, null), hrCaller);
        OccurrenceListResult listed = calendars.queryOccurrences(ownEmployeePublicId,
            LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 15), memberCaller);
        assertEquals(7, listed.occurrences().size());
        OccurrenceView monday = listed.occurrences().stream()
            .filter(view -> MONDAY.equals(view.workDate())).findFirst().orElseThrow();
        assertFalse(monday.pinned());
        assertEquals(Instant.parse("2026-10-12T02:00:00Z"), monday.startAt());
    }

    @Test
    void scopedHrReadsCoveredWorkContext() {
        UUID scheduleId = publishWeekdays();
        UUID calendarId = calendars.publishCalendar(
            new PublishCalendarPayload(UUID.randomUUID(), "JKT", List.of()), hrCaller).publicId();
        assignSchedule(employeePublicId, scheduleId, LocalDate.of(2026, 10, 8), scopedCaller);
        assignCalendar(employeePublicId, calendarId, LocalDate.of(2026, 10, 8), scopedCaller);
        OccurrenceListResult listed = calendars.queryOccurrences(employeePublicId,
            LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 15), scopedCaller);
        assertEquals(7, listed.occurrences().size());
    }

    @Test
    void revokedHrCannotAssignAfterAuthorityLoss() {
        UUID scheduleId = publishWeekdays();
        for (RoleAssignment role : roleRepository.findByAccountId(scopedAccountId)) {
            role.endAt(NOW.minusSeconds(1));
            roleRepository.save(role);
        }
        assertThrows(AccessDeniedException.class, () -> calendars.assignSchedule(
            assignPayload(employeePublicId, scheduleId, LocalDate.of(2026, 10, 8), null, null, null, null),
            scopedCaller));
    }

    @Test
    void queryOccurrencesHidesUnrelatedAndUnknownEmployees() {
        assertThrows(ResourceNotFoundException.class, () -> calendars.queryOccurrences(
            UUID.randomUUID(), LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 15), memberCaller));
        assertThrows(ResourceNotFoundException.class, () -> calendars.queryOccurrences(employeePublicId,
            LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 15), memberCaller));
    }

    @Test
    void revokedOwnerRoleCannotReadOwnWorkContext() {
        UUID scheduleId = publishWeekdays();
        UUID calendarId = calendars.publishCalendar(
            new PublishCalendarPayload(UUID.randomUUID(), "JKT", List.of()), hrCaller).publicId();
        calendars.assignSchedule(assignPayload(ownEmployeePublicId, scheduleId,
            LocalDate.of(2026, 10, 8), null, null, null, null), hrCaller);
        calendars.assignCalendar(assignPayload(ownEmployeePublicId, calendarId,
            LocalDate.of(2026, 10, 8), null, null, null, null), hrCaller);
        UUID own = ownEmployeePublicId;
        OccurrenceListResult granted = calendars.queryOccurrences(own,
            LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 15), memberCaller);
        assertEquals(7, granted.occurrences().size());

        Long memberAccountId = accountRepository
            .findByOidcIssuerAndOidcSubject(ISSUER, "emp-1").orElseThrow().getId();
        for (RoleAssignment role : roleRepository.findByAccountId(memberAccountId)) {
            role.endAt(NOW.minusSeconds(1));
            roleRepository.saveAndFlush(role);
        }
        assertThrows(ResourceNotFoundException.class, () -> calendars.queryOccurrences(own,
            LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 15), memberCaller));
    }

    @Test
    void queryOccurrencesRejectsWideRange() {
        assertThrows(IllegalArgumentException.class, () -> calendars.queryOccurrences(employeePublicId,
            LocalDate.of(2026, 1, 1), LocalDate.of(2027, 6, 1), hrCaller));
    }

    @Test
    void queryRechecksAccountCutoffBeforeReturningContext() {
        UUID scheduleId = publishWeekdays();
        UUID calendarId = calendars.publishCalendar(
            new PublishCalendarPayload(UUID.randomUUID(), "JKT", List.of()), hrCaller).publicId();
        assignSchedule(employeePublicId, scheduleId, LocalDate.of(2026, 10, 8), scopedCaller);
        assignCalendar(employeePublicId, calendarId, LocalDate.of(2026, 10, 8), scopedCaller);
        UserAccount account = accountRepository.findById(hrAccountId).orElseThrow();
        account.applyCutoff(NOW.plusSeconds(1), LocalTime.NOON, "Asia/Jakarta", NOW);
        accountRepository.saveAndFlush(account);

        WorkCalendarService stepping = serviceWithClock(steppingClock());
        assertThrows(AccessDeniedException.class, () -> stepping.queryOccurrences(employeePublicId,
            LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 15), hrCaller));
    }

    @Test
    void queryRechecksRoleExpiryBeforeReturningContext() {
        UUID scheduleId = publishWeekdays();
        UUID calendarId = calendars.publishCalendar(
            new PublishCalendarPayload(UUID.randomUUID(), "JKT", List.of()), hrCaller).publicId();
        assignSchedule(employeePublicId, scheduleId, LocalDate.of(2026, 10, 8), scopedCaller);
        assignCalendar(employeePublicId, calendarId, LocalDate.of(2026, 10, 8), scopedCaller);
        for (RoleAssignment role : roleRepository.findByAccountId(scopedAccountId)) {
            role.endAt(NOW.plusSeconds(1));
            roleRepository.saveAndFlush(role);
        }

        WorkCalendarService stepping = serviceWithClock(steppingClock());
        assertThrows(ResourceNotFoundException.class, () -> stepping.queryOccurrences(employeePublicId,
            LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 15), scopedCaller));
    }

    @Test
    void previewRechecksAccountCutoffBeforeReturningImpact() {
        UserAccount account = accountRepository.findById(hrAccountId).orElseThrow();
        account.applyCutoff(NOW.plusSeconds(1), LocalTime.NOON, "Asia/Jakarta", NOW);
        accountRepository.saveAndFlush(account);

        WorkCalendarService stepping = serviceWithClock(steppingClock());
        assertThrows(AccessDeniedException.class, () -> stepping.previewImpact(new PreviewPayload(
            null, null, LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 15),
            List.of(employeePublicId), null, null), hrCaller));
    }

    private WorkCalendarService serviceWithClock(java.time.Clock clock) {
        return new WorkCalendarService(scheduleRevisionRepository, scheduleDayRepository,
            calendarRevisionRepository, holidayDateRepository, scheduleAssignmentRepository,
            calendarAssignmentRepository, occurrenceRepository, accountGuard, resolver, employees,
            access, admission, entityManager, clock);
    }

    private static StepClock steppingClock() {
        return new StepClock(NOW, NOW.plusSeconds(2));
    }

    private static final class StepClock extends java.time.Clock {
        private final Instant first;
        private final Instant later;
        private int calls;

        private StepClock(Instant first, Instant later) {
            this.first = first;
            this.later = later;
        }

        @Override
        public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public java.time.Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return calls++ == 0 ? first : later;
        }
    }

    @Test
    void publishScheduleRejectsEmptyOrOversizedDays() {
        assertThrows(IllegalArgumentException.class, () -> calendars.publishSchedule(
            new PublishSchedulePayload(UUID.randomUUID(), "Asia/Jakarta", List.of()), hrCaller));
        List<ScheduleDayPayload> eight = new java.util.ArrayList<>(weekdaySchedule());
        eight.add(new ScheduleDayPayload(6, LocalTime.of(9, 0), LocalTime.of(17, 0), 0));
        eight.add(new ScheduleDayPayload(7, LocalTime.of(9, 0), LocalTime.of(17, 0), 0));
        eight.add(new ScheduleDayPayload(1, LocalTime.of(9, 0), LocalTime.of(17, 0), 0));
        List<ScheduleDayPayload> tooMany = List.copyOf(eight);
        assertThrows(IllegalArgumentException.class, () -> calendars.publishSchedule(
            new PublishSchedulePayload(UUID.randomUUID(), "Asia/Jakarta", tooMany), hrCaller));
    }

    @Test
    void assignRejectsInvertedInterval() {
        UUID scheduleId = publishWeekdays();
        assertThrows(IllegalArgumentException.class,
            () -> calendars.assignSchedule(assignPayload(employeePublicId, scheduleId,
                LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 15), null, null, null), scopedCaller));
    }

    @Test
    void assignCalendarRejectsOverlapAndSupportsRevise() {
        UUID calendarId = calendars.publishCalendar(
            new PublishCalendarPayload(UUID.randomUUID(), "JKT", List.of()), hrCaller).publicId();
        AssignmentResult first = calendars.assignCalendar(assignPayload(employeePublicId,
            calendarId, LocalDate.of(2026, 10, 8), null, null, null, null), scopedCaller);
        WorkContextException ex = assertThrows(WorkContextException.class,
            () -> calendars.assignCalendar(assignPayload(employeePublicId, calendarId,
                LocalDate.of(2026, 10, 10), null, null, null, null), scopedCaller));
        assertEquals(WorkContextException.OVERLAPPING_CALENDAR, ex.getReason());
        AssignmentResult revised = calendars.assignCalendar(assignPayload(employeePublicId,
            calendarId, LocalDate.of(2026, 10, 13), null, first.publicId(), "Location change", null),
            scopedCaller);
        assertEquals(LocalDate.of(2026, 10, 13), revised.fromDate());
        assertEquals(first.publicId(), jdbc.queryForObject(
            "select supersedes_public_id from employee_calendar_assignment where public_id = '"
                + revised.publicId() + "'",
            UUID.class));
    }

    private AssignmentPayload assignPayload(UUID employeeId, UUID revisionId, LocalDate fromDate,
                                            LocalDate toDate, UUID supersedesId, String reason,
                                            String fingerprint) {
        String expected = fingerprint != null ? fingerprint
            : fingerprintFor(employeeId, revisionId, fromDate, toDate, supersedesId);
        return new AssignmentPayload(UUID.randomUUID(), employeeId, revisionId, fromDate, toDate,
            supersedesId, reason, expected);
    }

    private String fingerprintFor(UUID employeeId, UUID revisionId, LocalDate fromDate,
                                  LocalDate toDate, UUID supersedesId) {
        entityManager.flush();
        LocalDate effectiveTo = toDate != null ? toDate : fromDate.plusDays(366);
        boolean schedule = scheduleRevisionRepository.findByPublicId(revisionId).isPresent();
        return calendars.previewImpact(new PreviewPayload(
            schedule ? revisionId : null, schedule ? null : revisionId,
            fromDate, effectiveTo, List.of(employeeId), supersedesId, null), hrCaller).fingerprint();
    }

    private AssignmentResult assignSchedule(UUID employeeId, UUID revisionId, LocalDate fromDate,
                                            Authentication caller) {
        return calendars.assignSchedule(
            assignPayload(employeeId, revisionId, fromDate, null, null, null, null), caller);
    }

    private void assignCalendar(UUID employeeId, UUID revisionId, LocalDate fromDate, Authentication caller) {
        calendars.assignCalendar(
            assignPayload(employeeId, revisionId, fromDate, null, null, null, null), caller);
    }

    private AssignmentResult assignScheduleWithSupersedes(UUID employeeId, UUID revisionId, LocalDate fromDate,
                                                          UUID supersedesId, String reason,
                                                          Authentication caller) {
        return calendars.assignSchedule(
            assignPayload(employeeId, revisionId, fromDate, null, supersedesId, reason, null), caller);
    }

    private UUID publishWeekdays() {
        return calendars.publishSchedule(
            new PublishSchedulePayload(UUID.randomUUID(), "Asia/Jakarta", weekdaySchedule()), hrCaller)
            .publicId();
    }

    private static List<ScheduleDayPayload> weekdaySchedule() {
        return List.of(
            new ScheduleDayPayload(1, LocalTime.of(9, 0), LocalTime.of(17, 0), 0),
            new ScheduleDayPayload(2, LocalTime.of(9, 0), LocalTime.of(17, 0), 0),
            new ScheduleDayPayload(3, LocalTime.of(9, 0), LocalTime.of(17, 0), 0),
            new ScheduleDayPayload(4, LocalTime.of(9, 0), LocalTime.of(17, 0), 0),
            new ScheduleDayPayload(5, LocalTime.of(9, 0), LocalTime.of(17, 0), 0));
    }

    private static List<ScheduleDayPayload> overnightSunday() {
        return List.of(
            new ScheduleDayPayload(7, LocalTime.of(22, 0), LocalTime.of(6, 0), 1));
    }
}
