package id.mydev.peoplecore.notification.infrastructure.persistence;

import id.mydev.peoplecore.attendance.infrastructure.persistence.AttendanceNotificationResolver;
import id.mydev.peoplecore.leave.infrastructure.persistence.CancellationNotificationResolver;
import id.mydev.peoplecore.leave.infrastructure.persistence.LeaveNotificationResolver;
import id.mydev.peoplecore.notification.domain.NotificationModels.Intent;
import id.mydev.peoplecore.notification.domain.NotificationModels.Recipient;
import id.mydev.peoplecore.notification.domain.NotificationResolver;
import id.mydev.peoplecore.identity.infrastructure.persistence.JdbcNotificationIdentityRepository;
import id.mydev.peoplecore.payslip.infrastructure.persistence.PayslipNotificationResolver;
import id.mydev.peoplecore.reimbursement.infrastructure.persistence.ReimbursementNotificationResolver;
import id.mydev.peoplecore.support.PostgresFixture;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("postgres")
class WorkflowNotificationPostgresTest {
    private static final Instant NOW=Instant.parse("2026-11-13T10:00:00Z");
    enum Flow { ATTENDANCE, LEAVE, CANCELLATION, REIMBURSEMENT }
    private record Person(long id,UUID employee,long accountInternal,UUID account) { }
    private record Scenario(Flow flow,long id,UUID aggregate,UUID revision,NotificationResolver resolver) { }
    private record Document(long id,long version) { }

    @ParameterizedTest @EnumSource(Flow.class)
    void everySupportedWorkflowActionResolvesPinsAndMaterializesOnceWithStaleReviewerSuppression(Flow flow) {
        try(var fixture=new PostgresFixture()) {
            fixture.flyway().migrate(); fixture.grantRuntime(); JdbcTemplate jdbc=fixture.runtime();
            var tx=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
            var notifications=new JdbcNotificationRepository(jdbc);
            var identities=new JdbcNotificationIdentityRepository(jdbc);
            List<String> actions=actions(flow);
            for(String action:actions) {
                Person owner=person(jdbc,"EMPLOYEE");
                Person reviewer=person(jdbc,flow==Flow.CANCELLATION?"HR_ADMIN":"MANAGER");
                assignment(jdbc,owner,reviewer,false);
                boolean hasReviewer=!action.equals("ROUTE_RETRY")&&!action.equals("RETRY_ROUTE");
                Scenario scenario=scenario(jdbc,flow,owner,reviewer,hasReviewer);
                applyNotificationOutcome(jdbc,scenario,action,reviewer);
                String eventType=event(flow,action);
                assertTrue(scenario.resolver().supports(eventType),eventType);
                List<Recipient> recipients=scenario.resolver().resolve(eventType,scenario.aggregate(),NOW);
                assertFalse(recipients.isEmpty(),eventType);
                UUID eventId=UUID.randomUUID();
                for(Recipient recipient:recipients) {
                    Intent intent=new Intent(eventId,eventType,scenario.aggregate(),NOW,recipient.employeeId(),recipient.accountId(),
                        recipient.template(),recipient.eligibilityType(),recipient.assignmentId());
                    assertTrue(scenario.resolver().eligible(intent,NOW),eventType+"/"+recipient.template());
                    if("REVIEW_REQUIRED".equals(recipient.template())||"CLOSED_REVIEW".equals(recipient.eligibilityType())) {
                        jdbc.update("UPDATE role_assignments SET effective_to=? WHERE account_id=(SELECT id FROM user_accounts WHERE public_id=?)",
                            time(NOW),recipient.accountId());
                        assertFalse(scenario.resolver().eligible(intent,NOW),eventType+" stale recipient");
                        jdbc.update("UPDATE role_assignments SET effective_to=NULL WHERE account_id=(SELECT id FROM user_accounts WHERE public_id=?)",
                            recipient.accountId());
                    }
                    var destination=identities.verifyDestination(recipient.employeeId(),
                        "workflow-"+eventId+"-"+recipient.employeeId()+"@example.test","matrix-"+eventId,"local-matrix",NOW);
                    tx.executeWithoutResult(status->notifications.storeIntent(intent,java.util.Optional.of(destination),NOW));
                }
                int expected=recipients.stream().map(Recipient::employeeId).collect(java.util.stream.Collectors.toSet()).size();
                assertEquals(expected,(int)tx.execute(status->notifications.materialize(eventId,NOW)),eventType);
                assertEquals(0,(int)tx.execute(status->notifications.materialize(eventId,NOW.plusSeconds(1))),eventType+" replay");
                assertEquals(expected,jdbc.queryForObject("SELECT count(public_id) FROM notification_delivery WHERE event_id=?",Integer.class,eventId),eventType);
            }
        }
    }

    @ParameterizedTest @EnumSource(Flow.class)
    void currentReviewerGetsPinnedNoticeButReassignmentAndRoleRemovalSuppressOldWork(Flow flow) {
        try(var fixture=new PostgresFixture()) {
            fixture.flyway().migrate(); fixture.grantRuntime(); JdbcTemplate jdbc=fixture.runtime();
            Person owner=person(jdbc,"EMPLOYEE"); Person reviewer=person(jdbc,flow==Flow.CANCELLATION?"HR_ADMIN":"MANAGER");
            Person replacement=person(jdbc,flow==Flow.REIMBURSEMENT?"MANAGER":"HR_ADMIN");
            assignment(jdbc,owner,reviewer,false);
            Scenario scenario=scenario(jdbc,flow,owner,reviewer,true);
            String submitted=event(flow,flow==Flow.CANCELLATION?"CREATE":"SUBMIT");
            assertTrue(scenario.resolver().supports(submitted)); assertFalse(scenario.resolver().supports("UNSUPPORTED"));
            assertTrue(scenario.resolver().resolve(submitted,UUID.randomUUID(),NOW).isEmpty());
            Recipient first=scenario.resolver().resolve(submitted,scenario.aggregate(),NOW).getFirst();
            assertEquals("REVIEW_REQUIRED",first.template()); assertEquals(reviewer.account(),first.accountId());
            Intent original=intent(scenario,submitted,first);
            assertTrue(scenario.resolver().eligible(original,NOW));
            deactivate(jdbc,scenario);
            assignment(jdbc,owner,replacement,true);
            review(jdbc,scenario,replacement);
            assertFalse(scenario.resolver().eligible(original,NOW));
            var reassigned=scenario.resolver().resolve(event(flow,"REASSIGN"),scenario.aggregate(),NOW);
            assertTrue(reassigned.stream().anyMatch(r->r.template().equals("REVIEW_REASSIGNED")&&r.employeeId().equals(owner.employee())));
            Recipient next=reassigned.stream().filter(r->r.template().equals("REVIEW_REQUIRED")).findFirst().orElseThrow();
            assertEquals(replacement.account(),next.accountId());
            Intent pinned=intent(scenario,event(flow,"REASSIGN"),next);
            assertTrue(scenario.resolver().eligible(pinned,NOW));
            Intent prematureClosure=new Intent(pinned.eventId(),event(flow,"WITHDRAW"),pinned.aggregateId(),NOW,pinned.employeeId(),pinned.accountId(),"REQUEST_CLOSED","CLOSED_REVIEW",pinned.assignmentId());
            assertFalse(scenario.resolver().eligible(prematureClosure,NOW));
            Intent wrongAssignment=new Intent(pinned.eventId(),pinned.eventType(),pinned.aggregateId(),NOW,pinned.employeeId(),pinned.accountId(),pinned.template(),pinned.eligibilityType(),UUID.randomUUID());
            assertFalse(scenario.resolver().eligible(wrongAssignment,NOW));
            Intent notBlocked=new Intent(pinned.eventId(),pinned.eventType(),pinned.aggregateId(),NOW,pinned.employeeId(),pinned.accountId(),"REVIEW_BLOCKED","HR_RECOVERY",owner.employee());
            assertFalse(scenario.resolver().eligible(notBlocked,NOW));
            jdbc.update("UPDATE role_assignments SET effective_to=? WHERE account_id=?",Timestamp.from(NOW),replacement.accountInternal());
            assertFalse(scenario.resolver().eligible(pinned,NOW));
            role(jdbc,replacement,flow==Flow.REIMBURSEMENT?"MANAGER":"HR_ADMIN",NOW);
            assertTrue(scenario.resolver().eligible(pinned,NOW));
            withdraw(jdbc,scenario);
            assertFalse(scenario.resolver().eligible(pinned,NOW));
            var closed=scenario.resolver().resolve(event(flow,"WITHDRAW"),scenario.aggregate(),NOW);
            assertTrue(closed.stream().anyMatch(r->r.template().equals("REQUEST_CLOSED")&&r.employeeId().equals(owner.employee())));
            assertTrue(closed.stream().anyMatch(r->r.template().equals("REQUEST_CLOSED")&&r.employeeId().equals(replacement.employee())));
            assertTrue(scenario.resolver().eligible(intent(scenario,event(flow,"WITHDRAW"),closed.getFirst()),NOW));
            Recipient former=closed.stream().filter(r->"CLOSED_REVIEW".equals(r.eligibilityType())).findFirst().orElseThrow();
            Intent formerIntent=intent(scenario,event(flow,"WITHDRAW"),former);
            assertTrue(scenario.resolver().eligible(formerIntent,NOW));
            assertFalse(scenario.resolver().eligible(new Intent(formerIntent.eventId(),formerIntent.eventType(),formerIntent.aggregateId(),NOW,formerIntent.employeeId(),formerIntent.accountId(),formerIntent.template(),formerIntent.eligibilityType(),UUID.randomUUID()),NOW));
            assertFalse(scenario.resolver().eligible(new Intent(formerIntent.eventId(),formerIntent.eventType(),UUID.randomUUID(),NOW,formerIntent.employeeId(),formerIntent.accountId(),formerIntent.template(),formerIntent.eligibilityType(),formerIntent.assignmentId()),NOW));
            jdbc.update("UPDATE account_bindings SET revoked_at=? WHERE employee_id=? AND revoked_at IS NULL",time(NOW),owner.id());
            assertTrue(scenario.resolver().resolve(event(flow,"WITHDRAW"),scenario.aggregate(),NOW).stream()
                .filter(r->owner.employee().equals(r.employeeId())).allMatch(r->r.accountId()==null));
        }
    }

    @ParameterizedTest @EnumSource(Flow.class)
    void missingReviewerProducesActionableBlockedOwnerAndExplicitRecoveryEligibility(Flow flow) {
        try(var fixture=new PostgresFixture()) {
            fixture.flyway().migrate(); fixture.grantRuntime(); JdbcTemplate jdbc=fixture.runtime();
            Person owner=person(jdbc,"EMPLOYEE"); Person manager=person(jdbc,"MANAGER"); assignment(jdbc,owner,manager,false);
            Scenario scenario=scenario(jdbc,flow,owner,manager,false);
            String type=event(flow,flow==Flow.ATTENDANCE?"ROUTE_RETRY":"RETRY_ROUTE");
            var notices=scenario.resolver().resolve(type,scenario.aggregate(),NOW);
            assertEquals(1,notices.size()); assertEquals("REVIEW_BLOCKED",notices.getFirst().template());
            assertEquals(owner.employee(),notices.getFirst().employeeId());
            Intent recovery=new Intent(UUID.randomUUID(),type,scenario.aggregate(),NOW,UUID.randomUUID(),UUID.randomUUID(),"REVIEW_BLOCKED","HR_RECOVERY",owner.employee());
            assertTrue(scenario.resolver().eligible(recovery,NOW));
            assertTrue(scenario.resolver().eligible(intent(scenario,type,notices.getFirst()),NOW));
        }
    }

    @ParameterizedTest @EnumSource(Flow.class)
    void terminalDecisionNotifiesApplicantWithCurrentStatusAndNoNewReviewTask(Flow flow) {
        try(var fixture=new PostgresFixture()) {
            fixture.flyway().migrate(); fixture.grantRuntime(); JdbcTemplate jdbc=fixture.runtime();
            Person owner=person(jdbc,"EMPLOYEE"); Person reviewer=person(jdbc,flow==Flow.CANCELLATION?"HR_ADMIN":"MANAGER");
            assignment(jdbc,owner,reviewer,false); Scenario scenario=scenario(jdbc,flow,owner,reviewer,true);
            decision(jdbc,scenario,reviewer,1,"REJECT");
            terminal(jdbc,scenario,"REJECTED"); deactivate(jdbc,scenario);
            var notices=scenario.resolver().resolve(event(flow,"REJECT"),scenario.aggregate(),NOW);
            assertEquals(1,notices.size()); assertEquals(owner.employee(),notices.getFirst().employeeId());
            assertEquals(flow==Flow.CANCELLATION?"REQUEST_CLOSED":"REQUEST_STATUS",notices.getFirst().template());
        }
    }

    @ParameterizedTest @EnumSource(value=Flow.class,names={"LEAVE","REIMBURSEMENT"})
    void intermediateApprovalNotifiesNextReviewerAndFinalApprovalNotifiesApplicant(Flow flow) {
        try(var fixture=new PostgresFixture()) {
            fixture.flyway().migrate(); fixture.grantRuntime(); JdbcTemplate jdbc=fixture.runtime();
            Person owner=person(jdbc,"EMPLOYEE"); Person manager=person(jdbc,"MANAGER");
            Person second=person(jdbc,flow==Flow.LEAVE?"HR_ADMIN":"FINANCE_PAYROLL"); assignment(jdbc,owner,manager,false);
            Scenario scenario=scenario(jdbc,flow,owner,manager,true);
            decision(jdbc,scenario,manager,1,"APPROVE"); deactivate(jdbc,scenario); reviewStep(jdbc,scenario,second,2);
            var next=scenario.resolver().resolve(event(flow,"APPROVE"),scenario.aggregate(),NOW);
            assertEquals(1,next.size()); assertEquals(second.account(),next.getFirst().accountId());
            assertEquals("REVIEW_REQUIRED",next.getFirst().template());
            assertTrue(scenario.resolver().eligible(intent(scenario,event(flow,"APPROVE"),next.getFirst()),NOW));
            decision(jdbc,scenario,second,2,"APPROVE"); terminal(jdbc,scenario,"APPROVED"); deactivate(jdbc,scenario);
            var finalNotice=scenario.resolver().resolve(event(flow,"APPROVE"),scenario.aggregate(),NOW);
            assertEquals(1,finalNotice.size()); assertEquals(owner.employee(),finalNotice.getFirst().employeeId());
            assertEquals("REQUEST_STATUS",finalNotice.getFirst().template());
            if(flow==Flow.REIMBURSEMENT) {
                long payment=jdbc.queryForObject("INSERT INTO reimbursement_payment(public_id,claim_id,revision,amount,paid_date,payment_reference,created_by,created_at) VALUES (gen_random_uuid(),?,1,50,'2026-11-13','payment-one',?,?) RETURNING id",Long.class,scenario.id(),second.account(),time(NOW));
                terminal(jdbc,scenario,"PAID");
                assertEquals("PAYMENT_STATUS",scenario.resolver().resolve("REIMBURSEMENT_RECORD_PAYMENT",scenario.aggregate(),NOW).getFirst().template());
                jdbc.update("INSERT INTO reimbursement_payment_correction(public_id,payment_id,prior_paid_date,prior_reference,corrected_paid_date,corrected_reference,reason,created_by,created_at) VALUES (gen_random_uuid(),?,'2026-11-13','payment-one','2026-11-14','payment-two','Correct reference',?,?)",payment,second.account(),time(NOW.plusSeconds(1)));
                assertEquals("PAYMENT_STATUS",scenario.resolver().resolve("REIMBURSEMENT_CORRECT_PAYMENT",scenario.aggregate(),NOW).getFirst().template());
            }
        }
    }

    @Test void publishedPayslipNoticeIsEmployeeOnlyAndAnOldPublicationCannotRemainEligibleAfterReplacement() {
        try(var fixture=new PostgresFixture()) {
            fixture.flyway().migrate(); fixture.grantRuntime(); JdbcTemplate jdbc=fixture.runtime();
            Person employee=person(jdbc,"EMPLOYEE"); Person finance=person(jdbc,"FINANCE_PAYROLL");
            var tx=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
            UUID first=tx.execute(status->publication(jdbc,employee,finance));
            var resolver=new PayslipNotificationResolver(jdbc);
            assertTrue(resolver.supports("PAYSLIP_PUBLISHED")); assertFalse(resolver.supports("PAYSLIP_BATCH_EDIT"));
            assertTrue(resolver.resolve("PAYSLIP_PUBLISHED",UUID.randomUUID(),NOW).isEmpty());
            var recipient=resolver.resolve("PAYSLIP_PUBLISHED",first,NOW).getFirst();
            assertEquals(employee.employee(),recipient.employeeId()); assertEquals(employee.account(),recipient.accountId());
            Intent intent=new Intent(UUID.randomUUID(),"PAYSLIP_PUBLISHED",first,NOW,recipient.employeeId(),recipient.accountId(),recipient.template(),recipient.eligibilityType(),null);
            assertTrue(resolver.eligible(intent,NOW));
            UUID replacement=tx.execute(status->publication(jdbc,employee,finance));
            assertFalse(resolver.eligible(intent,NOW));
            assertTrue(resolver.eligible(new Intent(UUID.randomUUID(),"PAYSLIP_PUBLISHED",replacement,NOW,recipient.employeeId(),recipient.accountId(),recipient.template(),recipient.eligibilityType(),null),NOW));
        }
    }

    private static Scenario scenario(JdbcTemplate jdbc,Flow flow,Person owner,Person reviewer,boolean assigned) {
        UUID aggregate=UUID.randomUUID(); Scenario result;
        if(flow==Flow.ATTENDANCE) {
            long schedule=jdbc.queryForObject("INSERT INTO work_schedule_revision(public_id,created_at) VALUES (gen_random_uuid(),?) RETURNING id",Long.class,time(NOW));
            jdbc.update("INSERT INTO work_schedule_day(revision_id,weekday,start_time,end_time) VALUES (?,5,'09:00','17:00')",schedule);
            jdbc.update("UPDATE work_schedule_revision SET status='PUBLISHED',published_at=? WHERE id=?",time(NOW),schedule);
            long scheduleAssignment=jdbc.queryForObject("INSERT INTO employee_schedule_assignment(public_id,employee_id,revision_id,from_date,created_at) VALUES (gen_random_uuid(),?,?,'2026-01-01',?) RETURNING id",Long.class,owner.id(),schedule,time(NOW));
            long occurrence=jdbc.queryForObject("INSERT INTO attendance_occurrence(public_id,employee_id,work_date,start_at,end_at,timezone,schedule_revision_id,schedule_assignment_id,created_at) VALUES (gen_random_uuid(),?,'2026-11-13',?,?,'Asia/Jakarta',?,?,?) RETURNING id",Long.class,owner.id(),time(NOW),time(NOW.plusSeconds(3600)),schedule,scheduleAssignment,time(NOW));
            long id=jdbc.queryForObject("INSERT INTO attendance_correction(public_id,employee_id,employee_public_id,work_date,status,proposed_in,reason,created_at) VALUES (?,?,?,'2026-11-13','DRAFT',?,'Correction',?) RETURNING id",Long.class,aggregate,owner.id(),owner.employee(),time(NOW),time(NOW));
            UUID revision=UUID.randomUUID();
            jdbc.update("INSERT INTO attendance_correction_revision(public_id,correction_id,occurrence_id,proposed_in,reason,org_unit,created_at) VALUES (?,?,?,?,'Correction','ENG',?)",revision,id,occurrence,time(NOW),time(NOW));
            jdbc.update("UPDATE attendance_correction SET active_revision_id=?,status='PENDING',version=version+1 WHERE id=?",revision,id);
            result=new Scenario(flow,id,aggregate,revision,new AttendanceNotificationResolver(jdbc));
        } else if(flow==Flow.LEAVE||flow==Flow.CANCELLATION) {
            long request=jdbc.queryForObject("INSERT INTO leave_request(public_id,employee_id,status,revision,from_date,to_date,portion,reason,created_at) VALUES (?,?,?,1,'2026-11-13','2026-11-13','FULL','Annual leave',?) RETURNING id",Long.class,aggregate,owner.id(),flow==Flow.CANCELLATION?"APPROVED":"PENDING",time(NOW));
            if(flow==Flow.LEAVE) result=new Scenario(flow,request,aggregate,null,new LeaveNotificationResolver(jdbc));
            else {
                aggregate=UUID.randomUUID();
                long cancellation=jdbc.queryForObject("INSERT INTO leave_cancellation(public_id,request_id,status,reason,org_unit,created_at) VALUES (?,?,'PENDING','Cancel leave','ENG',?) RETURNING id",Long.class,aggregate,request,time(NOW));
                result=new Scenario(flow,cancellation,aggregate,null,new CancellationNotificationResolver(jdbc));
            }
        } else {
            Document document=document(jdbc,owner,"CLAIM_EVIDENCE");
            long category=jdbc.queryForObject("INSERT INTO reimbursement_category_revision(public_id,category_code,effective_from,claim_limit,created_at) VALUES (gen_random_uuid(),?,'2026-01-01',100,?) RETURNING id",Long.class,"TRAVEL-"+aggregate,time(NOW));
            long id=jdbc.queryForObject("INSERT INTO reimbursement_claim(public_id,employee_id,status,category_code,transaction_date,amount,description,created_at) VALUES (?,?,'DRAFT',?,'2026-11-13',50,'Trip',?) RETURNING id",Long.class,aggregate,owner.id(),"TRAVEL-"+aggregate,time(NOW));
            jdbc.update("INSERT INTO reimbursement_revision(claim_id,revision,category_revision_id,transaction_date,amount,description,org_unit,submitted_at,sealed) VALUES (?,1,?,'2026-11-13',50,'Trip','ENG',?,false)",id,category,time(NOW));
            jdbc.update("INSERT INTO reimbursement_evidence(claim_id,revision,document_version_id) VALUES (?,1,?)",id,document.version());
            jdbc.update("UPDATE reimbursement_revision SET sealed=true WHERE claim_id=?",id);
            jdbc.update("UPDATE reimbursement_claim SET status='PENDING',revision=1,version=1 WHERE id=?",id);
            result=new Scenario(flow,id,aggregate,null,new ReimbursementNotificationResolver(jdbc));
        }
        if(assigned) review(jdbc,result,reviewer);
        return result;
    }

    private static void review(JdbcTemplate jdbc,Scenario s,Person reviewer) {
        switch(s.flow()) {
            case ATTENDANCE -> jdbc.update("INSERT INTO attendance_correction_assignment(public_id,revision_id,reviewer_id,reason,created_at) VALUES (gen_random_uuid(),?,?,'Assigned',?)",s.revision(),reviewer.accountInternal(),time(NOW));
            case LEAVE -> jdbc.update("INSERT INTO leave_review_assignment(request_id,revision,step,reviewer_account_public_id,reviewer_employee_public_id,created_at) VALUES (?,1,1,?,?,?)",s.id(),reviewer.account(),reviewer.employee(),time(NOW));
            case CANCELLATION -> jdbc.update("INSERT INTO leave_cancellation_assignment(cancellation_id,reviewer_account_public_id,reviewer_employee_public_id,created_at) VALUES (?,?,?,?)",s.id(),reviewer.account(),reviewer.employee(),time(NOW));
            case REIMBURSEMENT -> jdbc.update("INSERT INTO reimbursement_review_assignment(claim_id,revision,step,reviewer_account_public_id,reviewer_employee_public_id,created_at) VALUES (?,1,1,?,?,?)",s.id(),reviewer.account(),reviewer.employee(),time(NOW));
        }
    }
    private static void reviewStep(JdbcTemplate jdbc,Scenario s,Person reviewer,int step) {
        String table=s.flow()==Flow.LEAVE?"leave_review_assignment":"reimbursement_review_assignment";
        String parent=s.flow()==Flow.LEAVE?"request_id":"claim_id";
        jdbc.update("INSERT INTO "+table+"("+parent+",revision,step,reviewer_account_public_id,reviewer_employee_public_id,created_at) VALUES (?,1,?,?,?,?)",s.id(),step,reviewer.account(),reviewer.employee(),time(NOW));
    }
    private static void decision(JdbcTemplate jdbc,Scenario s,Person reviewer,int step,String outcome) {
        switch(s.flow()) {
            case ATTENDANCE -> jdbc.update("INSERT INTO attendance_correction_decision(public_id,revision_id,assignment_id,reviewer_id,decision,reason,created_at) SELECT gen_random_uuid(),?,public_id,?,?,'Reviewed',? FROM attendance_correction_assignment WHERE revision_id=? ORDER BY id DESC LIMIT 1",s.revision(),reviewer.accountInternal(),switch(outcome) { case "APPROVE"->"APPROVED"; case "RETURN"->"RETURNED"; default->"REJECTED"; },time(NOW),s.revision());
            case LEAVE -> jdbc.update("INSERT INTO leave_decision(request_id,revision,step,reviewer_account_public_id,reviewer_employee_public_id,outcome,reason,created_at) VALUES (?,1,?,?,?,?,'Reviewed',?)",s.id(),step,reviewer.account(),reviewer.employee(),outcome,time(NOW));
            case CANCELLATION -> jdbc.update("INSERT INTO leave_cancellation_decision(cancellation_id,reviewer_account_public_id,outcome,reason,created_at) VALUES (?,?,?,'Reviewed',?)",s.id(),reviewer.account(),outcome,time(NOW));
            case REIMBURSEMENT -> jdbc.update("INSERT INTO reimbursement_decision(claim_id,revision,step,reviewer_account_public_id,reviewer_employee_public_id,outcome,reason,created_at) VALUES (?,1,?,?,?,?,'Reviewed',?)",s.id(),step,reviewer.account(),reviewer.employee(),outcome,time(NOW));
        }
    }
    private static void terminal(JdbcTemplate jdbc,Scenario s,String state) {
        String table=switch(s.flow()) { case ATTENDANCE->"attendance_correction"; case LEAVE->"leave_request"; case CANCELLATION->"leave_cancellation"; case REIMBURSEMENT->"reimbursement_claim"; };
        jdbc.update("UPDATE "+table+" SET status=?,version=version+1 WHERE id=?",state,s.id());
    }
    private static void deactivate(JdbcTemplate jdbc,Scenario s) {
        switch(s.flow()) {
            case ATTENDANCE -> { }
            case LEAVE -> jdbc.update("UPDATE leave_review_assignment SET active=false WHERE request_id=? AND active",s.id());
            case CANCELLATION -> jdbc.update("UPDATE leave_cancellation_assignment SET active=false WHERE cancellation_id=? AND active",s.id());
            case REIMBURSEMENT -> jdbc.update("UPDATE reimbursement_review_assignment SET active=false WHERE claim_id=? AND active",s.id());
        }
    }
    private static void withdraw(JdbcTemplate jdbc,Scenario s) {
        String table=switch(s.flow()) { case ATTENDANCE->"attendance_correction"; case LEAVE->"leave_request"; case CANCELLATION->"leave_cancellation"; case REIMBURSEMENT->"reimbursement_claim"; };
        jdbc.update("UPDATE "+table+" SET status='WITHDRAWN',version=version+1 WHERE id=?",s.id());
        deactivate(jdbc,s);
    }
    private static void applyNotificationOutcome(JdbcTemplate jdbc,Scenario scenario,String action,Person reviewer) {
        if("WITHDRAW".equals(action)) {
            withdraw(jdbc,scenario);
        } else if("REJECT".equals(action)) {
            decision(jdbc,scenario,reviewer,1,"REJECT");
            terminal(jdbc,scenario,"REJECTED"); deactivate(jdbc,scenario);
        } else if("RETURN".equals(action)) {
            decision(jdbc,scenario,reviewer,1,"RETURN");
            terminal(jdbc,scenario,"RETURNED"); deactivate(jdbc,scenario);
        } else if(scenario.flow()==Flow.CANCELLATION&&"APPROVE".equals(action)) {
            decision(jdbc,scenario,reviewer,1,"APPROVE");
            terminal(jdbc,scenario,"APPROVED"); deactivate(jdbc,scenario);
        } else if("RECORD_PAYMENT".equals(action)||"CORRECT_PAYMENT".equals(action)) {
            Person finance=person(jdbc,"FINANCE_PAYROLL");
            reviewStep(jdbc,scenario,finance,2);
            decision(jdbc,scenario,reviewer,1,"APPROVE");
            decision(jdbc,scenario,finance,2,"APPROVE");
            terminal(jdbc,scenario,"APPROVED"); deactivate(jdbc,scenario);
            long payment=jdbc.queryForObject("INSERT INTO reimbursement_payment(public_id,claim_id,revision,amount,paid_date,payment_reference,created_by,created_at) VALUES (gen_random_uuid(),?,1,50,'2026-11-13','matrix-payment',?,?) RETURNING id",Long.class,scenario.id(),reviewer.account(),time(NOW));
            terminal(jdbc,scenario,"PAID");
            if("CORRECT_PAYMENT".equals(action)) jdbc.update("INSERT INTO reimbursement_payment_correction(public_id,payment_id,prior_paid_date,prior_reference,corrected_paid_date,corrected_reference,reason,created_by,created_at) VALUES (gen_random_uuid(),?,'2026-11-13','matrix-payment','2026-11-14','matrix-payment-corrected','Correct reference',?,?)",payment,reviewer.account(),time(NOW.plusSeconds(1)));
        }
    }
    private static List<String> actions(Flow flow) {
        return switch(flow) {
            case ATTENDANCE -> List.of("SUBMIT","RESUBMIT","WITHDRAW","APPROVE","REJECT","RETURN","REASSIGN","ROUTE_RETRY");
            case LEAVE -> List.of("SUBMIT","RESUBMIT","WITHDRAW","APPROVE","REJECT","RETURN","REASSIGN","RETRY_ROUTE");
            case CANCELLATION -> List.of("CREATE","WITHDRAW","APPROVE","REJECT","REASSIGN","RETRY_ROUTE");
            case REIMBURSEMENT -> List.of("SUBMIT","RESUBMIT","WITHDRAW","APPROVE","REJECT","RETURN","REASSIGN","RETRY_ROUTE","RECORD_PAYMENT","CORRECT_PAYMENT");
        };
    }
    private static String event(Flow flow,String action) {
        return switch(flow) { case ATTENDANCE->"ATTENDANCE_CORRECTION_"; case LEAVE->"LEAVE_REQUEST_"; case CANCELLATION->"LEAVE_CANCELLATION_"; case REIMBURSEMENT->"REIMBURSEMENT_"; }+action;
    }
    private static Intent intent(Scenario s,String type,Recipient recipient) {
        return new Intent(UUID.randomUUID(),type,s.aggregate(),NOW,recipient.employeeId(),recipient.accountId(),recipient.template(),recipient.eligibilityType(),recipient.assignmentId());
    }
    private static Person person(JdbcTemplate jdbc,String role) {
        UUID employee=UUID.randomUUID(); UUID account=UUID.randomUUID();
        long accountId=jdbc.queryForObject("INSERT INTO user_accounts(public_id,oidc_issuer,oidc_subject,created_at) VALUES (?,'https://resolver.test',?,?) RETURNING id",Long.class,account,account.toString(),time(NOW));
        long employeeId=jdbc.queryForObject("INSERT INTO employees(public_id,employee_number,created_at) VALUES (?,?,?) RETURNING id",Long.class,employee,"WORKFLOW-"+employee,time(NOW));
        jdbc.update("INSERT INTO account_bindings(employee_id,employee_public_id,account_id,oidc_issuer,oidc_subject,created_at) VALUES (?,?,?,'https://resolver.test',?,?)",employeeId,employee,accountId,account.toString(),time(NOW));
        var person=new Person(employeeId,employee,accountId,account); role(jdbc,person,role,NOW.minusSeconds(60)); return person;
    }
    private static void role(JdbcTemplate jdbc,Person person,String role,Instant from) {
        jdbc.update("INSERT INTO role_assignments(account_id,role,scope,effective_from,created_at) VALUES (?,?,?, ?,?)",person.accountInternal(),role,"EMPLOYEE".equals(role)?null:"ENG",time(from),time(NOW));
    }
    private static void assignment(JdbcTemplate jdbc,Person owner,Person manager,boolean supersede) {
        if(supersede) jdbc.update("UPDATE employee_assignments SET superseded_at=? WHERE employee_id=? AND superseded_at IS NULL",time(NOW),owner.id());
        jdbc.update("INSERT INTO employee_assignments(public_id,employee_id,org_unit,manager_employee_id,job_level,valid_from,created_at) VALUES (gen_random_uuid(),?,'ENG',?,'L3',?,?)",owner.id(),manager.id(),time(supersede?NOW:NOW.minusSeconds(60)),time(NOW));
    }
    private static Document document(JdbcTemplate jdbc,Person owner,String purpose) {
        long document=jdbc.queryForObject("INSERT INTO private_document(public_id,employee_id,purpose,status,file_name,declared_media_type,declared_size,current_generation,created_at,updated_at) VALUES (gen_random_uuid(),?,?,'VERIFYING','file.pdf','application/pdf',1,1,?,?) RETURNING id",Long.class,owner.id(),purpose,time(NOW),time(NOW));
        UUID uploadId=UUID.randomUUID();
        long upload=jdbc.queryForObject("INSERT INTO document_upload_attempt(public_id,document_id,generation,staging_key,byte_size,status,created_at) VALUES (?,?,1,?,1,'RESERVED',?) RETURNING id",Long.class,uploadId,document,"staging/"+uploadId,time(NOW));
        jdbc.update("UPDATE document_upload_attempt SET status='COMPLETED',object_etag='etag',completed_at=? WHERE id=?",time(NOW),upload);
        jdbc.update("UPDATE private_document SET current_upload_id=?,current_upload_generation=1 WHERE id=?",upload,document);
        UUID verification=UUID.randomUUID();
        long attempt=jdbc.queryForObject("INSERT INTO document_verification_attempt(public_id,document_id,upload_attempt_id,generation,candidate_key,status,created_at) VALUES (?,?,?,1,?,'QUEUED',?) RETURNING id",Long.class,verification,document,upload,"candidates/"+verification,time(NOW));
        UUID token=UUID.randomUUID();
        jdbc.update("UPDATE document_verification_attempt SET status='CLAIMED',lease_token=?,lease_expires_at=clock_timestamp()+interval '5 minutes' WHERE id=?",token,attempt);
        jdbc.update("UPDATE document_verification_attempt SET copy_started_at=clock_timestamp() WHERE id=?",attempt);
        jdbc.update("UPDATE document_verification_attempt SET copy_completed_at=clock_timestamp() WHERE id=?",attempt);
        jdbc.update("UPDATE document_verification_attempt SET status='CLEAN',completed_at=clock_timestamp() WHERE id=?",attempt);
        long version=jdbc.queryForObject("INSERT INTO document_version(public_id,document_id,attempt_id,lease_token,media_type,byte_size,sha256,scanner_version,verified_at) VALUES (gen_random_uuid(),?,?,?,'application/pdf',1,repeat('a',64),'test',clock_timestamp()) RETURNING id",Long.class,document,attempt,token);
        jdbc.update("UPDATE private_document SET status='VERIFIED',current_version_id=? WHERE id=?",version,document);
        return new Document(document,version);
    }
    private static UUID publication(JdbcTemplate jdbc,Person owner,Person finance) {
        Document document=document(jdbc,owner,"PAYSLIP");
        long batch=jdbc.queryForObject("INSERT INTO payslip_batch(public_id,created_by,created_at,updated_at) VALUES (gen_random_uuid(),?,?,?) RETURNING id",Long.class,finance.accountInternal(),time(NOW),time(NOW));
        long revision=jdbc.queryForObject("INSERT INTO payslip_batch_revision(public_id,batch_id,revision_number,created_at) VALUES (gen_random_uuid(),?,1,?) RETURNING id",Long.class,batch,time(NOW));
        long item=jdbc.queryForObject("INSERT INTO payslip_batch_item(public_id,revision_id,employee_id,period_start,document_id,document_version_id) VALUES (gen_random_uuid(),?,?,'2026-11-01',?,?) RETURNING id",Long.class,revision,owner.id(),document.id(),document.version());
        jdbc.update("UPDATE payslip_batch SET current_revision_id=? WHERE id=?",revision,batch);
        jdbc.update("UPDATE payslip_batch_revision SET status='REVIEWED',reviewed_by=?,reviewed_at=? WHERE id=?",finance.accountInternal(),time(NOW),revision);
        jdbc.update("UPDATE payslip_batch SET status='REVIEWED' WHERE id=?",batch);
        UUID publication=UUID.randomUUID();
        long id=jdbc.queryForObject("INSERT INTO payslip_publication(public_id,revision_id,item_id,employee_id,period_start,published_by,published_at) VALUES (?,?,?,?,'2026-11-01',?,?) RETURNING id",Long.class,publication,revision,item,owner.id(),finance.accountInternal(),time(NOW));
        jdbc.update("INSERT INTO payslip_current(employee_id,period_start,publication_id) VALUES (?,'2026-11-01',?) ON CONFLICT(employee_id,period_start) DO UPDATE SET publication_id=excluded.publication_id",owner.id(),id);
        jdbc.update("UPDATE payslip_batch SET status='PUBLISHED' WHERE id=?",batch);
        return publication;
    }
    private static Timestamp time(Instant at) { return Timestamp.from(at); }
}
