package id.mydev.peoplecore.attendance;

import id.mydev.peoplecore.attendance.application.command.WorkCalendarCommands;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarPayloads.AssignmentPayload;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarPayloads.HolidayPayload;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarPayloads.PreviewPayload;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarPayloads.PublishCalendarPayload;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarPayloads.PublishSchedulePayload;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarPayloads.ScheduleDayPayload;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarResults.AssignmentResult;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarResults.OccurrenceView;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarResults.RevisionResult;
import id.mydev.peoplecore.attendance.application.service.AttendanceOccurrenceResolver;
import id.mydev.peoplecore.attendance.application.service.WorkCalendarService;
import id.mydev.peoplecore.attendance.domain.exception.ContextChangedException;
import id.mydev.peoplecore.common.command.CommandExecutionService;
import id.mydev.peoplecore.common.command.CommandConflictException;
import id.mydev.peoplecore.identity.domain.model.HrisRole;
import id.mydev.peoplecore.identity.domain.model.RoleAssignment;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.identity.domain.repository.RoleAssignmentRepository;
import id.mydev.peoplecore.identity.domain.repository.UserAccountRepository;
import id.mydev.peoplecore.organization.application.command.EmployeeResults.EmployeeResult;
import id.mydev.peoplecore.organization.application.service.EmployeeService;
import id.mydev.peoplecore.support.JwtAuthFixture;
import id.mydev.peoplecore.support.PostgresFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@Tag("postgres")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "peoplecore.runtime.role=api", "spring.jpa.hibernate.ddl-auto=validate",
    "peoplecore.organization.allowed-units=ENG,FIN"
})
class WorkCalendarPostgresTest {

    private static final String ISSUER = "https://attendance.postgres.test";
    private static final PostgresFixture DATABASE = new PostgresFixture();

    static {
        DATABASE.flyway().migrate();
        DATABASE.grantRuntime();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DATABASE.url);
        registry.add("spring.datasource.username", () -> DATABASE.runtimeUsername);
        registry.add("spring.datasource.password", () -> DATABASE.runtimePassword);
    }

    @Autowired PlatformTransactionManager transactions;
    @Autowired WorkCalendarService calendars;
    @Autowired WorkCalendarCommands calendarCommands;
    @Autowired AttendanceOccurrenceResolver resolver;
    @Autowired EmployeeService employees;
    @Autowired UserAccountRepository accounts;
    @Autowired RoleAssignmentRepository roles;
    @Autowired JdbcTemplate jdbc;

    private Authentication hr;
    private Authentication scopedHr;
    private Long orgWideHrAccountId;
    private Long scopedHrAccountId;
    private UUID employeePublicId;
    private UUID scheduleId;
    private UUID calendarId;
    private Instant start;

    @BeforeEach
    void setup() {
        start = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(3600);
        String hrSubject = "hr-" + UUID.randomUUID();
        UserAccount hrAccount = accounts.save(new UserAccount(UUID.randomUUID(), ISSUER, hrSubject, start));
        orgWideHrAccountId = hrAccount.getId();
        roles.save(new RoleAssignment(hrAccount.getId(), HrisRole.HR_ADMIN, null, start, start));
        hr = JwtAuthFixture.token(ISSUER, hrSubject);

        String scopedSubject = "hr-eng-" + UUID.randomUUID();
        UserAccount scopedAccount =
            accounts.save(new UserAccount(UUID.randomUUID(), ISSUER, scopedSubject, start));
        scopedHrAccountId = scopedAccount.getId();
        roles.save(new RoleAssignment(scopedHrAccountId, HrisRole.HR_ADMIN, "ENG", start, start));
        scopedHr = JwtAuthFixture.token(ISSUER, scopedSubject);

        EmployeeResult target = employees.create(UUID.randomUUID(), "EMP-" + UUID.randomUUID(), hr);
        employeePublicId = target.employeeId();
        employees.addAssignment(employeePublicId, UUID.randomUUID(), "ENG", null, "STAFF",
            start.minusSeconds(3600), null, hr);

        scheduleId = calendars.publishSchedule(new PublishSchedulePayload(UUID.randomUUID(),
            "Asia/Jakarta", weekdaySchedule()), hr).publicId();
        calendarId = calendars.publishCalendar(
            new PublishCalendarPayload(UUID.randomUUID(), "JKT", List.of()), hr).publicId();
    }

    @AfterEach
    void clearCaller() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void migrationDeliversAttendanceConstraints() {
        Integer tables = DATABASE.jdbc.queryForObject(
            "select count(*) from information_schema.tables where table_schema = current_schema() "
                + "and table_name in ('work_schedule_revision','work_schedule_day',"
                + "'holiday_calendar_revision','holiday_date','employee_schedule_assignment',"
                + "'employee_calendar_assignment','attendance_account','attendance_occurrence')",
            Integer.class);
        assertEquals(8, tables);
        Integer uniqueWeekday = DATABASE.jdbc.queryForObject(
            "select count(*) from pg_indexes where schemaname = current_schema() "
                + "and indexname = 'uk_schedule_day_revision_weekday'",
            Integer.class);
        assertEquals(1, uniqueWeekday);
        Integer uniqueOccurrence = DATABASE.jdbc.queryForObject(
            "select count(*) from pg_indexes where schemaname = current_schema() "
                + "and indexname = 'uk_occurrence_employee_date'",
            Integer.class);
        assertEquals(1, uniqueOccurrence);
    }

    @Test
    void runtimeCannotMutateSealedScheduleContent() {
        calendars.assignSchedule(assignPayload(employeePublicId, scheduleId,
            LocalDate.now().plusDays(1), null, null, null, null), scopedHr);
        try {
            jdbc.execute("update work_schedule_day set overnight_offset = 1 where revision_id = "
                + "(select id from work_schedule_revision where public_id = '" + scheduleId + "') "
                + "and weekday = 1");
            fail("Runtime role must not mutate published schedule content");
        } catch (Exception ex) {
            assertTrue(ex.getMessage().contains("immutable")
                || (ex.getCause() != null && ex.getCause().getMessage().contains("immutable")));
        }
    }

    @Test
    void runtimeCannotMutatePinnedOccurrence() {        LocalDate monday = nextWeekday(1);
        UUID holidayCalendar = calendars.publishCalendar(new PublishCalendarPayload(UUID.randomUUID(),
            "JKT", List.of(new HolidayPayload(monday, "Cuti Bersama"))), hr).publicId();
        calendars.assignSchedule(assignPayload(employeePublicId, scheduleId,
            LocalDate.now().plusDays(1), null, null, null, null), scopedHr);
        calendars.assignCalendar(assignPayload(employeePublicId, holidayCalendar,
            LocalDate.now().plusDays(1), null, null, null, null), scopedHr);
        Long internalId = DATABASE.jdbc.queryForObject(
            "select id from employees where public_id = '" + employeePublicId + "'", Long.class);
        OccurrenceView occurrence = inNewTransaction(() -> {
            var pinned = resolver.resolveOrPin(internalId, monday);
            return new OccurrenceView(pinned.getPublicId(), employeePublicId, monday,
                pinned.getStartAt(), pinned.getEndAt(), pinned.getTimezone(), true, "Cuti Bersama",
                true, null);
        });
        assertNotNull(occurrence.publicId());
        try {
            jdbc.execute("delete from attendance_occurrence where public_id = '"
                + occurrence.publicId() + "'");
            fail("Runtime role must not delete pinned occurrences");
        } catch (Exception ex) {
            assertTrue(ex.getMessage().contains("append-only")
                || (ex.getCause() != null && ex.getCause().getMessage().contains("append-only")));
        }
        try {
            jdbc.execute("delete from holiday_date where revision_id = "
                + "(select id from holiday_calendar_revision where public_id = '" + holidayCalendar + "')");
            fail("Runtime role must not delete published holiday rows");
        } catch (Exception ex) {
            assertTrue(ex.getMessage().contains("immutable")
                || (ex.getCause() != null && ex.getCause().getMessage().contains("immutable")));
        }
    }

    @Test
    void assignmentAndPinRunUnderProvisionedAttendanceGrants() {
        try {
            applyProvisionedAttendanceGrants();
            AssignmentResult assignment = calendars.assignSchedule(assignPayload(employeePublicId, scheduleId,
                LocalDate.now().plusDays(1), null, null, null, null), scopedHr);
            AssignmentResult calendarAssignment = calendars.assignCalendar(assignPayload(employeePublicId, calendarId,
                LocalDate.now().plusDays(1), null, null, null, null), scopedHr);
            Long internalId = DATABASE.jdbc.queryForObject(
                "select id from employees where public_id = '" + employeePublicId + "'", Long.class);
            UUID pinned = inNewTransaction(() -> resolver.resolveOrPin(internalId, nextWeekday(1))
                .getPublicId());
            assertNotNull(pinned);
            try {
                jdbc.execute("update attendance_occurrence set timezone = 'UTC' where public_id = '"
                    + pinned + "'");
                fail("Runtime role must not update pinned occurrences");
            } catch (Exception ex) {
                assertTrue(isAccessDenied(ex), "Unexpected error: " + ex.getMessage());
            }
            try {
                jdbc.execute("delete from attendance_account");
                fail("Runtime role must not delete attendance accounts");
            } catch (Exception ex) {
                assertTrue(isAccessDenied(ex), "Unexpected error: " + ex.getMessage());
            }
            try {
                jdbc.execute("update employee_schedule_assignment set from_date = from_date + 1 "
                    + "where public_id = '" + assignment.publicId() + "'");
                fail("Runtime role must not rewrite assignment provenance");
            } catch (Exception ex) {
                assertTrue(isAccessDenied(ex), "Unexpected error: " + ex.getMessage());
            }
            for (String table : List.of("employee_schedule_assignment", "employee_calendar_assignment")) {
                UUID target = table.equals("employee_schedule_assignment") ? assignment.publicId() : calendarAssignment.publicId();
                for (String mutation : List.of("employee_id=employee_id+1", "revision_id=revision_id+1",
                    "public_id=gen_random_uuid()", "to_date=from_date+1", "supersedes_public_id=gen_random_uuid()",
                    "created_at=created_at+interval '1 second'", "backdate_reason='rewritten'",
                    "superseded=true,revision_id=revision_id+1")) {
                    var denied = org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class,
                        () -> jdbc.update("update " + table + " set " + mutation + " where public_id=?", target));
                    assertTrue(isAccessDenied(denied));
                }
            }
            AssignmentResult revised = calendars.assignSchedule(assignPayload(employeePublicId, scheduleId,
                LocalDate.now().plusDays(5), null, assignment.publicId(), "revise", null), scopedHr);
            assertNotNull(revised.publicId());
            var denied = org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class,
                () -> jdbc.update("update employee_schedule_assignment set superseded=false where public_id=?", assignment.publicId()));
            assertTrue(isAccessDenied(denied));

        } finally {
            DATABASE.jdbc.execute("grant select, insert, update, delete on work_schedule_revision, "
                + "holiday_calendar_revision, work_schedule_day, holiday_date, attendance_account, "
                + "attendance_occurrence, employee_schedule_assignment, employee_calendar_assignment to "
                + DATABASE.runtimeUsername);
        }
    }

    private void applyProvisionedAttendanceGrants() {
        String runtime = DATABASE.runtimeUsername;
        DATABASE.jdbc.execute("revoke all on work_schedule_revision, holiday_calendar_revision, "
            + "work_schedule_day, holiday_date, attendance_account, attendance_occurrence, "
            + "employee_schedule_assignment, employee_calendar_assignment from " + runtime);
        DATABASE.jdbc.execute("grant select, insert, update on work_schedule_revision, "
            + "holiday_calendar_revision, employee_schedule_assignment, employee_calendar_assignment to "
            + runtime);
        DATABASE.jdbc.execute("grant select, insert, update on attendance_account to " + runtime);
        DATABASE.jdbc.execute("grant select, insert on work_schedule_day, holiday_date, "
            + "attendance_occurrence to " + runtime);
    }

    private static boolean isAccessDenied(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && (message.contains("permission denied")
                || message.contains("append-only") || message.contains("immutable"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    @Test
    void failingOutboxRollsBackPublishedContextReceiptAndAudit() {
        String key = "publish-rollback-" + UUID.randomUUID();
        UUID expectedId = id.mydev.peoplecore.common.command.CommandIds.derive("SCHEDULE_PUBLISH", hr.getName(), key);
        DATABASE.jdbc.execute("create function fail_context_outbox() returns trigger language plpgsql as $$ begin "
            + "if NEW.aggregate_public_id = '" + expectedId + "'::uuid then raise exception 'Injected outbox failure'; end if; return NEW; end $$");
        DATABASE.jdbc.execute("create trigger fail_context_outbox before insert on outbox_events "
            + "for each row execute function fail_context_outbox()");
        try {
            org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> as(hr, () -> calendarCommands.publishSchedule(hr, key,
                    new PublishSchedulePayload(null, "Asia/Jakarta", weekdaySchedule()))));
            assertEquals(0, DATABASE.jdbc.queryForObject(
                "select count(*) from work_schedule_revision where public_id=?", Integer.class, expectedId));
            assertEquals(0, DATABASE.jdbc.queryForObject(
                "select count(*) from audit_events where aggregate_public_id=?", Integer.class, expectedId));
            assertEquals(0, DATABASE.jdbc.queryForObject(
                "select count(*) from outbox_events where aggregate_public_id=?", Integer.class, expectedId));
            assertEquals(0, DATABASE.jdbc.queryForObject(
                "select count(*) from command_receipts where idempotency_key=?", Integer.class, key));
        } finally {
            DATABASE.jdbc.execute("drop trigger fail_context_outbox on outbox_events");
            DATABASE.jdbc.execute("drop function fail_context_outbox()");
        }
    }

    @Test
    void commandPublishReplayReturnsRecordedOutcomeWithTrail() {
        PublishSchedulePayload first =
            new PublishSchedulePayload(null, "Asia/Jakarta", weekdaySchedule());
        RevisionResult published = as(hr,
            () -> calendarCommands.publishSchedule(hr, "schedule-key-1", first));
        RevisionResult replayed = as(hr,
            () -> calendarCommands.publishSchedule(hr, "schedule-key-1", first));
        assertEquals(published.publicId(), replayed.publicId());
        assertEquals(1, DATABASE.jdbc.queryForObject(
            "select count(*) from work_schedule_revision where public_id = '" + published.publicId() + "'",
            Integer.class));
        assertEquals(1, DATABASE.jdbc.queryForObject(
            "select count(*) from audit_events where action = 'SCHEDULE_PUBLISH' "
                + "and aggregate_public_id = '" + published.publicId() + "'",
            Integer.class));
        assertEquals(1, DATABASE.jdbc.queryForObject(
            "select count(*) from outbox_events where event_type = 'SCHEDULE_PUBLISH' "
                + "and aggregate_public_id = '" + published.publicId() + "'",
            Integer.class));
        PublishSchedulePayload changed =
            new PublishSchedulePayload(null, "Asia/Jakarta", List.of(
                new ScheduleDayPayload(7, LocalTime.of(22, 0), LocalTime.of(6, 0), 1)));
        try {
            as(hr, () -> calendarCommands.publishSchedule(hr, "schedule-key-1", changed));
            fail("Changed payload with the same key must conflict");
        } catch (CommandConflictException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    void commandAssignReplayReturnsRecordedOutcomeWithTrail() {
        AssignmentPayload first = assignPayload(employeePublicId, scheduleId,
            LocalDate.now().plusDays(1), null, null, null, null);
        AssignmentResult assigned = as(scopedHr,
            () -> calendarCommands.assignSchedule(scopedHr, "assign-key-1", first));
        AssignmentResult replayed = as(scopedHr,
            () -> calendarCommands.assignSchedule(scopedHr, "assign-key-1", first));
        assertEquals(assigned.publicId(), replayed.publicId());
        assertEquals(1, DATABASE.jdbc.queryForObject(
            "select count(*) from audit_events where action = 'SCHEDULE_ASSIGN' "
                + "and aggregate_public_id = '" + assigned.publicId() + "'",
            Integer.class));
        assertEquals(1, DATABASE.jdbc.queryForObject(
            "select count(*) from outbox_events where event_type = 'SCHEDULE_ASSIGN' "
                + "and aggregate_public_id = '" + assigned.publicId() + "'",
            Integer.class));
        AssignmentPayload changed = assignPayload(employeePublicId, scheduleId,
            LocalDate.now().plusDays(2), null, null, null, null);
        try {
            as(scopedHr, () -> calendarCommands.assignSchedule(scopedHr, "assign-key-1", changed));
            fail("Changed payload with the same key must conflict");
        } catch (CommandConflictException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    private static <T> T as(Authentication caller, java.util.function.Supplier<T> action) {
        SecurityContextHolder.getContext().setAuthentication(caller);
        try {
            return action.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void publishReplayRequiresOrganizationWideAuthority() {
        PublishSchedulePayload schedule =
            new PublishSchedulePayload(null, "Asia/Jakarta", weekdaySchedule());
        RevisionResult published = as(hr, () -> calendarCommands.publishSchedule(hr,
            "org-wide-schedule", schedule));
        PublishCalendarPayload calendar = new PublishCalendarPayload(null, "JKT", List.of());
        RevisionResult publishedCalendar = as(hr, () -> calendarCommands.publishCalendar(hr,
            "org-wide-calendar", calendar));
        assertNotNull(published.publicId());
        assertNotNull(publishedCalendar.publicId());

        TransactionTemplate downgrade = new TransactionTemplate(transactions);
        downgrade.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        downgrade.execute(status -> {
            roles.findByAccountId(orgWideHrAccountId).forEach(role -> {
                role.endAt(Instant.now().minusSeconds(1));
                roles.save(role);
            });
            roles.save(new RoleAssignment(orgWideHrAccountId, HrisRole.HR_ADMIN, "ENG",
                Instant.now().minusSeconds(60), Instant.now()));
            return null;
        });

        try {
            as(hr, () -> calendarCommands.publishSchedule(hr, "org-wide-schedule", schedule));
            fail("Scoped-only authority must not replay a publish command");
        } catch (AccessDeniedException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            as(hr, () -> calendarCommands.publishCalendar(hr, "org-wide-calendar", calendar));
            fail("Scoped-only authority must not replay a publish command");
        } catch (AccessDeniedException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    void concurrentOverlappingAssignmentsSerializeToOne() throws Exception {
        AssignmentPayload first = assignPayload(employeePublicId, scheduleId,
            LocalDate.now().plusDays(1), null, null, null, null);
        AssignmentPayload second = assignPayload(employeePublicId, scheduleId,
            LocalDate.now().plusDays(3), null, null, null, null);
        List<AssignmentPayload> payloads = List.of(first, second);
        AtomicReference<AssignmentResult> winner = new AtomicReference<>();
        AtomicReference<Throwable> loser = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<?> futureA = pool.submit(() -> raceAssign(payloads.get(0), ready, go, winner, loser));
            Future<?> futureB = pool.submit(() -> raceAssign(payloads.get(1), ready, go, winner, loser));
            assertTrue(ready.await(30, TimeUnit.SECONDS), "Racers did not start");
            go.countDown();
            futureA.get(60, TimeUnit.SECONDS);
            futureB.get(60, TimeUnit.SECONDS);
        }
        assertNotNull(winner.get());
        assertNotNull(loser.get());
        assertTrue(loser.get() instanceof ContextChangedException,
            "Expected ContextChangedException but was " + loser.get());
    }

    @Test
    void concurrentColdGuardInitializesSingleAccount() throws Exception {
        EmployeeResult fresh = employees.create(UUID.randomUUID(), "EMP-" + UUID.randomUUID(), hr);
        UUID freshEmployee = fresh.employeeId();
        employees.addAssignment(freshEmployee, UUID.randomUUID(), "ENG", null, "STAFF",
            start.minusSeconds(3600), null, hr);
        AssignmentPayload first = assignPayload(freshEmployee, scheduleId,
            LocalDate.now().plusDays(1), LocalDate.now().plusDays(10), null, null, null);
        AssignmentPayload second = assignPayload(freshEmployee, scheduleId,
            LocalDate.now().plusDays(11), null, null, null, null);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<?> futureA = pool.submit(() -> {
                ready.countDown();
                awaitGo(go);
                try {
                    inNewTransaction(() -> calendars.assignSchedule(first, scopedHr));
                } catch (RuntimeException ex) {
                    failure.compareAndSet(null, ex);
                }
            });
            Future<?> futureB = pool.submit(() -> {
                ready.countDown();
                awaitGo(go);
                try {
                    inNewTransaction(() -> calendars.assignSchedule(second, scopedHr));
                } catch (RuntimeException ex) {
                    failure.compareAndSet(null, ex);
                }
            });
            assertTrue(ready.await(30, TimeUnit.SECONDS), "Racers did not start");
            go.countDown();
            futureA.get(60, TimeUnit.SECONDS);
            futureB.get(60, TimeUnit.SECONDS);
        }
        if (failure.get() != null) {
            throw new AssertionError("Cold guard race failed", failure.get());
        }
        Integer accounts = DATABASE.jdbc.queryForObject(
            "select count(*) from attendance_account where employee_id = "
                + "(select id from employees where public_id = '" + freshEmployee + "')",
            Integer.class);
        assertEquals(1, accounts);
    }

    @Test
    void concurrentResolvePinsSingleOccurrence() throws Exception {
        calendars.assignSchedule(assignPayload(employeePublicId, scheduleId,
            LocalDate.now().plusDays(1), null, null, null, null), scopedHr);
        calendars.assignCalendar(assignPayload(employeePublicId, calendarId,
            LocalDate.now().plusDays(1), null, null, null, null), scopedHr);
        LocalDate monday = nextWeekday(1);
        Long internalId = DATABASE.jdbc.queryForObject(
            "select id from employees where public_id = '" + employeePublicId + "'", Long.class);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<UUID> first = new AtomicReference<>();
        AtomicReference<UUID> second = new AtomicReference<>();
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<?> futureA = pool.submit(() -> {
                ready.countDown();
                awaitGo(go);
                first.set(inNewTransaction(() -> resolver.resolveOrPin(internalId, monday).getPublicId()));
            });
            Future<?> futureB = pool.submit(() -> {
                ready.countDown();
                awaitGo(go);
                second.set(inNewTransaction(() -> resolver.resolveOrPin(internalId, monday).getPublicId()));
            });
            assertTrue(ready.await(30, TimeUnit.SECONDS), "Racers did not start");
            go.countDown();
            futureA.get(60, TimeUnit.SECONDS);
            futureB.get(60, TimeUnit.SECONDS);
        }
        assertNotNull(first.get());
        assertNotNull(second.get());
        assertEquals(first.get(), second.get());
        Integer rows = jdbc.queryForObject(
            "select count(*) from attendance_occurrence where employee_id = "
                + "(select id from employees where public_id = '" + employeePublicId + "')",
            Integer.class);
        assertEquals(1, rows);
    }

    @Test
    void revokedRoleDuringAdmissionWaitBlocksAssignment() throws Exception {
        calendars.assignSchedule(assignPayload(employeePublicId, scheduleId,
            LocalDate.now().plusDays(30), LocalDate.now().plusDays(40), null, null, null), scopedHr);
        AssignmentPayload waiting = assignPayload(employeePublicId, scheduleId,
            LocalDate.now().plusDays(1), LocalDate.now().plusDays(10), null, null, null);
        Throwable outcome = runWhileAdmissionHeld(() -> {
            revokeHrRole(scopedHrAccountId);
            return null;
        }, () -> inNewTransaction(() -> calendars.assignSchedule(waiting, scopedHr)));
        assertTrue(outcome instanceof AccessDeniedException,
            "Expected AccessDeniedException but was " + outcome);
    }

    @Test
    void revokedRoleDuringAdmissionWaitBlocksPreview() throws Exception {
        Throwable outcome = runWhileAdmissionHeld(() -> {
            revokeHrRole(scopedHrAccountId);
            return null;
        }, () -> calendars.previewImpact(new PreviewPayload(
            scheduleId, calendarId, LocalDate.now().plusDays(1), LocalDate.now().plusDays(8),
            List.of(employeePublicId), null, null), scopedHr));
        assertTrue(outcome instanceof AccessDeniedException,
            "Expected AccessDeniedException but was " + outcome);
    }

    private void revokeHrRole(Long accountId) {
        TransactionTemplate revoke = new TransactionTemplate(transactions);
        revoke.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        revoke.execute(status -> {
            for (RoleAssignment role : roles.findByAccountId(accountId)) {
                role.endAt(Instant.now().minusSeconds(1));
                roles.save(role);
            }
            return null;
        });
    }

    private Throwable runWhileAdmissionHeld(java.util.concurrent.Callable<Void> whileWaiting,
                                            java.util.concurrent.Callable<?> waitingCall) throws Exception {
        long key = admissionKey();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        var adminSource = new DriverManagerDataSource(DATABASE.url, DATABASE.username, DATABASE.password);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<?> holder = pool.submit(() -> {
                try (Connection connection = adminSource.getConnection()) {
                    connection.setAutoCommit(false);
                    try (var statement = connection.createStatement()) {
                        statement.execute("SELECT pg_advisory_xact_lock(" + key + ")");
                    }
                    locked.countDown();
                    if (!release.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Release was never signaled");
                    }
                    Thread.sleep(200);
                    connection.commit();
                } catch (Exception ex) {
                    throw new IllegalStateException("Admission holder failed", ex);
                }
                return null;
            });
            assertTrue(locked.await(30, TimeUnit.SECONDS), "Admission was never held");
            Future<?> waiter = pool.submit(() -> {
                try {
                    waitingCall.call();
                } catch (Throwable ex) {
                    outcome.compareAndSet(null, ex);
                }
                return null;
            });
            Thread.sleep(300);
            whileWaiting.call();
            release.countDown();
            waiter.get(60, TimeUnit.SECONDS);
            holder.get(60, TimeUnit.SECONDS);
        }
        return outcome.get();
    }

    private static long admissionKey() {
        String hex = CommandExecutionService.computeSha256("profile-admission");
        return ByteBuffer.wrap(HexFormat.of().parseHex(hex)).getLong();
    }

    @Test
    void concurrentRevisesSerializeToOneWinner() throws Exception {
        AssignmentResult original = inNewTransaction(() -> calendars.assignSchedule(
            assignPayload(employeePublicId, scheduleId, LocalDate.now().plusDays(1), null, null, null, null),
            scopedHr));
        AssignmentPayload first = assignPayload(employeePublicId, scheduleId,
            LocalDate.now().plusDays(5), null, original.publicId(), "revise A", null);
        AssignmentPayload second = assignPayload(employeePublicId, scheduleId,
            LocalDate.now().plusDays(6), null, original.publicId(), "revise B", null);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<AssignmentResult> winner = new AtomicReference<>();
        AtomicReference<Throwable> loser = new AtomicReference<>();
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<?> futureA = pool.submit(() -> {
                ready.countDown();
                awaitGo(go);
                try {
                    AssignmentResult result =
                        inNewTransaction(() -> calendars.assignSchedule(first, scopedHr));
                    winner.compareAndSet(null, result);
                } catch (RuntimeException ex) {
                    loser.compareAndSet(null, ex);
                }
            });
            Future<?> futureB = pool.submit(() -> {
                ready.countDown();
                awaitGo(go);
                try {
                    AssignmentResult result =
                        inNewTransaction(() -> calendars.assignSchedule(second, scopedHr));
                    if (!winner.compareAndSet(null, result)) {
                        loser.compareAndSet(null,
                            new IllegalStateException("Both concurrent revises won"));
                    }
                } catch (RuntimeException ex) {
                    loser.compareAndSet(null, ex);
                }
            });
            assertTrue(ready.await(30, TimeUnit.SECONDS), "Racers did not start");
            go.countDown();
            futureA.get(60, TimeUnit.SECONDS);
            futureB.get(60, TimeUnit.SECONDS);
        }
        assertNotNull(winner.get());
        assertNotNull(loser.get());
        assertTrue(loser.get() instanceof ContextChangedException,
            "Expected ContextChangedException but was " + loser.get());
    }

    private void raceAssign(AssignmentPayload payload, CountDownLatch ready, CountDownLatch go,
                            AtomicReference<AssignmentResult> winner, AtomicReference<Throwable> loser) {
        ready.countDown();
        awaitGo(go);
        try {
            AssignmentResult result = inNewTransaction(() -> calendars.assignSchedule(payload, scopedHr));
            if (!winner.compareAndSet(null, result)) {
                loser.compareAndSet(null,
                    new IllegalStateException("Both concurrent assignments won"));
            }
        } catch (RuntimeException ex) {
            loser.compareAndSet(null, ex);
        }
    }

    private <T> T inNewTransaction(java.util.function.Supplier<T> action) {
        TransactionTemplate template = new TransactionTemplate(transactions);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template.execute(status -> action.get());
    }

    private static void awaitGo(CountDownLatch go) {
        try {
            if (!go.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Racers did not start");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Race was interrupted", ex);
        }
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
        LocalDate effectiveTo = toDate != null ? toDate : fromDate.plusDays(366);
        boolean schedule = DATABASE.jdbc.queryForObject(
            "select count(*) from work_schedule_revision where public_id = ?", Integer.class, revisionId) > 0;
        return calendars.previewImpact(new PreviewPayload(
            schedule ? revisionId : null, schedule ? null : revisionId,
            fromDate, effectiveTo, List.of(employeeId), supersedesId, null), hr).fingerprint();
    }

    private static LocalDate nextWeekday(int isoWeekday) {
        LocalDate date = LocalDate.now().plusDays(1);
        while (date.getDayOfWeek().getValue() != isoWeekday) {
            date = date.plusDays(1);
        }
        return date;
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
