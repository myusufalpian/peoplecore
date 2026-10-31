package id.mydev.peoplecore.leave;

import id.mydev.peoplecore.attendance.application.command.WorkCalendarPayloads.AssignmentPayload;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarPayloads.HolidayPayload;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarPayloads.PreviewPayload;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarPayloads.PublishCalendarPayload;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarPayloads.PublishSchedulePayload;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarPayloads.ScheduleDayPayload;
import id.mydev.peoplecore.attendance.application.service.WorkCalendarService;
import id.mydev.peoplecore.identity.domain.model.AccountBinding;
import id.mydev.peoplecore.identity.domain.model.HrisRole;
import id.mydev.peoplecore.identity.domain.model.RoleAssignment;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.identity.domain.repository.AccountBindingRepository;
import id.mydev.peoplecore.identity.domain.repository.RoleAssignmentRepository;
import id.mydev.peoplecore.identity.domain.repository.UserAccountRepository;
import id.mydev.peoplecore.leave.application.command.LeaveWorkflowCommands;
import id.mydev.peoplecore.leave.application.command.LeaveWorkflowModels.Action;
import id.mydev.peoplecore.leave.application.command.LeaveWorkflowModels.Draft;
import id.mydev.peoplecore.leave.application.command.LeaveWorkflowModels.Portion;
import id.mydev.peoplecore.leave.application.command.LeaveWorkflowModels.Publish;
import id.mydev.peoplecore.leave.application.command.LeaveWorkflowModels.RequestView;
import id.mydev.peoplecore.leave.application.command.LeaveWorkflowModels.Rule;
import id.mydev.peoplecore.leave.application.service.LeaveConflictException;
import id.mydev.peoplecore.leave.application.service.LeaveEntitlementService;
import id.mydev.peoplecore.leave.application.service.LeaveEntitlementService.GrantPayload;
import id.mydev.peoplecore.leave.application.service.LeaveRoutingService;
import id.mydev.peoplecore.leave.application.service.LeaveWorkflowService;
import id.mydev.peoplecore.organization.application.service.EmployeeService;
import id.mydev.peoplecore.support.JwtAuthFixture;
import id.mydev.peoplecore.support.PostgresFixture;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("postgres")
@SpringBootTest(properties={"peoplecore.runtime.role=api","spring.jpa.hibernate.ddl-auto=validate","peoplecore.organization.allowed-units=ENG,FIN"})
class LeaveWorkflowPostgresTest {
    private static final String ISSUER="https://leave.workflow.test";
    private static final PostgresFixture DATABASE=new PostgresFixture();
    static { DATABASE.flyway().migrate(); DATABASE.grantRuntime(); }
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->DATABASE.url);
        registry.add("spring.datasource.username",()->DATABASE.runtimeUsername);
        registry.add("spring.datasource.password",()->DATABASE.runtimePassword);
    }
    @Autowired LeaveWorkflowCommands commands;
    @Autowired LeaveWorkflowService workflow;
    @Autowired LeaveRoutingService routing;
    @Autowired LeaveEntitlementService entitlement;
    @Autowired WorkCalendarService calendars;
    @Autowired EmployeeService employees;
    @Autowired UserAccountRepository accounts;
    @Autowired RoleAssignmentRepository roles;
    @Autowired AccountBindingRepository bindings;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    private Authentication hr;
    private Authentication owner;
    private Authentication manager;
    private UUID hrId;
    private UUID employee;
    private UUID managerEmployee;
    private LocalDate date;

    @BeforeEach void setup() {
        Instant start=Instant.now().minusSeconds(3600);
        UserAccount hrAccount=account(HrisRole.HR_ADMIN,start); hrId=hrAccount.getPublicId(); hr=token(hrAccount);
        UUID hrEmployee=employees.create(UUID.randomUUID(),"HR-"+UUID.randomUUID(),hr).employeeId();
        bind(hrAccount,hrEmployee,start);
        employees.addAssignment(hrEmployee,UUID.randomUUID(),"ENG",null,"STAFF",start,null,hr);
        UserAccount managerAccount=account(HrisRole.MANAGER,start); manager=token(managerAccount);
        managerEmployee=employees.create(UUID.randomUUID(),"MGR-"+UUID.randomUUID(),hr).employeeId();
        bind(managerAccount,managerEmployee,start);
        employees.addAssignment(managerEmployee,UUID.randomUUID(),"ENG",null,"STAFF",start,null,hr);
        UserAccount member=account(HrisRole.EMPLOYEE,start); owner=token(member);
        employee=employees.create(UUID.randomUUID(),"EMP-"+UUID.randomUUID(),hr).employeeId(); bind(member,employee,start);
        employees.addAssignment(employee,UUID.randomUUID(),"ENG",managerEmployee,"STAFF",start,null,hr);
        date=LocalDate.now().plusDays(10);
        if(date.getYear()!=LocalDate.now().getYear()) date=LocalDate.of(LocalDate.now().getYear()+1,1,10);
        UUID schedule=calendars.publishSchedule(new PublishSchedulePayload(UUID.randomUUID(),"Asia/Jakarta",IntStream.rangeClosed(1,7).mapToObj(i->new ScheduleDayPayload(i,LocalTime.of(9,0),LocalTime.of(17,0),0)).toList()),hr).publicId();
        UUID calendar=calendars.publishCalendar(new PublishCalendarPayload(UUID.randomUUID(),"JKT",List.of()),hr).publicId();
        String fingerprint=calendars.previewImpact(new PreviewPayload(schedule,null,date.minusDays(1),date.plusDays(30),List.of(employee),null,null),hr).fingerprint();
        calendars.assignSchedule(new AssignmentPayload(UUID.randomUUID(),employee,schedule,date.minusDays(1),date.plusDays(30),null,null,fingerprint),hr);
        fingerprint=calendars.previewImpact(new PreviewPayload(null,calendar,date.minusDays(1),date.plusDays(30),List.of(employee),null,null),hr).fingerprint();
        calendars.assignCalendar(new AssignmentPayload(UUID.randomUUID(),employee,calendar,date.minusDays(1),date.plusDays(30),null,null,fingerprint),hr);
        new TransactionTemplate(transactions).executeWithoutResult(status->entitlement.opening(new GrantPayload(employee,date.getYear(),20,"opening","HR","Annual opening"),hr));
        authenticate(hr);
        var config=commands.publish(hr,key(),new Publish(List.of(new Rule("eng",1,"ENG",hrId,hrId))));
        commands.activate(hr,key(),config.publicId());
    }

    @Test void fullDayReservesManagerDoesNotConsumeThenHrConsumes() {
        RequestView request=submit(Portion.FULL,date);
        assertEquals(2,balance().reserved()); assertEquals(0,balance().consumed());
        authenticate(manager); request=commands.act(manager,key(),request.publicId(),"APPROVE",action(request.version(),null));
        assertEquals("PENDING",request.status()); assertEquals(2,balance().reserved()); assertEquals(0,balance().consumed());
        authenticate(hr); request=commands.act(hr,key(),request.publicId(),"APPROVE",action(request.version(),null));
        assertEquals("APPROVED",request.status()); assertEquals(0,balance().reserved()); assertEquals(2,balance().consumed());
        assertEquals(2,workflow.portions(request.publicId(),owner).size()); assertEquals(2,workflow.history(request.publicId(),owner).size());
    }
    @Test void distinctHalvesCoexistButFullDayOverlaps() {
        submit(Portion.FIRST_HALF,date); submit(Portion.SECOND_HALF,date);
        assertEquals(2,balance().reserved());
        authenticate(owner); var draft=commands.create(owner,key(),draft(Portion.FULL,date));
        assertThrows(LeaveConflictException.class,()->commands.act(owner,key(),draft.publicId(),"SUBMIT",action(draft.version(),null)));
        assertEquals(2,balance().reserved());
    }
    @Test void returnReleasesAndResubmitCreatesNewRevisionAndBothSteps() {
        RequestView request=submit(Portion.FULL,date);
        authenticate(manager); request=commands.act(manager,key(),request.publicId(),"RETURN",action(request.version(),"Please amend"));
        assertEquals("RETURNED",request.status()); assertEquals(0,balance().reserved());
        authenticate(owner); request=commands.act(owner,key(),request.publicId(),"RESUBMIT",action(request.version(),null));
        assertEquals(2,request.revision()); assertEquals(2,balance().reserved());
        authenticate(hr); RequestView current=request;
        assertThrows(org.springframework.security.access.AccessDeniedException.class,()->commands.act(hr,key(),current.publicId(),"APPROVE",action(current.version(),null)));
    }
    @Test void withdrawReleasesOnlyOwnReservation() {
        RequestView first=submit(Portion.FULL,date); submit(Portion.FULL,date.plusDays(1));
        authenticate(owner); commands.act(owner,key(),first.publicId(),"WITHDRAW",action(first.version(),null));
        assertEquals(2,balance().reserved());
    }
    @Test void cancellationPendingRejectedAndWithdrawnPreserveConsumptionThenApprovalCreditsOnce() {
        RequestView request=approve(submit(Portion.FULL,date)); authenticate(owner);
        var cancellation=commands.createCancellation(owner,key(),request.publicId(),action(request.version(),"Cancel whole request"));
        assertEquals(2,balance().consumed());
        authenticate(hr); commands.actCancellation(hr,key(),request.publicId(),cancellation.publicId(),"REJECT",action(cancellation.version(),"Retain approved leave"));
        assertEquals(2,balance().consumed());
        request=workflow.get(request.publicId(),owner); authenticate(owner);
        cancellation=commands.createCancellation(owner,key(),request.publicId(),action(request.version(),"Try again"));
        commands.actCancellation(owner,key(),request.publicId(),cancellation.publicId(),"WITHDRAW",action(cancellation.version(),null));
        assertEquals(2,balance().consumed());
        request=workflow.get(request.publicId(),owner);
        cancellation=commands.createCancellation(owner,key(),request.publicId(),action(request.version(),"Final cancel"));
        authenticate(hr); String approvalKey=key();
        var result=commands.actCancellation(hr,approvalKey,request.publicId(),cancellation.publicId(),"APPROVE",action(cancellation.version(),null));
        assertEquals("APPROVED",result.status()); assertEquals(0,balance().consumed()); assertEquals(20,balance().available());
        var replay=commands.actCancellation(hr,approvalKey,request.publicId(),cancellation.publicId(),"APPROVE",action(cancellation.version(),null));
        assertEquals(result,replay); assertEquals("CANCELLED",workflow.get(request.publicId(),owner).status());
    }
    @Test void blockedRouteStillReservesAndWithdrawReleases() {
        jdbc.update("DELETE FROM leave_rule_current");
        RequestView request=submit(Portion.FULL,date);
        assertNotNull(request.blockedReason()); assertEquals(2,balance().reserved());
        authenticate(owner); commands.act(owner,key(),request.publicId(),"WITHDRAW",action(request.version(),null));
        assertEquals(0,balance().reserved());
    }
    @Test void retryRouteRecoversPendingRequestAfterConfigurationReturns() {
        Long config=jdbc.queryForObject("SELECT rule_set_id FROM leave_rule_current WHERE id=1",Long.class);
        jdbc.update("DELETE FROM leave_rule_current");
        RequestView request=submit(Portion.FULL,date);
        assertNotNull(request.blockedReason());
        authenticate(owner);
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
            ()->commands.act(owner,key(),request.publicId(),"RETRY_ROUTE",action(request.version(),"Retry")));
        jdbc.update("INSERT INTO leave_rule_current(id,rule_set_id) VALUES (1,?)",config);
        authenticate(hr);
        RequestView retried=commands.act(hr,key(),request.publicId(),"RETRY_ROUTE",action(request.version(),"Configuration restored"));
        assertEquals("PENDING",retried.status());
        assertEquals(null,retried.blockedReason());
        assertEquals(2,balance().reserved());
    }
    @Test void activeHrReviewCanBeReassignedToAnotherEligibleHr() {
        RequestView request=submit(Portion.FULL,date);
        authenticate(manager);
        request=commands.act(manager,key(),request.publicId(),"APPROVE",action(request.version(),null));
        UserAccount replacement=account(HrisRole.HR_ADMIN,Instant.now().minusSeconds(3600));
        UUID replacementEmployee=employees.create(UUID.randomUUID(),"HR-REASSIGN-"+key(),hr).employeeId();
        bind(replacement,replacementEmployee,Instant.now().minusSeconds(3600));
        employees.addAssignment(replacementEmployee,UUID.randomUUID(),"ENG",null,"STAFF",Instant.now().minusSeconds(3600),null,hr);
        authenticate(hr);
        request=commands.act(hr,key(),request.publicId(),"REASSIGN",new Action(employee,request.version(),
            "Replace active HR reviewer",replacement.getPublicId(),2,false));
        RequestView current=request;
        assertThrows(id.mydev.peoplecore.common.api.ResourceNotFoundException.class,
            ()->commands.act(hr,key(),current.publicId(),"APPROVE",action(current.version(),null)));
        authenticate(token(replacement));
        request=commands.act(token(replacement),key(),request.publicId(),"APPROVE",action(request.version(),null));
        assertEquals("APPROVED",request.status());
        assertEquals(2,balance().consumed());
    }
    @Test void missingOrganizationUnitBlocksManagerRoute() {
        var route=routing.resolveWithManager(employee,null,managerEmployee,Instant.now());
        assertEquals("NO_ORG_UNIT",route.blockedReason());
        assertEquals(null,route.managerAccountPublicId());
    }
    @Test void routingRejectsMissingAmbiguousAndSelfReviewers() {
        Instant now=Instant.now();
        assertEquals("NO_MATCH",routing.resolveWithManager(employee,"FIN",managerEmployee,now).blockedReason());
        assertEquals("NO_DESIGNATED_REVIEWER",routing.resolveWithManager(employee,"ENG",UUID.randomUUID(),now).blockedReason());
        assertEquals("REVIEWER_INELIGIBLE",routing.resolveWithManager(employee, "ENG",employee,now).blockedReason());

        Long configuration=jdbc.queryForObject("SELECT rule_set_id FROM leave_rule_current WHERE id=1",Long.class);
        jdbc.update("INSERT INTO leave_rule(rule_set_id,rule_id,priority,org_unit,hr_account_public_id,cancellation_account_public_id) "
            + "SELECT rule_set_id,'duplicate-eng',priority,org_unit,hr_account_public_id,cancellation_account_public_id "
            + "FROM leave_rule WHERE rule_set_id=? AND rule_id='eng'",configuration);
        assertEquals("AMBIGUOUS_RULE",routing.resolveWithManager(employee,"ENG",managerEmployee,now).blockedReason());

        assertTrue(routing.eligible(hrId,employee,"ENG",2,false,now));
        assertFalse(routing.eligible(hrId,employee,"ENG",1,false,now));
        assertTrue(routing.samePerson(hrId,hrId));
        assertFalse(routing.samePerson(hrId,null));
    }
    @Test void publishRejectsInvalidDesignatedReviewerAndConfigurationShape() throws Exception {
        assertThrows(IllegalArgumentException.class,()->new TransactionTemplate(transactions).executeWithoutResult(status -> routing.publish(UUID.randomUUID(),new Publish(null),hr)));
        assertThrows(IllegalArgumentException.class,()->new TransactionTemplate(transactions).executeWithoutResult(status -> routing.publish(UUID.randomUUID(),new Publish(List.of()),hr)));
        assertThrows(IllegalArgumentException.class,()->new TransactionTemplate(transactions).executeWithoutResult(status -> routing.publish(UUID.randomUUID(),new Publish(java.util.Collections.singletonList(null)),hr)));
        Rule repeated=new Rule("repeated",1,"ENG",hrId,hrId);
        assertThrows(IllegalArgumentException.class,()->new TransactionTemplate(transactions).executeWithoutResult(status -> routing.publish(UUID.randomUUID(),new Publish(java.util.Collections.nCopies(101,repeated)),hr)));
        assertThrows(IllegalArgumentException.class,()->new TransactionTemplate(transactions).executeWithoutResult(status -> routing.publish(UUID.randomUUID(),new Publish(List.of(
            repeated,new Rule("repeated",2,"FIN",hrId,hrId))),hr)));
        assertThrows(IllegalArgumentException.class,()->new TransactionTemplate(transactions).executeWithoutResult(status -> routing.publish(UUID.randomUUID(),new Publish(List.of(
            new Rule("unknown",1,"ENG",UUID.randomUUID(),hrId))),hr)));
        assertThrows(IllegalArgumentException.class,()->workflow.verifyCommand("LEAVE_RULE_PUBLISH",
            mapper.readTree("{\"publication\":{\"rules\":[null]}}"),hr));
        assertThrows(IllegalArgumentException.class,()->workflow.verifyCommand("LEAVE_RULE_PUBLISH",mapper.readTree("{}"),hr));
        assertThrows(IllegalArgumentException.class,()->workflow.verifyCommand("LEAVE_RULE_PUBLISH",
            mapper.readTree("{\"publication\":{\"rules\":{}}}"),hr));
        assertThrows(IllegalArgumentException.class,()->workflow.verifyCommand("LEAVE_RULE_PUBLISH",
            mapper.readTree("{\"publication\":{\"rules\":[{}]}}"),hr));
        assertThrows(IllegalArgumentException.class,()->workflow.verifyCommand("LEAVE_RULE_PUBLISH",
            mapper.readTree("{\"publication\":{\"rules\":[{\"orgUnit\":7}]}}"),hr));
        workflow.verifyCommand("LEAVE_RULE_PUBLISH",mapper.readTree(
            "{\"publication\":{\"rules\":[{\"orgUnit\":\"ENG\"}]}}"),hr);
    }
    @Test void captureSkipsFullDayHolidaysAndRejectsPartialOrExpiredYearRequests() {
        LocalDate holiday=date;
        UUID calendar=calendars.publishCalendar(new PublishCalendarPayload(UUID.randomUUID(),"JKT",
            List.of(new HolidayPayload(holiday,"Local holiday"))),hr).publicId();
        Long employeeId=jdbc.queryForObject("SELECT id FROM employees WHERE public_id=?",Long.class,employee);
        UUID prior=jdbc.queryForObject("SELECT public_id FROM employee_calendar_assignment WHERE employee_id=? AND superseded=FALSE ORDER BY id LIMIT 1",UUID.class,employeeId);
        var preview=calendars.previewImpact(new PreviewPayload(null,calendar,holiday,holiday.plusDays(1),List.of(employee),prior,null),hr);
        calendars.assignCalendar(new AssignmentPayload(UUID.randomUUID(),employee,calendar,holiday,holiday.plusDays(1),prior,null,preview.fingerprint()),hr);

        authenticate(owner);
        RequestView holidayDraft=commands.create(owner,key(),new Draft(employee,holiday,holiday.plusDays(1),Portion.FULL,"Annual leave around a holiday"));
        RequestView acrossHoliday=commands.act(owner,key(),holidayDraft.publicId(),"SUBMIT",action(holidayDraft.version(),null));
        assertEquals(2,workflow.portions(acrossHoliday.publicId(),owner).size());
        assertTrue(workflow.portions(acrossHoliday.publicId(),owner).stream().allMatch(p->p.workDate().equals(holiday.plusDays(1))));
        RequestView partial=commands.create(owner,key(),draft(Portion.FIRST_HALF,holiday));
        assertThrows(IllegalArgumentException.class,()->commands.act(owner,key(),partial.publicId(),"SUBMIT",action(partial.version(),null)));
        LocalDate expired=LocalDate.of(date.getYear()-1,6,1);
        RequestView old=commands.create(owner,key(),draft(Portion.FULL,expired));
        assertThrows(IllegalArgumentException.class,()->commands.act(owner,key(),old.publicId(),"SUBMIT",action(old.version(),null)));
    }
    @Test void outboxTrailUsesResultingRequestStatus() {
        RequestView request=submit(Portion.FULL,date);
        String payload=jdbc.queryForObject("SELECT payload FROM outbox_events WHERE aggregate_public_id=? AND event_type='LEAVE_REQUEST_SUBMIT' ORDER BY id DESC LIMIT 1",String.class,request.publicId());
        assertTrue(payload.contains("\"status\":\"PENDING\""));
    }
    @Test void staleVersionsCannotChangeRequest() {
        RequestView request=submit(Portion.FULL,date); authenticate(owner);
        assertThrows(LeaveConflictException.class,()->commands.act(owner,key(),request.publicId(),"WITHDRAW",action(0,null)));
        assertEquals(2,balance().reserved());
    }
    @Test void foreignEmployeeCannotMutateOrRead() {
        RequestView request=submit(Portion.FULL,date); authenticate(manager);
        assertThrows(id.mydev.peoplecore.common.api.ResourceNotFoundException.class,()->commands.act(manager,key(),request.publicId(),"WITHDRAW",action(request.version(),null)));
        assertTrue(workflow.list(employee,owner).stream().anyMatch(r->r.publicId().equals(request.publicId())));
    }
    @Test void evidenceRowsCannotBeRewrittenByRuntime() {
        RequestView request=submit(Portion.FULL,date);
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE leave_portion SET half=2 WHERE request_id=(SELECT id FROM leave_request WHERE public_id=?)",request.publicId()));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE leave_revision SET half_units=10 WHERE request_id=(SELECT id FROM leave_request WHERE public_id=?)",request.publicId()));
    }
    @Test void completedManagerApprovalReplaysAfterActiveStepAdvances() {
        RequestView request=submit(Portion.FULL,date); authenticate(manager);
        String decisionKey=key(); Action payload=action(request.version(),null);
        RequestView result=commands.act(manager,decisionKey,request.publicId(),"APPROVE",payload);
        RequestView replay=commands.act(manager,decisionKey,request.publicId(),"APPROVE",payload);
        assertEquals(result,replay); assertEquals(0,balance().consumed());
    }
    @Test void reviewerAccountRebindingCannotBypassDistinctEmployeeIdentities() {
        RequestView request=submit(Portion.FULL,date); authenticate(manager);
        request=commands.act(manager,key(),request.publicId(),"APPROVE",action(request.version(),null));
        UserAccount hrAccount=accounts.findByPublicId(hrId).orElseThrow();
        var hrBinding=bindings.findByAccountIdAndRevokedAtIsNull(hrAccount.getId()).orElseThrow();
        var managerBinding=bindings.findByEmployeeIdAndRevokedAtIsNull(employees.requireInternalByPublicId(managerEmployee).getId()).orElseThrow();
        hrBinding.revoke(Instant.now()); managerBinding.revoke(Instant.now());
        bindings.saveAndFlush(hrBinding); bindings.saveAndFlush(managerBinding);
        bind(hrAccount,managerEmployee,Instant.now());
        authenticate(hr); RequestView current=request;
        assertThrows(org.springframework.security.access.AccessDeniedException.class,()->commands.act(hr,key(),current.publicId(),"APPROVE",action(current.version(),null)));
        assertEquals(0,balance().consumed()); assertEquals(2,balance().reserved());
        assertNotNull(workflow.get(request.publicId(),owner).blockedReason());
    }
    @Test void actualIntervalOverlapAcrossDifferentWorkDateLabelsIsRejected() {
        Instant midnight=date.atStartOfDay(java.time.ZoneId.of("Asia/Jakarta")).toInstant();
        pin(date,midnight.plusSeconds(23*3600),midnight.plusSeconds(31*3600));
        pin(date.plusDays(1),midnight.plusSeconds(30*3600),midnight.plusSeconds(38*3600));
        submit(Portion.SECOND_HALF,date);
        authenticate(owner); RequestView second=commands.create(owner,key(),draft(Portion.FIRST_HALF,date.plusDays(1)));
        assertThrows(LeaveConflictException.class,()->commands.act(owner,key(),second.publicId(),"SUBMIT",action(second.version(),null)));
        assertEquals(1,balance().reserved());
    }
    @Test void touchingIntervalsAcrossDifferentDatesMayCoexist() {
        Instant midnight=date.atStartOfDay(java.time.ZoneId.of("Asia/Jakarta")).toInstant();
        pin(date,midnight.plusSeconds(23*3600),midnight.plusSeconds(31*3600));
        pin(date.plusDays(1),midnight.plusSeconds(31*3600),midnight.plusSeconds(39*3600));
        submit(Portion.SECOND_HALF,date); submit(Portion.FIRST_HALF,date.plusDays(1));
        assertEquals(2,balance().reserved());
    }
    @Test void reasonRequiredForReturnAndRejection() {
        RequestView request=submit(Portion.FULL,date); authenticate(manager);
        assertThrows(IllegalArgumentException.class,()->commands.act(manager,key(),request.publicId(),"RETURN",action(request.version()," ")));
        assertThrows(IllegalArgumentException.class,()->commands.act(manager,key(),request.publicId(),"REJECT",action(request.version(),null)));
        assertEquals("PENDING",workflow.get(request.publicId(),owner).status());
    }
    @Test void draftEditingAndDiscardPreserveAuditWithoutPinning() {
        authenticate(owner); RequestView request=commands.create(owner,key(),draft(Portion.FULL,date));
        assertEquals(0,jdbc.queryForObject("SELECT count(id) FROM attendance_occurrence WHERE employee_id=?",Integer.class,employees.requireInternalByPublicId(employee).getId()));
        request=commands.edit(owner,key(),request.publicId(),draft(Portion.SECOND_HALF,date.plusDays(1)),request.version());
        assertEquals(Portion.SECOND_HALF,request.portion());
        request=commands.act(owner,key(),request.publicId(),"DISCARD",action(request.version(),null));
        assertEquals("DISCARDED",request.status());
        RequestView discarded=request;
        assertThrows(LeaveConflictException.class,()->commands.act(owner,key(),discarded.publicId(),"SUBMIT",action(discarded.version(),null)));
        assertEquals(0,balance().reserved());
    }
    @Test void rejectionReleasesReservationAndKeepsHistoricalPortions() {
        RequestView request=submit(Portion.FIRST_HALF,date); authenticate(manager);
        request=commands.act(manager,key(),request.publicId(),"REJECT",action(request.version(),"Rejected"));
        assertEquals("REJECTED",request.status()); assertEquals(0,balance().reserved());
        assertEquals(1,workflow.portions(request.publicId(),owner).size()); assertEquals("REJECT",workflow.history(request.publicId(),owner).getFirst().outcome());
    }
    @Test void scopedHrCanExplicitlyDesignateDistinctFallbackAndOldManagerLosesAuthority() {
        RequestView request=submit(Portion.FULL,date);
        Instant now=Instant.now().minusSeconds(100);
        UserAccount fallback=account(HrisRole.HR_ADMIN,now);
        UUID fallbackEmployee=employees.create(UUID.randomUUID(),"FALLBACK-"+key(),hr).employeeId();
        bind(fallback,fallbackEmployee,now); employees.addAssignment(fallbackEmployee,UUID.randomUUID(),"ENG",null,"STAFF",now,null,hr);
        authenticate(hr);
        request=commands.act(hr,key(),request.publicId(),"REASSIGN",new Action(employee,request.version(),"Explicit fallback designation",fallback.getPublicId(),1,true));
        authenticate(manager); RequestView reassigned=request;
        assertThrows(id.mydev.peoplecore.common.api.ResourceNotFoundException.class,()->commands.act(manager,key(),reassigned.publicId(),"APPROVE",action(reassigned.version(),null)));
        Authentication alternate=token(fallback); authenticate(alternate);
        request=commands.act(alternate,key(),request.publicId(),"APPROVE",action(request.version(),null));
        authenticate(hr); request=commands.act(hr,key(),request.publicId(),"APPROVE",action(request.version(),null));
        assertEquals("APPROVED",request.status()); assertEquals(2,balance().consumed());
    }
    @Test void replayCannotChangePayloadAndCutoffCannotReplayPrivateResults() {
        authenticate(owner); String requestKey=key(); Draft payload=draft(Portion.FULL,date);
        RequestView request=commands.create(owner,requestKey,payload);
        assertEquals(request,commands.create(owner,requestKey,payload));
        assertThrows(id.mydev.peoplecore.common.command.CommandConflictException.class,()->commands.create(owner,requestKey,draft(Portion.FIRST_HALF,date)));
        UserAccount member=accounts.findByOidcIssuerAndOidcSubject(ISSUER,owner.getName()).orElseThrow();
        member.applyCutoff(Instant.now().minusSeconds(1),LocalTime.of(17,0),"Asia/Jakarta",Instant.now()); accounts.saveAndFlush(member);
        assertThrows(org.springframework.security.access.AccessDeniedException.class,()->commands.create(owner,requestKey,payload));
    }
    @Test void crossYearAndMultiDatePartialDraftsAreRejected() {
        authenticate(owner);
        assertThrows(IllegalArgumentException.class,()->commands.create(owner,key(),new Draft(employee,LocalDate.of(date.getYear(),12,31),LocalDate.of(date.getYear()+1,1,1),Portion.FULL,"Annual")));
        assertThrows(IllegalArgumentException.class,()->commands.create(owner,key(),new Draft(employee,date,date.plusDays(1),Portion.FIRST_HALF,"Partial")));
        assertThrows(IllegalArgumentException.class,()->commands.create(owner,key(),new Draft(employee,date.plusDays(1),date,Portion.FULL,"Reversed")));
        assertThrows(IllegalArgumentException.class,()->commands.create(owner,key(),new Draft(employee,date,date,null,"Missing portion")));
        assertThrows(IllegalArgumentException.class,()->commands.create(owner,key(),new Draft(employee,date,date,Portion.FULL," ")));
        assertThrows(IllegalArgumentException.class,()->commands.create(owner,key(),new Draft(employee,null,date,Portion.FULL,"Missing start")));
        assertThrows(IllegalArgumentException.class,()->commands.create(owner,key(),new Draft(employee,date,null,Portion.FULL,"Missing end")));
    }

    @Test void boundedLeaveReadApisRejectInvalidLimitsRangesAndCommandIdentities() throws Exception {
        assertThrows(IllegalArgumentException.class,()->workflow.listPage(employee,null,0,owner));
        assertThrows(IllegalArgumentException.class,()->workflow.timeline(UUID.randomUUID(),null,null,0,owner));
        assertThrows(IllegalArgumentException.class,()->workflow.timeline(UUID.randomUUID(),null,null,101,owner));
        assertThrows(IllegalArgumentException.class,()->workflow.summary(employee,date,date,null,10,owner));
        assertThrows(IllegalArgumentException.class,()->workflow.summary(employee,date,date.plusDays(367),null,10,owner));
        assertThrows(IllegalArgumentException.class,()->workflow.verifyCommand("LEAVE_REQUEST_CREATE",mapper.readTree("{}"),owner));
        assertThrows(IllegalArgumentException.class,()->workflow.verifyCommand("LEAVE_REQUEST_CREATE",
            mapper.readTree("{\"employeePublicId\":7}"),owner));
    }

    @Test void leaveSubmissionMustFallWithinEmploymentAndUnknownActionsAreRejected() {
        authenticate(owner);
        RequestView request=commands.create(owner,key(),draft(Portion.FULL,date));
        authenticate(hr);
        employees.updateEmploymentDates(employee,date.plusDays(1),null,hr);
        authenticate(owner);
        assertThrows(IllegalArgumentException.class,()->commands.act(owner,key(),request.publicId(),"SUBMIT",action(request.version(),null)));

        authenticate(hr);
        employees.updateEmploymentDates(employee,date.minusDays(30),date.minusDays(1),hr);
        authenticate(owner);
        assertThrows(IllegalArgumentException.class,()->commands.act(owner,key(),request.publicId(),"SUBMIT",action(request.version(),null)));

        assertThrows(IllegalArgumentException.class,()->new TransactionTemplate(transactions).execute(status->
            workflow.act(request.publicId(),"UNKNOWN",action(request.version(),null),owner)));
    }
    @Test void sameReviewerCannotFillBothStepsOrBeRequester() {
        authenticate(hr);
        RequestView request=submit(Portion.FULL,date); authenticate(hr);
        assertThrows(IllegalArgumentException.class,()->commands.act(hr,key(),request.publicId(),"REASSIGN",new Action(employee,request.version(),"Invalid same reviewer",hrId,1,true)));
        assertEquals(2,balance().reserved());
    }
    @Test void cancellationReassignmentRecordsFreshDesignationAndHistory() {
        RequestView request=approve(submit(Portion.FULL,date)); authenticate(owner);
        var cancel=commands.createCancellation(owner,key(),request.publicId(),action(request.version(),"Cancel"));
        authenticate(hr);
        cancel=commands.actCancellation(hr,key(),request.publicId(),cancel.publicId(),"REASSIGN",new Action(employee,cancel.version(),"Confirm designated reviewer",hrId,null,false));
        assertEquals(1,cancel.version());
        cancel=commands.actCancellation(hr,key(),request.publicId(),cancel.publicId(),"APPROVE",action(cancel.version(),null));
        assertEquals(1,workflow.cancellationHistory(request.publicId(),cancel.publicId(),owner).size());
        assertEquals(0,balance().consumed());
    }
    @Test void requestPagesContinuePastOneHundredAndManagerSqlHidesDrafts() {
        authenticate(owner);
        RequestView last=null;
        for(int i=0;i<101;i++) last=commands.create(owner,key(),draft(Portion.FULL,date));
        var first=workflow.listPage(employee,null,100,owner);
        assertEquals(100,first.items().size()); assertNotNull(first.nextCursor());
        var second=workflow.listPage(employee,first.nextCursor(),100,owner);
        assertEquals(1,second.items().size()); assertEquals(last.publicId(),second.items().getFirst().publicId());
        assertEquals(null,second.nextCursor());
        var hidden=workflow.listPage(employee,null,100,manager);
        assertEquals(0,hidden.items().size()); assertEquals(null,hidden.nextCursor());
        RequestView submitted=commands.act(owner,key(),last.publicId(),"SUBMIT",action(last.version(),null));
        var managerPage=workflow.listPage(employee,null,100,manager);
        assertEquals(1,managerPage.items().size()); assertEquals(submitted.publicId(),managerPage.items().getFirst().publicId());
        assertEquals(0,workflow.history(submitted.publicId(),manager).size());
        assertEquals(submitted.publicId(),workflow.get(submitted.publicId(),manager).publicId());
    }
    @Test void completeTimelineContinuesPastOneHundredAndIncludesImmutableRouteSnapshot() {
        authenticate(owner); RequestView request=commands.create(owner,key(),draft(Portion.FULL,date));
        for(int i=0;i<100;i++) request=commands.edit(owner,key(),request.publicId(),draft(Portion.FULL,date),request.version());
        request=commands.act(owner,key(),request.publicId(),"SUBMIT",action(request.version(),null));
        var first=workflow.timeline(request.publicId(),null,null,100,owner);
        assertEquals(100,first.items().size()); assertNotNull(first.nextCursor());
        var second=workflow.timeline(request.publicId(),null,first.nextCursor(),100,owner);
        assertEquals(2,second.items().size()); assertEquals(null,second.nextCursor());
        assertEquals("LEAVE_REQUEST_SUBMIT",second.items().getLast().action());
        assertEquals(2,second.items().getLast().payload().get("route").size());
        assertEquals(2,second.items().getLast().payload().get("portions").size());
        assertEquals(102,jdbc.queryForObject("SELECT count(h.id) FROM leave_history h JOIN leave_request r ON r.id=h.request_id WHERE r.public_id=?",Integer.class,request.publicId()));
    }
    @Test void summaryContainsOnlyFinalEffectsAndReflectsCancellationWithoutPrivateReasons() {
        RequestView request=submit(Portion.FULL,date);
        assertEquals(0,workflow.summary(employee,date,date.plusDays(1),null,100,manager).items().size());
        request=approve(request);
        var approved=workflow.summary(employee,date,date.plusDays(1),null,1,manager);
        assertEquals(1,approved.items().size()); assertNotNull(approved.nextCursor());
        assertEquals("APPROVED",approved.items().getFirst().status());
        assertEquals(1,workflow.summary(employee,date,date.plusDays(1),approved.nextCursor(),1,manager).items().size());
        authenticate(owner); var cancel=commands.createCancellation(owner,key(),request.publicId(),action(request.version(),"Private employee reason"));
        assertEquals("APPROVED",workflow.summary(employee,date,date.plusDays(1),null,100,manager).items().getFirst().status());
        authenticate(hr); commands.actCancellation(hr,key(),request.publicId(),cancel.publicId(),"APPROVE",action(cancel.version(),null));
        var cancelled=workflow.summary(employee,date,date.plusDays(1),null,100,manager);
        assertEquals(2,cancelled.items().size()); assertEquals("CANCELLED",cancelled.items().getFirst().status());
        assertTrue(!cancelled.toString().contains("Private employee reason"));
        assertEquals(0,jdbc.queryForObject("SELECT count(o.id) FROM leave_occupancy o JOIN leave_request r ON r.id=o.request_id WHERE r.public_id=? AND o.active=TRUE",Integer.class,request.publicId()));
    }
    @Test void runtimeCannotRewriteAggregateAndOperationalProvenanceWhileLifecycleStillWorks() {
        RequestView request=submit(Portion.FULL,date); UUID id=request.publicId();
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE leave_request SET employee_id=?,version=version+1 WHERE public_id=?",employees.requireInternalByPublicId(managerEmployee).getId(),id));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE leave_request SET reason='Rewritten',version=version+1 WHERE public_id=?",id));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE leave_review_assignment SET reviewer_account_public_id=? WHERE request_id=(SELECT id FROM leave_request WHERE public_id=?) AND step=1",hrId,id));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE leave_occupancy SET start_at=start_at-INTERVAL '1 minute' WHERE request_id=(SELECT id FROM leave_request WHERE public_id=?)",id));
        request=approve(request); authenticate(owner);
        var cancellation=commands.createCancellation(owner,key(),id,action(request.version(),"Cancel"));
        UUID cancellationId=cancellation.publicId();
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE leave_cancellation SET reason='Rewritten',version=version+1 WHERE public_id=?",cancellationId));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE leave_cancellation_assignment SET reviewer_account_public_id=? WHERE cancellation_id=(SELECT id FROM leave_cancellation WHERE public_id=?)",UUID.randomUUID(),cancellationId));
        authenticate(hr); commands.actCancellation(hr,key(),id,cancellationId,"APPROVE",action(cancellation.version(),null));
        assertEquals("CANCELLED",workflow.get(id,owner).status()); assertEquals(0,balance().consumed());
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE leave_occupancy SET active=TRUE WHERE request_id=(SELECT id FROM leave_request WHERE public_id=?)",id));
    }
    private void pin(LocalDate day,Instant start,Instant end) {
        jdbc.update("INSERT INTO attendance_occurrence(public_id,employee_id,work_date,start_at,end_at,timezone,schedule_revision_id,calendar_revision_id,schedule_assignment_id,calendar_assignment_id,created_at) SELECT ?,s.employee_id,?,?,?,'Asia/Jakarta',s.revision_id,c.revision_id,s.id,c.id,? FROM employee_schedule_assignment s JOIN employee_calendar_assignment c ON c.employee_id=s.employee_id WHERE s.employee_id=? AND NOT s.superseded AND NOT c.superseded",
            UUID.randomUUID(),day,java.sql.Timestamp.from(start),java.sql.Timestamp.from(end),java.sql.Timestamp.from(Instant.now()),employees.requireInternalByPublicId(employee).getId());
    }
    private RequestView submit(Portion portion,LocalDate day) {
        authenticate(owner); RequestView draft=commands.create(owner,key(),draft(portion,day));
        return commands.act(owner,key(),draft.publicId(),"SUBMIT",action(draft.version(),null));
    }
    private RequestView approve(RequestView request) {
        authenticate(manager); request=commands.act(manager,key(),request.publicId(),"APPROVE",action(request.version(),null));
        authenticate(hr); return commands.act(hr,key(),request.publicId(),"APPROVE",action(request.version(),null));
    }
    private Draft draft(Portion portion,LocalDate day) { return new Draft(employee,day,day,portion,"Annual leave"); }
    private Action action(long version,String reason) { return new Action(employee,version,reason,null,null,false); }
    private LeaveEntitlementService.Balance balance() { return entitlement.query(employee,date.getYear(),hr); }
    private UserAccount account(String role,Instant start) {
        UserAccount account=accounts.save(new UserAccount(UUID.randomUUID(),ISSUER,"subject-"+UUID.randomUUID(),start));
        roles.save(new RoleAssignment(account.getId(),role,null,start,start)); return account;
    }
    private void bind(UserAccount account,UUID employee,Instant now) {
        bindings.save(new AccountBinding(employees.requireInternalByPublicId(employee).getId(),employee,account.getId(),ISSUER,account.getOidcSubject(),now));
    }
    private Authentication token(UserAccount account) { return JwtAuthFixture.token(ISSUER,account.getOidcSubject()); }
    private void authenticate(Authentication caller) { SecurityContextHolder.getContext().setAuthentication(caller); }
    private String key() { return UUID.randomUUID().toString(); }
}
