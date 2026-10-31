package id.mydev.peoplecore.leave;

import id.mydev.peoplecore.leave.application.service.LeaveEntitlementService;
import id.mydev.peoplecore.leave.application.service.LeaveEntitlementService.GrantPayload;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.identity.domain.model.RoleAssignment;
import id.mydev.peoplecore.identity.domain.model.HrisRole;
import id.mydev.peoplecore.identity.domain.repository.UserAccountRepository;
import id.mydev.peoplecore.identity.domain.repository.RoleAssignmentRepository;
import id.mydev.peoplecore.support.JwtAuthFixture;
import org.springframework.security.core.Authentication;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import id.mydev.peoplecore.support.PostgresFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import id.mydev.peoplecore.leave.application.command.LeaveEntitlementCommands;
import id.mydev.peoplecore.common.command.CommandConflictException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("postgres")
@SpringBootTest(properties = {"peoplecore.runtime.role=api", "spring.jpa.hibernate.ddl-auto=validate"})
class LeaveEntitlementPostgresTest {
    private static final PostgresFixture DATABASE = new PostgresFixture();
    static { DATABASE.flyway().migrate(); DATABASE.grantRuntime(); }
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DATABASE.url);
        registry.add("spring.datasource.username", () -> DATABASE.runtimeUsername);
        registry.add("spring.datasource.password", () -> DATABASE.runtimePassword);
    }
    @Autowired LeaveEntitlementService service;
    @Autowired LeaveEntitlementCommands commands;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired UserAccountRepository accounts;
    @Autowired RoleAssignmentRepository roles;
    private Authentication hr;
    private UUID employeePublicId;
    private Long employeeId;
    private UUID request;
    @BeforeEach
    void setup() {
        UUID publicId=UUID.randomUUID();
        employeePublicId=publicId;
        String subject=UUID.randomUUID().toString();
        UserAccount operator=accounts.saveAndFlush(new UserAccount(UUID.randomUUID(),"https://leave.tests",subject,Instant.now()));
        roles.saveAndFlush(new RoleAssignment(operator.getId(),HrisRole.HR_ADMIN,null,Instant.now().minusSeconds(60),Instant.now()));
        hr=JwtAuthFixture.named("https://leave.tests",subject);
        jdbc.update("INSERT INTO employees(public_id,employee_number,status,created_at,version) VALUES (?,?,'ACTIVE',?,0)",publicId,publicId.toString(),Timestamp.from(Instant.now()));
        employeeId=jdbc.queryForObject("SELECT id FROM employees WHERE public_id=?",Long.class,publicId);
        request=UUID.randomUUID();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            service.lock(employeeId);
            jdbc.update("INSERT INTO leave_period(employee_id,period_year) VALUES (?,2026)",employeeId);
            jdbc.update("INSERT INTO leave_ledger(public_id,period_id,entry_type,business_reference,half_units,source,reason,source_reference,created_at) SELECT ?,id,'OPENING','OPENING',2,'HR','Opening','OPENING',? FROM leave_period WHERE employee_id=?",UUID.randomUUID(),Timestamp.from(Instant.now()),employeeId);
        });
    }
    @Test
    void exactReservationConsumptionAndReversalAreIdempotent() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            service.reserve(employeeId,2026,request,1,2);
            service.reserve(employeeId,2026,request,1,2);
            service.consume(employeeId,2026,request,1);
            service.consume(employeeId,2026,request,1);
            service.reverse(employeeId,2026,request,1);
            service.reverse(employeeId,2026,request,1);
        });
        assertEquals(1,count("CONSUMPTION"));
        assertEquals(1,count("REVERSAL"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(id) FROM leave_reservation WHERE request_public_id=? AND active=TRUE",Integer.class,request));
    }
    @Test
    void competingReservationCannotOverspendAndRollbackLeavesNoEffect() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> service.reserve(employeeId,2026,request,1,2));
        assertThrows(IllegalArgumentException.class, () -> new TransactionTemplate(transactions).executeWithoutResult(status -> service.reserve(employeeId,2026,UUID.randomUUID(),1,1)));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(r.id) FROM leave_reservation r JOIN leave_period p ON p.id=r.period_id WHERE p.employee_id=?",Integer.class,employeeId));
    }
    @Test
    void consumptionRequiresExactRevision() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> service.reserve(employeeId,2026,request,1,2));
        assertThrows(IllegalArgumentException.class, () -> new TransactionTemplate(transactions).executeWithoutResult(status -> service.consume(employeeId,2026,request,2)));
        assertEquals(0,count("CONSUMPTION"));
    }
    @Test
    void businessIdentityIgnoresHttpKeyButRejectsChangedAmount() {
        GrantPayload first=new GrantPayload(employeePublicId,2027,4,"ref-a","HR","Opening");
        assertEquals(4,service.opening(first,hr).granted());
        assertEquals(4,service.opening(first,hr).granted());
        assertThrows(CommandConflictException.class, () -> service.opening(new GrantPayload(employeePublicId,2027,6,"ref-c","HR","Opening"),hr));
        GrantPayload adjustment=new GrantPayload(employeePublicId,2027,1,"adjust-1","HR","Correction");
        assertEquals(1,service.adjust(adjustment,hr).adjusted());
        assertEquals(1,service.adjust(adjustment,hr).adjusted());
        assertThrows(CommandConflictException.class, () -> service.adjust(new GrantPayload(employeePublicId,2027,2,"adjust-1","HR","Correction"),hr));
    }
    @Test
    void businessRetriesRejectChangedSourceReasonOrOpeningReference() {
        GrantPayload opening=new GrantPayload(employeePublicId,2027,4,"opening-ref","HR","Opening evidence");
        service.opening(opening,hr);
        assertThrows(CommandConflictException.class, () -> service.opening(new GrantPayload(employeePublicId,2027,4,"opening-ref","IMPORT","Opening evidence"),hr));
        assertThrows(CommandConflictException.class, () -> service.opening(new GrantPayload(employeePublicId,2027,4,"opening-ref","HR","Different reason"),hr));
        assertThrows(CommandConflictException.class, () -> service.opening(new GrantPayload(employeePublicId,2027,4,"different-ref","HR","Opening evidence"),hr));
        GrantPayload adjustment=new GrantPayload(employeePublicId,2027,1,"adjust-evidence","HR","Adjustment evidence");
        service.adjust(adjustment,hr);
        assertThrows(CommandConflictException.class, () -> service.adjust(new GrantPayload(employeePublicId,2027,1,"adjust-evidence","IMPORT","Adjustment evidence"),hr));
        assertThrows(CommandConflictException.class, () -> service.adjust(new GrantPayload(employeePublicId,2027,1,"adjust-evidence","HR","Different reason"),hr));
        assertEquals("opening-ref",service.ledger(employeePublicId,2027,null,hr).getFirst().sourceReference());
        assertEquals(1,service.query(employeePublicId,2027,hr).adjusted());
    }

    @Test
    void negativeAdjustmentCannotRemoveReservedOrConsumedCapacity() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> service.reserve(employeeId,2026,request,1,2));
        GrantPayload reduction=new GrantPayload(employeePublicId,2026,-1,"reduce","HR","Reduction");
        assertThrows(IllegalArgumentException.class, () -> service.adjust(reduction,hr));
        new TransactionTemplate(transactions).executeWithoutResult(status -> service.consume(employeeId,2026,request,1));
        assertThrows(IllegalArgumentException.class, () -> service.adjust(reduction,hr));
        assertEquals(2,service.query(employeePublicId,2026,hr).consumed());
    }
    @Test
    void expiredYearCreditRemainsVisibleButCannotBeReserved() {
        service.opening(new GrantPayload(employeePublicId,2020,2,"old","HR","Historical grant"),hr);
        service.adjust(new GrantPayload(employeePublicId,2020,1,"old-adjust","HR","Historical correction"),hr);
        assertEquals(3,service.query(employeePublicId,2020,hr).available());
        assertThrows(IllegalArgumentException.class, () -> new TransactionTemplate(transactions).executeWithoutResult(status -> service.reserve(employeeId,2020,request,1,1)));
    }
    @Test
    void invalidGrantAndEffectInputsAreRejectedAtTheBoundary() {
        assertThrows(IllegalArgumentException.class,()->service.opening(new GrantPayload(null,2027,1,"ref","HR","Reason"),hr));
        assertThrows(IllegalArgumentException.class,()->service.opening(new GrantPayload(employeePublicId,1899,1,"ref","HR","Reason"),hr));
        assertThrows(IllegalArgumentException.class,()->service.opening(new GrantPayload(employeePublicId,9999,1,"ref","HR","Reason"),hr));
        assertThrows(IllegalArgumentException.class,()->service.opening(new GrantPayload(employeePublicId,2027,0,"ref","HR","Reason"),hr));
        assertThrows(IllegalArgumentException.class,()->service.adjust(new GrantPayload(employeePublicId,2027,0,"ref","HR","Reason"),hr));
        assertThrows(IllegalArgumentException.class,()->service.opening(new GrantPayload(employeePublicId,2027,1," ","HR","Reason"),hr));
        assertThrows(IllegalArgumentException.class,()->service.opening(new GrantPayload(employeePublicId,2027,1,"x".repeat(201),"HR","Reason"),hr));
        assertThrows(IllegalArgumentException.class,()->service.opening(new GrantPayload(employeePublicId,2027,1,"ref"," ","Reason"),hr));
        assertThrows(IllegalArgumentException.class,()->service.opening(new GrantPayload(employeePublicId,2027,1,"ref","x".repeat(201),"Reason"),hr));
        assertThrows(IllegalArgumentException.class,()->service.opening(new GrantPayload(employeePublicId,2027,1,"ref","HR"," "),hr));
        assertThrows(IllegalArgumentException.class,()->service.opening(new GrantPayload(employeePublicId,2027,1,"ref","HR","x".repeat(1001)),hr));
        assertThrows(IllegalArgumentException.class,()->service.opening(new GrantPayload(employeePublicId,2027,-1,"ref","HR","Reason"),hr));
        assertThrows(IllegalArgumentException.class,()->new TransactionTemplate(transactions).executeWithoutResult(status -> service.reserve(employeeId,2027,null,1,1)));
        assertThrows(IllegalArgumentException.class,()->new TransactionTemplate(transactions).executeWithoutResult(status -> service.reserve(employeeId,2027,request,0,1)));
        assertThrows(IllegalArgumentException.class,()->new TransactionTemplate(transactions).executeWithoutResult(status -> service.reserve(employeeId,2027,request,1,0)));
    }
    @Test
    void lastCapacityIsSerializedAcrossTwoTransactions() throws Exception {
        CountDownLatch held=new CountDownLatch(1);
        CountDownLatch release=new CountDownLatch(1);
        try (var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var first=executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                service.reserve(employeeId,2026,request,1,2);
                held.countDown();
                try { if (!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("Test lock timeout"); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
            }));
            if (!held.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("First reservation did not acquire lock");
            var second=executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> service.reserve(employeeId,2026,UUID.randomUUID(),1,2)));
            release.countDown();
            first.get(10,TimeUnit.SECONDS);
            assertThrows(java.util.concurrent.ExecutionException.class, () -> second.get(10,TimeUnit.SECONDS));
        } finally { release.countDown(); }
        assertEquals(2,service.query(employeePublicId,2026,hr).reserved());
    }
    @Test
    void transactionFailureRollsBackReservationAndLedgerEffects() {
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            service.reserve(employeeId,2026,request,1,2);
            service.consume(employeeId,2026,request,1);
            throw new IllegalStateException("Injected failure");
        }));
        assertEquals(0,count("CONSUMPTION"));
        assertEquals(0,service.query(employeePublicId,2026,hr).reserved());
        assertEquals(2,service.query(employeePublicId,2026,hr).available());
    }
    @AfterEach
    void clearCaller() {
        SecurityContextHolder.clearContext();
    }
    @Test
    void receiptReplayAndDifferentKeysShareOneBusinessAdjustment() {
        SecurityContextHolder.getContext().setAuthentication(hr);
        GrantPayload adjustment=new GrantPayload(employeePublicId,2026,1,"adjust-http","HR","Correction");
        String key=UUID.randomUUID().toString();
        assertEquals(1,commands.grant(hr,key,adjustment,false).adjusted());
        assertEquals(1,commands.grant(hr,key,adjustment,false).adjusted());
        assertEquals(1,commands.grant(hr,UUID.randomUUID().toString(),adjustment,false).adjusted());
        assertThrows(CommandConflictException.class, () -> commands.grant(hr,key,new GrantPayload(employeePublicId,2026,2,"adjust-http","HR","Correction"),false));
        assertThrows(CommandConflictException.class, () -> commands.grant(hr,UUID.randomUUID().toString(),new GrantPayload(employeePublicId,2026,1,"adjust-http","IMPORT","Correction"),false));
        assertThrows(CommandConflictException.class, () -> commands.grant(hr,UUID.randomUUID().toString(),new GrantPayload(employeePublicId,2026,1,"adjust-http","HR","Changed reason"),false));
        assertEquals(1,count("ADJUSTMENT"));
    }
    private int count(String type) {
        Integer result=jdbc.queryForObject("SELECT COUNT(l.id) FROM leave_ledger l JOIN leave_period p ON p.id=l.period_id WHERE p.employee_id=? AND l.entry_type=?",Integer.class,employeeId,type);
        return result == null ? 0 : result;
    }
}
