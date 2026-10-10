package id.mydev.peoplecore;

import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.common.command.CommandConflictException;
import id.mydev.peoplecore.identity.domain.model.HrisRole;
import id.mydev.peoplecore.identity.domain.model.AccountBinding;
import id.mydev.peoplecore.identity.domain.repository.AccountBindingRepository;
import id.mydev.peoplecore.identity.domain.model.RoleAssignment;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.identity.domain.repository.RoleAssignmentRepository;
import id.mydev.peoplecore.identity.domain.repository.UserAccountRepository;
import id.mydev.peoplecore.identity.application.service.AuthGenerationService;
import id.mydev.peoplecore.organization.application.command.EmployeeCommands;
import id.mydev.peoplecore.organization.application.command.EmployeeResults.AssignmentResult;
import id.mydev.peoplecore.organization.application.service.EmployeeService;
import id.mydev.peoplecore.organization.domain.exception.AssignmentOverlapException;
import id.mydev.peoplecore.organization.domain.exception.AssignmentValidationException;
import id.mydev.peoplecore.organization.domain.repository.EmployeeAssignmentRepository;
import id.mydev.peoplecore.support.JwtAuthFixture;
import id.mydev.peoplecore.support.PostgresFixture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.aop.framework.ProxyFactory;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("postgres")
@Import(EmployeeHistoryPostgresTest.HistoryInterception.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "peoplecore.runtime.role=api", "spring.jpa.hibernate.ddl-auto=validate",
    "peoplecore.organization.allowed-units=ENG,FIN"
})
class EmployeeHistoryPostgresTest {
    private static final ThreadLocal<Runnable> BEFORE_HISTORY = new ThreadLocal<>();

    @TestConfiguration(proxyBeanMethods = false)
    static class HistoryInterception {
        @Bean
        static BeanPostProcessor historyInterceptor() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!(bean instanceof EmployeeAssignmentRepository)) {
                        return bean;
                    }
                    var factory = new ProxyFactory(bean);
                    factory.addAdvice((MethodInterceptor) invocation -> {
                        if (invocation.getMethod().getName().equals("findHistory")) {
                            Runnable hook = BEFORE_HISTORY.get();
                            BEFORE_HISTORY.remove();
                            if (hook != null) {
                                hook.run();
                            }
                        }
                        return invocation.proceed();
                    });
                    return factory.getProxy();
                }
            };
        }
    }
    private static final String ISSUER = "https://history.test";
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
    @Autowired EmployeeCommands commands;
    @Autowired EmployeeService employees;
    @Autowired EmployeeAssignmentRepository assignments;
    @Autowired UserAccountRepository accounts;
    @Autowired AccountBindingRepository bindings;
    @Autowired RoleAssignmentRepository roles;
    @Autowired AuthGenerationService generations;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired WebApplicationContext context;
    private Authentication hr;
    private Long hrId;
    private UUID employee;
    private UUID manager;
    private Instant start;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        start = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(86400);
        String subject = "hr-" + UUID.randomUUID();
        UserAccount account = accounts.save(new UserAccount(UUID.randomUUID(), ISSUER, subject, start));
        hrId = account.getId();
        roles.save(new RoleAssignment(hrId, HrisRole.HR_ADMIN, null, start, start));
        hr = JwtAuthFixture.token(ISSUER, subject);
        SecurityContextHolder.getContext().setAuthentication(hr);
        employee = commands.create(hr, "create-" + UUID.randomUUID(), "EMP-" + UUID.randomUUID()).employeeId();
        manager = commands.create(hr, "manager-" + UUID.randomUUID(), "MGR-" + UUID.randomUUID()).employeeId();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach
    void clearCaller() {
        BEFORE_HISTORY.remove();
        SecurityContextHolder.clearContext();
    }

    @AfterAll
    static void cleanup() { DATABASE.close(); }

    private AssignmentResult initial() {
        return commands.addAssignment(hr, "add-" + UUID.randomUUID(), employee, "ENG", manager, "L3", start, null);
    }

    private AssignmentResult revise(UUID original, Instant boundary, String key) {
        return commands.reviseAssignment(hr, key, employee, original, "FIN", null, "L4", boundary, null, "Reviewed transfer");
    }

    private JsonNode audit(String action) {
        String details = jdbc.queryForObject("select details from audit_events where action=? and aggregate_public_id=? order by id desc limit 1",
            String.class, action, employee);
        return mapper.readTree(details);
    }

    @Test
    void historyDeniesTransferCommittedBetweenScopeCheckAndSnapshotRead() {
        var original = initial();
        String subject = "scoped-" + UUID.randomUUID();
        var scopedAccount = accounts.save(new UserAccount(UUID.randomUUID(), ISSUER, subject, start));
        roles.save(new RoleAssignment(scopedAccount.getId(), HrisRole.HR_ADMIN, "ENG", start, start));
        var scoped = JwtAuthFixture.token(ISSUER, subject);
        var transaction = new TransactionTemplate(transactions);
        transaction.setReadOnly(true);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        try (var executor = Executors.newSingleThreadExecutor()) {
            transaction.executeWithoutResult(status -> {
                employees.get(employee, scoped);
                BEFORE_HISTORY.set(() -> {
                    var writer = executor.submit(() -> {
                        SecurityContextHolder.getContext().setAuthentication(hr);
                        try { return revise(original.assignmentId(), start, "scoped-history-race"); }
                        finally { SecurityContextHolder.clearContext(); }
                    });
                    try { writer.get(20, TimeUnit.SECONDS); }
                    catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(failure);
                    } catch (ExecutionException | TimeoutException failure) {
                        throw new AssertionError(failure);
                    }
                });
                assertThrows(ResourceNotFoundException.class, () -> employees.assignmentHistory(employee, scoped));
                assertEquals(2, employees.assignmentHistory(employee, hr).size());
                status.setRollbackOnly();
            });
        }
        assertThrows(ResourceNotFoundException.class, () -> employees.assignmentHistory(employee, scoped));
        roles.save(new RoleAssignment(scopedAccount.getId(), HrisRole.HR_ADMIN, "FIN", start, start));
        assertEquals(2, employees.assignmentHistory(employee, scoped).size());
    }

    @Test
    void historyPreservesSelfReadRoleRequirementAndMissingResourceDenial() {
        initial();
        String subject = "self-" + UUID.randomUUID();
        var account = accounts.save(new UserAccount(UUID.randomUUID(), ISSUER, subject, start));
        bindings.save(new AccountBinding(employees.requireInternalByPublicId(employee).getId(), employee,
            account.getId(), ISSUER, subject, start));
        var caller = JwtAuthFixture.token(ISSUER, subject);
        assertThrows(ResourceNotFoundException.class, () -> employees.assignmentHistory(employee, caller));
        roles.save(new RoleAssignment(account.getId(), HrisRole.EMPLOYEE, null, start, start));
        assertEquals(1, employees.assignmentHistory(employee, caller).size());
        assertThrows(ResourceNotFoundException.class, () -> employees.assignmentHistory(manager, caller));
        assertThrows(ResourceNotFoundException.class, () -> employees.assignmentHistory(UUID.randomUUID(), hr));
    }

    @Test
    void historySeesCommittedRevisionDespitePreviouslyManagedOriginal() throws Exception {
        var original = initial();
        Instant boundary = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(3600);
        var transaction = new TransactionTemplate(transactions);
        transaction.setReadOnly(true);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        try (var executor = Executors.newSingleThreadExecutor()) {
            transaction.executeWithoutResult(status -> {
                employees.get(employee, hr);
                var writer = executor.submit(() -> {
                    SecurityContextHolder.getContext().setAuthentication(hr);
                    try { return revise(original.assignmentId(), boundary, "history-race"); }
                    finally { SecurityContextHolder.clearContext(); }
                });
                try { writer.get(20, TimeUnit.SECONDS); }
                catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                } catch (ExecutionException | TimeoutException failure) {
                    throw new AssertionError(failure);
                }
                var history = employees.assignmentHistory(employee, hr);
                assertEquals(3, history.size());
                assertEquals(2L, history.stream().filter(row -> row.supersededAt() == null).count());
                var prior = history.stream().filter(row -> row.assignmentId().equals(original.assignmentId())).findFirst().orElseThrow();
                assertNotNull(prior.supersededAt());
                assertEquals(manager, prior.managerId());
            });
        }
    }

    @Test
    void normalizedRevisionDoesNotCreateCollapsedPrefixAndReplayMatchesStorage() {
        var original = initial();
        Instant requested = start.plusNanos(1);
        var result = revise(original.assignmentId(), requested, "micro-revision");
        assertEquals(start, result.validFrom());
        assertNull(result.retainedAssignmentId());
        assertEquals(2, employees.assignmentHistory(employee, hr).size());
        assertEquals(result.validFrom(), assignments.findByPublicId(result.assignmentId()).orElseThrow().getValidFrom());
        assertEquals(result, revise(original.assignmentId(), requested, "micro-revision"));
        assertEquals(start.toString(), audit("ASSIGNMENT_REVISE").get("after").get("validFrom").stringValue());
    }

    @Test
    void collapsedFiniteIntervalsAreRejectedBeforeWriting() {
        long generation = generations.current();
        assertThrows(AssignmentValidationException.class, () -> commands.addAssignment(hr, "collapsed-add",
            employee, "ENG", null, "L3", start, start.plusNanos(1)));
        assertEquals(0, employees.assignmentHistory(employee, hr).size());
        assertEquals(generation, generations.current());
        var original = initial();
        generation = generations.current();
        assertThrows(AssignmentValidationException.class, () -> commands.reviseAssignment(hr, "collapsed-revision",
            employee, original.assignmentId(), "FIN", null, "L4", start.plusSeconds(1),
            start.plusSeconds(1).plusNanos(1), "Reviewed transfer"));
        assertNull(assignments.findByPublicId(original.assignmentId()).orElseThrow().getSupersededAt());
        assertEquals(1, employees.assignmentHistory(employee, hr).size());
        assertEquals(generation, generations.current());
    }

    @Test
    void futureTransitionPreservesOriginalAndSelectsExactBoundary() {
        var original = initial();
        Instant boundary = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(3600);
        long generation = generations.current();
        var replacement = revise(original.assignmentId(), boundary, "revise-future");
        var stored = assignments.findByPublicId(original.assignmentId()).orElseThrow();
        assertNull(stored.getValidTo());
        assertNotNull(stored.getSupersededAt());
        assertNotNull(replacement.retainedAssignmentId());
        assertEquals(original.assignmentId(), replacement.supersedesId());
        assertEquals(original.assignmentId(), replacement.before().assignmentId());
        var retained = assignments.findByPublicId(replacement.retainedAssignmentId()).orElseThrow();
        assertEquals(boundary, retained.getValidTo());
        assertEquals(3, employees.assignmentHistory(employee, hr).size());
        Long internalId = employees.requireInternalByPublicId(employee).getId();
        assertEquals("ENG", employees.currentOrgUnit(internalId, boundary.minusNanos(1)).orElseThrow());
        assertEquals("FIN", employees.currentOrgUnit(internalId, boundary).orElseThrow());
        assertEquals(generation + 1, generations.current());
        assertEquals(manager.toString(), audit("ASSIGNMENT_REVISE").get("before").get("managerId").stringValue());
        assertEquals("FIN", audit("ASSIGNMENT_REVISE").get("after").get("orgUnit").stringValue());
        assertEquals(replacement, revise(original.assignmentId(), boundary, "revise-future"));
        assertEquals(3, employees.assignmentHistory(employee, hr).size());
    }

    @Test
    void backdatedRevisionRetainsOldVersionAndCanReplaceWholeInterval() {
        var original = initial();
        var replacement = revise(original.assignmentId(), start, "revise-backdated");
        assertNull(replacement.retainedAssignmentId());
        assertEquals(2, employees.assignmentHistory(employee, hr).size());
        assertEquals("ENG", assignments.findByPublicId(original.assignmentId()).orElseThrow().getOrgUnit());
        assertEquals("FIN", employees.currentOrgUnit(employees.requireInternalByPublicId(employee).getId(), start).orElseThrow());
        assertThrows(AssignmentOverlapException.class, () -> revise(original.assignmentId(), start, "second-revision"));
        assertThrows(CommandConflictException.class, () -> revise(original.assignmentId(), start.plusSeconds(1), "revise-backdated"));
    }

    @Test
    void revisionRejectsBadIntervalsReferencesAndOverlapWithoutPartialWrites() {
        var original = commands.addAssignment(hr, "bounded", employee, "ENG", manager, "L3", start, start.plusSeconds(100));
        commands.addAssignment(hr, "next", employee, "FIN", null, "L4", start.plusSeconds(100), null);
        long generation = generations.current();
        assertThrows(AssignmentValidationException.class, () -> revise(original.assignmentId(), start.minusSeconds(1), "too-early"));
        assertThrows(AssignmentValidationException.class, () -> revise(original.assignmentId(), start.plusSeconds(100), "too-late"));
        assertThrows(AssignmentOverlapException.class, () -> revise(original.assignmentId(), start.plusSeconds(50), "overlap"));
        assertThrows(ResourceNotFoundException.class, () -> revise(UUID.randomUUID(), start, "missing"));
        assertThrows(AssignmentValidationException.class, () -> commands.reviseAssignment(hr, "bad-interval", employee,
            original.assignmentId(), "ENG", null, "L3", start, start, "Reviewed"));
        assertThrows(AssignmentValidationException.class, () -> commands.reviseAssignment(hr, "blank-level", employee,
            original.assignmentId(), "ENG", null, " ", start, null, "Reviewed"));
        assertThrows(AssignmentValidationException.class, () -> commands.reviseAssignment(hr, "null-start", employee,
            original.assignmentId(), "ENG", null, "L3", null, null, "Reviewed"));
        assertThrows(AssignmentValidationException.class, () -> commands.reviseAssignment(hr, "self-manager", employee,
            original.assignmentId(), "ENG", employee, "L3", start, start.plusSeconds(100), "Reviewed"));
        UUID other = commands.create(hr, "other", "OTHER-" + UUID.randomUUID()).employeeId();
        long settledGeneration = generations.current();
        assertThrows(ResourceNotFoundException.class, () -> commands.reviseAssignment(hr, "wrong-employee", other,
            original.assignmentId(), "ENG", null, "L3", start, null, "Reviewed"));
        assertNull(assignments.findByPublicId(original.assignmentId()).orElseThrow().getSupersededAt());
        assertEquals(settledGeneration, generations.current());
        assertEquals(generation + 1, settledGeneration);
        assertEquals(0, jdbc.queryForObject("select count(*) from command_receipts where command_type='ASSIGNMENT_REVISE' and actor_id=?", Integer.class, hr.getName()));
        assertEquals(0, jdbc.queryForObject("select count(*) from audit_events where action='ASSIGNMENT_REVISE' and aggregate_public_id=?", Integer.class, employee));
    }

    @Test
    void managerAppearsInReadEventAndAuditContracts() throws Exception {
        var result = initial();
        assertEquals(manager, result.managerId());
        String payload = jdbc.queryForObject("select payload from outbox_events where event_type='ASSIGNMENT_ADD' and aggregate_public_id=?", String.class, employee);
        assertEquals(manager.toString(), mapper.readTree(payload).get("managerId").stringValue());
        assertEquals(manager.toString(), audit("ASSIGNMENT_ADD").get("after").get("managerId").stringValue());
        mvc.perform(get("/api/v1/employees/" + employee).with(authentication(hr)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.assignments[0].managerId").value(manager.toString()));
        assertTrue(audit("ASSIGNMENT_ADD").get("before").isNull());
    }

    @Test
    void numberAndEmploymentChangesRetainBeforeAfterWithoutChangingAccountCutoff() throws Exception {
        String oldNumber = employees.get(employee, hr).getEmployeeNumber();
        commands.updateNumber(hr, "number-update", employee, "UPDATED-" + UUID.randomUUID());
        assertEquals(oldNumber, audit("EMPLOYEE_UPDATE").get("before").get("employeeNumber").stringValue());
        LocalDate joined = LocalDate.of(2025, 1, 1);
        LocalDate ended = LocalDate.of(2026, 10, 31);
        var first = commands.updateEmploymentDates(hr, "employment-first", employee, joined, ended, "Verified employment");
        assertEquals(ended, first.employmentEndDate());
        assertTrue(audit("EMPLOYMENT_UPDATE").get("before").get("employmentStartDate").isNull());
        var next = commands.updateEmploymentDates(hr, "employment-rehire", employee, ended.plusDays(1), null, "Reviewed new period");
        assertEquals(joined, next.before().employmentStartDate());
        assertEquals(ended, next.before().employmentEndDate());
        assertNull(next.employmentEndDate());
        assertEquals(next, commands.updateEmploymentDates(hr, "employment-rehire", employee, ended.plusDays(1), null, "Reviewed new period"));
        assertThrows(CommandConflictException.class, () -> commands.updateEmploymentDates(hr, "employment-rehire", employee, joined, ended, "Changed"));
        assertTrue(accounts.findById(hrId).orElseThrow().isActiveAt(Instant.now()));
        assertNull(accounts.findById(hrId).orElseThrow().getAccessEndsAt());
        mvc.perform(get("/api/v1/employees/" + employee).with(authentication(hr)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.employee.employmentStartDate").value("2026-11-01"));
    }

    @Test
    void invalidEmploymentAndMissingReasonsRollBack() {
        assertThrows(IllegalArgumentException.class, () -> commands.updateEmploymentDates(hr, "invalid-dates", employee,
            LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 1), "Reviewed"));
        assertNull(employees.get(employee, hr).getEmploymentStartDate());
        assertThrows(IllegalArgumentException.class, () -> commands.updateEmploymentDates(hr, "missing-reason", employee,
            LocalDate.now(), null, " "));
        assertThrows(IllegalArgumentException.class, () -> commands.updateEmploymentDates(hr, "long-reason", employee,
            LocalDate.now(), null, "x".repeat(501)));
        assertEquals(0, jdbc.queryForObject("select count(*) from command_receipts where command_type='EMPLOYMENT_UPDATE' and actor_id=?", Integer.class, hr.getName()));
    }

    private void restrictHrTo(String unit) {
        Instant now = Instant.now();
        for (RoleAssignment row : roles.findByAccountId(hrId)) {
            row.endAt(now);
            roles.save(row);
        }
        roles.save(new RoleAssignment(hrId, HrisRole.HR_ADMIN, unit, now, now));
    }

    @Test
    void scopedHrCannotTransferOutsideScopeOrReplayFormerFutureScope() {
        var original = initial();
        Instant boundary = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(3600);
        revise(original.assignmentId(), boundary, "wide-transfer");
        restrictHrTo("ENG");
        assertThrows(AccessDeniedException.class, () -> revise(original.assignmentId(), boundary, "wide-transfer"));
        var retained = employees.assignmentHistory(employee, hr).stream()
            .filter(row -> row.supersededAt() == null && row.orgUnit().equals("ENG")).findFirst().orElseThrow();
        assertThrows(ResourceNotFoundException.class, () -> revise(retained.assignmentId(), start, "scoped-transfer"));
        restrictHrTo("FIN");
        assertThrows(ResourceNotFoundException.class, () -> commands.updateEmploymentDates(hr, "wrong-scope", employee,
            LocalDate.now(), null, "Reviewed"));
    }

    @Test
    void concurrentRevisionsHaveOneWinnerAndNoDuplicateIntervals() throws Exception {
        var original = initial();
        Instant boundary = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(3600);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> attempts = List.of(pool.submit(() -> concurrentRevision(original.assignmentId(), boundary, "race-a", ready, release)),
                pool.submit(() -> concurrentRevision(original.assignmentId(), boundary, "race-b", ready, release)));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            release.countDown();
            int winners = 0;
            for (Future<Boolean> attempt : attempts) if (attempt.get(20, TimeUnit.SECONDS)) winners++;
            assertEquals(1, winners);
        }
        var timeline = assignments.findByEmployeeIdAndSupersededAtIsNullOrderByValidFromAsc(
            employees.requireInternalByPublicId(employee).getId());
        assertEquals(2, timeline.size());
        assertFalse(timeline.get(0).overlaps(timeline.get(1).getValidFrom(), timeline.get(1).getValidTo()));
    }

    private boolean concurrentRevision(UUID original, Instant boundary, String key,
                                       CountDownLatch ready, CountDownLatch release) throws Exception {
        SecurityContextHolder.getContext().setAuthentication(hr);
        try {
            ready.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Barrier timed out");
            revise(original, boundary, key);
            return true;
        } catch (AssignmentOverlapException expected) {
            return false;
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void newHttpCommandsValidatePayloadAndReturnCompleteContracts() throws Exception {
        var original = initial();
        mvc.perform(put("/api/v1/employees/" + employee + "/employment").with(authentication(hr)).with(csrf())
                .header("Idempotency-Key", "http-employment").contentType(MediaType.APPLICATION_JSON)
                .content("{\"startDate\":\"2026-01-01\",\"endDate\":\"2026-12-31\",\"reason\":\"Reviewed\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.employmentEndDate").value("2026-12-31"));
        mvc.perform(put("/api/v1/employees/" + employee + "/employment").with(authentication(hr)).with(csrf())
                .header("Idempotency-Key", "http-invalid").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest());
        String body = "{\"orgUnit\":\"FIN\",\"jobLevel\":\"L4\",\"validFrom\":\"" + Instant.now().plusSeconds(3600) + "\",\"reason\":\"Reviewed\"}";
        mvc.perform(post("/api/v1/employees/" + employee + "/assignments/" + original.assignmentId() + "/revisions")
                .with(authentication(hr)).with(csrf()).header("Idempotency-Key", "http-revision")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.data.supersedesId").value(original.assignmentId().toString()));
    }
}
