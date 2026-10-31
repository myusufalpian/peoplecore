package id.mydev.peoplecore.attendance;

import id.mydev.peoplecore.attendance.application.command.AttendanceCommands;
import id.mydev.peoplecore.attendance.application.command.AttendancePayloads.ClockPayload;
import id.mydev.peoplecore.attendance.application.command.AttendancePayloads.CorrectionPayload;
import id.mydev.peoplecore.attendance.application.command.AttendancePayloads.CorrectionAction;
import id.mydev.peoplecore.attendance.application.command.AttendanceResults.CorrectionResult;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarPayloads.AssignmentPayload;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarPayloads.PreviewPayload;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarPayloads.PublishSchedulePayload;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarPayloads.PublishCalendarPayload;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarPayloads.ScheduleDayPayload;
import id.mydev.peoplecore.attendance.application.service.AttendanceTimePolicy.Action;
import id.mydev.peoplecore.attendance.application.service.AttendanceCorrectionService;
import id.mydev.peoplecore.attendance.application.service.AttendanceReviewerService;
import id.mydev.peoplecore.attendance.application.service.AttendanceReviewerService.Rule;
import id.mydev.peoplecore.attendance.application.service.AttendanceReviewerService.PublishRules;
import id.mydev.peoplecore.attendance.application.service.AttendanceReviewerService.ActivateRules;
import id.mydev.peoplecore.attendance.application.service.WorkCalendarService;
import id.mydev.peoplecore.attendance.domain.exception.WorkContextException;
import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.identity.domain.model.AccountBinding;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.identity.domain.model.RoleAssignment;
import id.mydev.peoplecore.identity.domain.model.HrisRole;
import id.mydev.peoplecore.identity.domain.repository.AccountBindingRepository;
import id.mydev.peoplecore.identity.domain.repository.UserAccountRepository;
import id.mydev.peoplecore.identity.domain.repository.RoleAssignmentRepository;
import id.mydev.peoplecore.organization.application.service.EmployeeService;
import id.mydev.peoplecore.organization.domain.repository.EmployeeRepository;
import id.mydev.peoplecore.support.PostgresFixture;
import id.mydev.peoplecore.support.JwtAuthFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("postgres")
@SpringBootTest(properties={"peoplecore.runtime.role=api","spring.jpa.hibernate.ddl-auto=validate","peoplecore.organization.allowed-units=ENG"})
@Import(AttendanceRecordingAcceptancePostgresTest.TimeConfiguration.class)
class AttendanceRecordingAcceptancePostgresTest {
    private static final PostgresFixture DATABASE=new PostgresFixture();
    private static final String ISSUER="https://attendance.acceptance.test";
    private static final LocalDate DATE=LocalDate.of(2026,10,19);
    private static final Instant START=Instant.parse("2026-10-19T02:00:00Z");
    static { DATABASE.flyway().migrate(); DATABASE.grantRuntime(); }
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->DATABASE.url);
        registry.add("spring.datasource.username",()->DATABASE.runtimeUsername);
        registry.add("spring.datasource.password",()->DATABASE.runtimePassword);
    }
    static class MutableClock extends Clock {
        private Instant value=START;
        void at(Instant instant) { value=instant; }
        @Override public Instant instant() { return value; }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(value,zone); }
    }
    @TestConfiguration
    static class TimeConfiguration {
        @Bean @Primary MutableClock acceptanceClock() { return new MutableClock(); }
    }
    @Autowired AttendanceCommands commands;
    @Autowired AttendanceCorrectionService corrections;
    @Autowired AttendanceReviewerService reviewers;
    @Autowired WorkCalendarService calendars;
    @Autowired EmployeeService employees;
    @Autowired EmployeeRepository employeeRows;
    @Autowired UserAccountRepository accounts;
    @Autowired AccountBindingRepository bindings;
    @Autowired RoleAssignmentRepository roles;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    private Authentication hr;
    private Authentication member;
    private Authentication manager;
    private UUID employee;
    private UUID managerEmployee;
    private UUID hrPublicId;
    private RoleAssignment memberRole;

    @BeforeEach
    void setup() {
        clock.at(START);
        Instant earlier=START.minusSeconds(86400*30L);
        UserAccount hrAccount=account("hr");
        hrPublicId=hrAccount.getPublicId();
        roles.saveAndFlush(new RoleAssignment(hrAccount.getId(),HrisRole.HR_ADMIN,null,earlier,earlier));
        hr=JwtAuthFixture.named(ISSUER,hrAccount.getOidcSubject());
        UUID hrEmployee=employee(hrAccount);
        employees.addAssignment(hrEmployee,UUID.randomUUID(),"ENG",null,"STAFF",earlier,null,hr);
        UserAccount managerAccount=account("manager");
        roles.saveAndFlush(new RoleAssignment(managerAccount.getId(),HrisRole.MANAGER,"ENG",earlier,earlier));
        manager=JwtAuthFixture.named(ISSUER,managerAccount.getOidcSubject());
        managerEmployee=employee(managerAccount);
        employees.addAssignment(managerEmployee,UUID.randomUUID(),"ENG",null,"MANAGER",earlier,null,hr);
        UserAccount memberAccount=account("member");
        memberRole=roles.saveAndFlush(new RoleAssignment(memberAccount.getId(),HrisRole.EMPLOYEE,null,earlier,earlier));
        member=JwtAuthFixture.named(ISSUER,memberAccount.getOidcSubject());
        employee=employee(memberAccount);
        employees.updateEmploymentDates(employee,DATE.minusDays(30),null,hr);
        employees.addAssignment(employee,UUID.randomUUID(),"ENG",managerEmployee,"STAFF",earlier,null,hr);
        List<ScheduleDayPayload> days=new ArrayList<>();
        for(int day=1;day<=7;day++) days.add(new ScheduleDayPayload(day,LocalTime.of(9,0),LocalTime.of(17,0),0));
        UUID schedule=calendars.publishSchedule(new PublishSchedulePayload(UUID.randomUUID(),"Asia/Jakarta",days),hr).publicId();
        UUID calendar=calendars.publishCalendar(new PublishCalendarPayload(UUID.randomUUID(),"JKT",List.of()),hr).publicId();
        assign(schedule,true);
        assign(calendar,false);
        as(hr);
        UUID rules=commands.publish(hr,key(),new PublishRules(null,List.of(new Rule(UUID.randomUUID(),1,"ENG",null)))).publicId();
        commands.activate(hr,key(),new ActivateRules(rules,"Activate review rules"));
    }

    private UserAccount account(String prefix) {
        return accounts.saveAndFlush(new UserAccount(UUID.randomUUID(),ISSUER,prefix+UUID.randomUUID(),START.minusSeconds(86400*30L)));
    }
    private UUID employee(UserAccount account) {
        UUID id=employees.create(UUID.randomUUID(),"EMP-"+UUID.randomUUID(),hr).employeeId();
        var row=employeeRows.findByPublicId(id).orElseThrow();
        row.linkAccount(account.getId()); employeeRows.saveAndFlush(row);
        bindings.saveAndFlush(new AccountBinding(row.getId(),id,account.getId(),ISSUER,account.getOidcSubject(),START.minusSeconds(86400*30L)));
        return id;
    }
    private void assign(UUID revision,boolean schedule) {
        LocalDate from=DATE.minusDays(20);
        String fingerprint=calendars.previewImpact(new PreviewPayload(schedule?revision:null,schedule?null:revision,
            from,from.plusDays(366),List.of(employee),null,null),hr).fingerprint();
        var payload=new AssignmentPayload(UUID.randomUUID(),employee,revision,from,null,null,"Initial context",fingerprint);
        if(schedule) calendars.assignSchedule(payload,hr); else calendars.assignCalendar(payload,hr);
    }
    private static String key() { return UUID.randomUUID().toString(); }
    private static void as(Authentication caller) { SecurityContextHolder.getContext().setAuthentication(caller); }
    private CorrectionAction action(CorrectionResult result,UUID evidence,String reason,Instant in,Instant out) {
        return new CorrectionAction(result.publicId(),result.version(),result.revisionId(),evidence,null,reason,in,out);
    }
    private CorrectionResult draft() {
        clock.at(START.plusSeconds(8*3600)); as(member);
        return commands.create(member,key(),new CorrectionPayload(null,DATE,START.minusSeconds(300),START.plusSeconds(8*3600),"Missing original evidence"));
    }

    @AfterEach
    void clearCaller() { SecurityContextHolder.clearContext(); }

    @Test
    void completeClockRecordsActionsAndDuplicateInclusiveSixtySeconds() {
        as(member);
        var in=commands.clock(member,key(),new ClockPayload(Action.IN),clock.instant());
        clock.at(START.plusSeconds(60));
        var duplicate=commands.clock(member,key(),new ClockPayload(Action.IN),clock.instant());
        assertTrue(duplicate.duplicate()); assertEquals(in.publicId(),duplicate.publicId());
        clock.at(START.plusSeconds(61));
        assertThrows(WorkContextException.class,()->commands.clock(member,key(),new ClockPayload(Action.IN),clock.instant()));
        clock.at(START.plusSeconds(8*3600));
        var out=commands.clock(member,key(),new ClockPayload(Action.OUT),clock.instant());
        assertFalse(out.duplicate()); assertEquals(in.occurrencePublicId(),out.occurrencePublicId());
        clock.at(START.plusSeconds(8*3600+60));
        assertTrue(commands.clock(member,key(),new ClockPayload(Action.OUT),clock.instant()).duplicate());
        clock.at(START.plusSeconds(8*3600+61));
        assertThrows(WorkContextException.class,()->commands.clock(member,key(),new ClockPayload(Action.OUT),clock.instant()));
    }

    @Test
    void outWithoutInCannotCreateEvidence() {
        as(member);
        assertThrows(WorkContextException.class,()->commands.clock(member,key(),new ClockPayload(Action.OUT),clock.instant()));
        assertEquals(0,jdbc.queryForObject("SELECT count(e.id) FROM clock_event e JOIN employees p ON p.id=e.employee_id WHERE p.public_id=?",Integer.class,employee));
    }

    @Test
    void correctionSubmitManagerApprovalAppendsEffectiveEvidence() {
        var draft=draft();
        var submitted=commands.act(member,key(),"SUBMIT",action(draft,null,null,null,null));
        assertEquals("PENDING",submitted.status());
        as(manager);
        var approved=commands.act(manager,key(),"APPROVE",action(submitted,null,null,null,null));
        assertEquals("APPROVED",approved.status());
        assertEquals(1,jdbc.queryForObject("SELECT count(r.id) FROM attendance_effective_revision r JOIN attendance_occurrence o ON o.id=r.occurrence_id JOIN employees p ON p.id=o.employee_id WHERE p.public_id=?",Integer.class,employee));
    }

    @Test
    void correctionReturnEditResubmitThenWithdrawKeepsPriorRevision() {
        var draft=draft();
        var pending=commands.act(member,key(),"SUBMIT",action(draft,null,null,null,null));
        as(manager);
        var returned=commands.act(manager,key(),"RETURN",action(pending,null,"Please correct evidence",null,null));
        as(member);
        var edited=commands.act(member,key(),"EDIT",action(returned,null,"Updated evidence",START.minusSeconds(240),START.plusSeconds(8*3600)));
        var resubmitted=commands.act(member,key(),"RESUBMIT",action(edited,null,null,null,null));
        assertFalse(pending.revisionId().equals(resubmitted.revisionId()));
        var firstHistory=corrections.history(resubmitted.publicId(),"REVISIONS",null,1,member);
        assertEquals(1,firstHistory.rows().size()); assertTrue(firstHistory.nextAfter()!=null);
        var secondHistory=corrections.history(resubmitted.publicId(),"REVISIONS",firstHistory.nextAfter(),1,member);
        assertEquals(1,secondHistory.rows().size()); assertEquals(null,secondHistory.nextAfter());
        var withdrawn=commands.act(member,key(),"WITHDRAW",action(resubmitted,null,null,null,null));
        assertEquals("WITHDRAWN",withdrawn.status());
        assertEquals(2,jdbc.queryForObject("SELECT count(r.id) FROM attendance_correction_revision r JOIN attendance_correction c ON c.id=r.correction_id WHERE c.public_id=?",Integer.class,draft.publicId()));
    }

    @Test
    void correctionListAndHistoryAreBoundedAndScopedToOwnerReviewerOrHr() {
        var draft=draft();
        assertEquals(1,corrections.list(employee,DATE,DATE,null,0,10,member).rows().size());
        assertEquals(0,corrections.history(draft.publicId(),"REVISIONS",null,10,member).rows().size());
        assertThrows(IllegalArgumentException.class,()->corrections.list(employee,DATE,DATE,null,0,0,member));
        assertThrows(IllegalArgumentException.class,()->corrections.list(employee,DATE,DATE,"UNKNOWN",0,10,member));
        assertThrows(IllegalArgumentException.class,()->corrections.list(employee,DATE,DATE,null,-1,10,member));
        assertThrows(IllegalArgumentException.class,()->corrections.list(employee,DATE,DATE,null,0,101,member));
        assertThrows(IllegalArgumentException.class,()->corrections.list(employee,null,DATE,null,0,10,member));
        assertThrows(IllegalArgumentException.class,()->corrections.list(employee,DATE,DATE.minusDays(1),null,0,10,member));
        assertThrows(IllegalArgumentException.class,()->corrections.list(employee,DATE,DATE.plusDays(366),null,0,10,member));
        assertThrows(IllegalArgumentException.class,()->corrections.history(draft.publicId(),"UNKNOWN",null,10,member));
        assertThrows(IllegalArgumentException.class,()->corrections.history(draft.publicId(),"REVISIONS",null,101,member));

        var pending=commands.act(member,key(),"SUBMIT",action(draft,null,null,null,null));
        assertEquals(1,corrections.history(pending.publicId(),"REVISIONS",null,10,member).rows().size());
        assertEquals(1,corrections.list(employee,DATE,DATE,"PENDING",0,10,manager).rows().size());
        assertEquals(1,corrections.history(pending.publicId(),"ASSIGNMENTS",null,10,manager).rows().size());
        as(manager);
        commands.act(manager,key(),"RETURN",action(pending,null,"Correct the evidence",null,null));
        assertEquals(1,corrections.history(pending.publicId(),"DECISIONS",null,10,member).rows().size());
        assertThrows(ResourceNotFoundException.class,
            ()->corrections.history(pending.publicId(),"REVISIONS",null,10,manager));
        assertEquals(1,corrections.list(employee,DATE,DATE,null,0,10,hr).rows().size());
    }

    @Test
    void correctionCreationRejectsIncompleteIdentityAndProposal() {
        as(member);
        assertThrows(IllegalArgumentException.class,()->corrections.create(
            new CorrectionPayload(null,DATE,START.minusSeconds(300),START.plusSeconds(8*3600),"Reason"),member));
        assertThrows(IllegalArgumentException.class,()->corrections.create(
            new CorrectionPayload(UUID.randomUUID(),null,START.minusSeconds(300),START.plusSeconds(8*3600),"Reason"),member));
        assertThrows(IllegalArgumentException.class,()->corrections.create(
            new CorrectionPayload(UUID.randomUUID(),DATE,null,START.plusSeconds(8*3600),"Reason"),member));
        assertThrows(IllegalArgumentException.class,()->corrections.create(
            new CorrectionPayload(UUID.randomUUID(),DATE,START.minusSeconds(300),START.plusSeconds(8*3600)," "),member));
    }

    @Test
    void clockAndCorrectionRequireEmploymentOnTheWorkDate() {
        as(member);
        employees.updateEmploymentDates(employee,DATE.plusDays(1),null,hr);
        assertThrows(IllegalArgumentException.class,
            ()->commands.clock(member,key(),new ClockPayload(Action.IN),clock.instant()));
        assertThrows(IllegalArgumentException.class,
            ()->corrections.create(new CorrectionPayload(UUID.randomUUID(),DATE,START.minusSeconds(300),
                START.plusSeconds(8*3600),"Outside employment"),member));

        employees.updateEmploymentDates(employee,DATE.minusDays(30),DATE.minusDays(1),hr);
        assertThrows(IllegalArgumentException.class,
            ()->commands.clock(member,key(),new ClockPayload(Action.IN),clock.instant()));
        assertThrows(IllegalArgumentException.class,
            ()->corrections.create(new CorrectionPayload(UUID.randomUUID(),DATE,START.minusSeconds(300),
                START.plusSeconds(8*3600),"After employment"),member));
    }

    @Test
    void attendanceReviewerConfigurationFailsClosedAndChecksFallbackEligibility() {
        var employeeRow=employeeRows.findByPublicId(employee).orElseThrow();
        assertThrows(IllegalArgumentException.class,()->reviewers.publish(new PublishRules(UUID.randomUUID(),List.of()),hr));
        assertThrows(IllegalArgumentException.class,()->reviewers.publish(new PublishRules(UUID.randomUUID(),java.util.Collections.singletonList(null)),hr));
        UUID duplicateId=UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,()->reviewers.publish(new PublishRules(UUID.randomUUID(),List.of(
            new Rule(duplicateId,1,"ENG",null),new Rule(duplicateId,2,"ENG",null))),hr));
        assertThrows(IllegalArgumentException.class,()->reviewers.publish(new PublishRules(UUID.randomUUID(),
            java.util.Collections.nCopies(101,new Rule(UUID.randomUUID(),1,"ENG",null))),hr));
        assertThrows(ResourceNotFoundException.class,()->reviewers.publish(new PublishRules(UUID.randomUUID(),List.of(
            new Rule(UUID.randomUUID(),1,"ENG",UUID.randomUUID()))),hr));

        var unmatched=reviewers.publish(new PublishRules(UUID.randomUUID(),List.of(new Rule(UUID.randomUUID(),1,"FIN",null))),hr);
        reviewers.activate(new ActivateRules(unmatched.publicId(),"No matching employee unit"),hr);
        assertEquals("NO_MATCHING_RULE",reviewers.resolve(employeeRow,START).blockedReason());
        var duplicatePriority=reviewers.publish(new PublishRules(UUID.randomUUID(),List.of(
            new Rule(UUID.randomUUID(),1,"ENG",null),new Rule(UUID.randomUUID(),1,"ENG",null))),hr);
        reviewers.activate(new ActivateRules(duplicatePriority.publicId(),"Ambiguous matching rules"),hr);
        assertEquals("AMBIGUOUS_RULE",reviewers.resolve(employeeRow,START).blockedReason());

        var designated=reviewers.publish(new PublishRules(UUID.randomUUID(),List.of(
            new Rule(UUID.randomUUID(),2,"ENG",hrPublicId))),hr);
        reviewers.activate(new ActivateRules(designated.publicId(),"Designate scoped HR fallback"),hr);
        var route=reviewers.resolve(employeeRow,START);
        assertEquals(null,route.blockedReason());
        var managerAccount=accounts.findByOidcIssuerAndOidcSubject(ISSUER,manager.getName()).orElseThrow();
        var hrAccount=accounts.findByOidcIssuerAndOidcSubject(ISSUER,hr.getName()).orElseThrow();
        assertTrue(reviewers.eligible(hrAccount,employeeRow,START,true));
        assertFalse(reviewers.eligible(hrAccount,employeeRow,START,false));
        assertFalse(reviewers.eligible(managerAccount,
            employeeRows.findByPublicId(managerEmployee).orElseThrow(),START,false));
        assertThrows(IllegalArgumentException.class,()->reviewers.activate(new ActivateRules(designated.publicId()," "),hr));
    }
    @Test
    void attendanceReviewerPublicationRejectsMalformedRulesAndUncoveredHr() {
        UUID repeated=UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,()->reviewers.publish(new PublishRules(null,List.of(
            new Rule(repeated,1,"ENG",null))),hr));
        assertThrows(IllegalArgumentException.class,()->reviewers.publish(new PublishRules(UUID.randomUUID(),List.of(
            new Rule(repeated,1,"ENG",null),new Rule(repeated,2,"FIN",null))),hr));
        assertThrows(IllegalArgumentException.class,()->reviewers.publish(new PublishRules(UUID.randomUUID(),List.of(
            new Rule(UUID.randomUUID(),1," ",null))),hr));
        assertThrows(ResourceNotFoundException.class,()->reviewers.publish(new PublishRules(UUID.randomUUID(),List.of(
            new Rule(UUID.randomUUID(),1,"ENG",UUID.randomUUID()))),hr));
        assertThrows(AccessDeniedException.class,()->reviewers.publish(new PublishRules(UUID.randomUUID(),List.of(
            new Rule(UUID.randomUUID(),1,"ENG",null))),manager));
        UUID activeRule=jdbc.queryForObject("SELECT rule_set_id FROM attendance_rule_activation ORDER BY id DESC LIMIT 1",UUID.class);
        assertThrows(AccessDeniedException.class,()->reviewers.activate(new ActivateRules(activeRule,"Not organization-wide"),manager));
        var managerAccount=accounts.findByOidcIssuerAndOidcSubject(ISSUER,manager.getName()).orElseThrow();
        assertFalse(reviewers.eligible(managerAccount,employeeRows.findByPublicId(employee).orElseThrow(),START,true));
    }

    @Test
    void revokedRoleCannotReplayPriorClockReceipt() {
        as(member); String commandKey=key();
        commands.clock(member,commandKey,new ClockPayload(Action.IN),clock.instant());
        memberRole.endAt(START); roles.saveAndFlush(memberRole);
        assertThrows(ResourceNotFoundException.class,()->commands.clock(member,commandKey,new ClockPayload(Action.IN),clock.instant()));
    }
    @Test
    void effectiveEvidenceChangePreventsStaleApprovalButAllowsReturn() {
        as(member);
        commands.clock(member,key(),new ClockPayload(Action.IN),clock.instant());
        var draft=draft();
        UUID baseline=jdbc.queryForObject("SELECT r.public_id FROM attendance_effective_revision r JOIN attendance_occurrence o ON o.id=r.occurrence_id JOIN employees p ON p.id=o.employee_id WHERE p.public_id=? ORDER BY r.id DESC LIMIT 1",UUID.class,employee);
        var pending=commands.act(member,key(),"SUBMIT",action(draft,baseline,null,null,null));
        commands.clock(member,key(),new ClockPayload(Action.OUT),clock.instant());
        as(manager);
        assertThrows(WorkContextException.class,()->commands.act(manager,key(),"APPROVE",action(pending,null,null,null,null)));
        assertEquals("RETURNED",commands.act(manager,key(),"RETURN",action(pending,null,"Evidence changed",null,null)).status());
    }

    @Test
    void reassignRevokesOriginalReviewerAndNewReviewerCanApprove() {
        var draft=draft();
        var pending=commands.act(member,key(),"SUBMIT",action(draft,null,null,null,null));
        UserAccount replacement=account("replacement-manager");
        Instant earlier=START.minusSeconds(86400*30L);
        roles.saveAndFlush(new RoleAssignment(replacement.getId(),HrisRole.MANAGER,"ENG",earlier,earlier));
        UUID replacementEmployee=employee(replacement);
        employees.addAssignment(replacementEmployee,UUID.randomUUID(),"ENG",null,"MANAGER",earlier,null,hr);
        as(hr);
        var changed=commands.act(hr,key(),"REASSIGN",new CorrectionAction(pending.publicId(),pending.version(),pending.revisionId(),null,replacement.getPublicId(),"Reviewer replacement",null,null));
        as(manager);
        assertThrows(ResourceNotFoundException.class,()->commands.act(manager,key(),"APPROVE",action(changed,null,null,null,null)));
        Authentication newManager=JwtAuthFixture.named(ISSUER,replacement.getOidcSubject()); as(newManager);
        assertEquals("APPROVED",commands.act(newManager,key(),"APPROVE",action(changed,null,null,null,null)).status());
    }

    @Test
    void blockedCorrectionRouteCanBeRetriedAfterReviewerConfigurationReturns() {
        as(hr);
        UUID noMatch=commands.publish(hr,key(),new PublishRules(UUID.randomUUID(),List.of(
            new Rule(UUID.randomUUID(),1,"FIN",null)))).publicId();
        commands.activate(hr,key(),new ActivateRules(noMatch,"Temporarily unavailable reviewer route"));
        var draft=draft();
        var pending=commands.act(member,key(),"SUBMIT",action(draft,null,null,null,null));
        assertEquals("PENDING",pending.status());
        assertEquals("REVIEWER_UNAVAILABLE",pending.blockedReason());

        as(hr);
        UUID rules=commands.publish(hr,key(),new PublishRules(UUID.randomUUID(),List.of(
            new Rule(UUID.randomUUID(),1,"ENG",null)))).publicId();
        commands.activate(hr,key(),new ActivateRules(rules,"Restore reviewer routing"));
        var retried=commands.act(hr,key(),"ROUTE_RETRY",
            action(pending,null,"Retry after restoring reviewer configuration",null,null));
        assertEquals("PENDING",retried.status());
        assertEquals(null,retried.blockedReason());
        as(manager);
        assertEquals("APPROVED",commands.act(manager,key(),"APPROVE",action(retried,null,null,null,null)).status());
    }

    @Test
    void cutoffPreventsClockReceiptReplay() {
        as(member); String commandKey=key();
        commands.clock(member,commandKey,new ClockPayload(Action.IN),clock.instant());
        UserAccount account=accounts.findByOidcIssuerAndOidcSubject(ISSUER,member.getName()).orElseThrow();
        account.applyCutoff(START,LocalTime.of(9,0),"Asia/Jakarta",START);
        accounts.saveAndFlush(account);
        assertThrows(AccessDeniedException.class,()->commands.clock(member,commandKey,new ClockPayload(Action.IN),clock.instant()));
    }

    @Test
    void reboundAccountCannotReplayClockForFormerEmployee() {
        as(member); String commandKey=key();
        commands.clock(member,commandKey,new ClockPayload(Action.IN),clock.instant());
        UserAccount account=accounts.findByOidcIssuerAndOidcSubject(ISSUER,member.getName()).orElseThrow();
        var old=bindings.findByAccountIdAndRevokedAtIsNull(account.getId()).orElseThrow();
        old.revoke(START); bindings.saveAndFlush(old);
        employee(account);
        assertThrows(ResourceNotFoundException.class,()->commands.clock(member,commandKey,new ClockPayload(Action.IN),clock.instant()));
    }

}
