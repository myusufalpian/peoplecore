package id.mydev.peoplecore.notification.infrastructure.persistence;

import id.mydev.peoplecore.notification.domain.NotificationModels.Intent;
import id.mydev.peoplecore.notification.domain.NotificationModels.Recipient;
import id.mydev.peoplecore.organization.infrastructure.persistence.ProfileNotificationResolver;
import id.mydev.peoplecore.support.PostgresFixture;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("postgres")
class ProfileNotificationScenariosPostgresTest {
    private static final Instant NOW=Instant.parse("2026-11-13T10:00:00Z");
    private record Person(long id,UUID employee,long accountInternal,UUID account) { }
    private record Proposal(long id,UUID publicId,long revision,Person owner) { }

    @Test void reassignmentAndWithdrawalUseFrozenReviewerAndNotifyOwnerAndClosedTaskHolder() {
        try(var fixture=new PostgresFixture()) {
            fixture.flyway().migrate(); fixture.grantRuntime(); JdbcTemplate jdbc=fixture.runtime();
            Person owner=person(jdbc,"EMPLOYEE"); Person first=person(jdbc,"HR_ADMIN"); Person replacement=person(jdbc,"HR_ADMIN");
            Proposal proposal=proposal(jdbc,owner); UUID assignment=assign(jdbc,proposal,first);
            var resolver=new ProfileNotificationResolver(jdbc);
            assertTrue(resolver.supports("PROFILE_SUBMIT")); assertFalse(resolver.supports("PROFILE_RECORD_PAYMENT"));
            assertFalse(resolver.supports("OTHER_SUBMIT"));
            assertTrue(resolver.resolve("PROFILE_SUBMIT",UUID.randomUUID(),NOW).isEmpty());
            Recipient recipient=resolver.resolve("PROFILE_SUBMIT",proposal.publicId(),NOW).getFirst();
            Intent original=intent(proposal,"PROFILE_SUBMIT",recipient);
            assertTrue(resolver.eligible(original,NOW)); assertEquals(assignment,recipient.assignmentId());
            close(jdbc,assignment,"SUPERSEDED"); UUID nextAssignment=assign(jdbc,proposal,replacement);
            assertFalse(resolver.eligible(original,NOW));
            var next=resolver.resolve("PROFILE_REASSIGN",proposal.publicId(),NOW);
            assertTrue(next.stream().anyMatch(r->"REVIEW_REASSIGNED".equals(r.template())&&owner.account().equals(r.accountId())));
            Recipient reassigned=next.stream().filter(r->"REVIEW_REQUIRED".equals(r.template())).findFirst().orElseThrow();
            Intent current=intent(proposal,"PROFILE_REASSIGN",reassigned);
            assertTrue(resolver.eligible(current,NOW));
            jdbc.update("UPDATE role_assignments SET effective_to=? WHERE account_id=?",time(NOW),replacement.accountInternal());
            assertFalse(resolver.eligible(current,NOW)); role(jdbc,replacement,"HR_ADMIN",NOW);
            jdbc.update("UPDATE profile_proposal SET state='CANCELLED',current_assignment_id=NULL,version=version+1 WHERE id=?",proposal.id());
            close(jdbc,nextAssignment,"CLOSED");
            assertFalse(resolver.eligible(current,NOW));
            var withdrawn=resolver.resolve("PROFILE_WITHDRAW",proposal.publicId(),NOW);
            assertEquals(2,withdrawn.size());
            assertTrue(withdrawn.stream().allMatch(r->"REQUEST_CLOSED".equals(r.template())));
            Recipient former=withdrawn.stream().filter(r->"CLOSED_REVIEW".equals(r.eligibilityType())).findFirst().orElseThrow();
            Intent closed=intent(proposal,"PROFILE_WITHDRAW",former);
            assertTrue(resolver.eligible(closed,NOW));
            assertTrue(resolver.eligible(intent(proposal,"PROFILE_WITHDRAW",withdrawn.stream().filter(r->"OWNER".equals(r.eligibilityType())).findFirst().orElseThrow()),NOW));
            assertFalse(resolver.eligible(new Intent(closed.eventId(),closed.eventType(),closed.aggregateId(),NOW,closed.employeeId(),closed.accountId(),closed.template(),closed.eligibilityType(),UUID.randomUUID()),NOW));
            jdbc.update("UPDATE role_assignments SET effective_to=? WHERE account_id=? AND effective_to IS NULL",time(NOW),replacement.accountInternal());
            assertFalse(resolver.eligible(closed,NOW));
        }
    }
    @Test void blockedRouteNotifiesApplicantAndOnlyRemainsRecoverableUntilAReviewerExists() {
        try(var fixture=new PostgresFixture()) {
            fixture.flyway().migrate(); fixture.grantRuntime(); JdbcTemplate jdbc=fixture.runtime();
            Person owner=person(jdbc,"EMPLOYEE"); Person hr=person(jdbc,"HR_ADMIN"); Proposal proposal=proposal(jdbc,owner);
            var resolver=new ProfileNotificationResolver(jdbc);
            var blocked=resolver.resolve("PROFILE_ROUTE_RETRY",proposal.publicId(),NOW);
            assertEquals(1,blocked.size()); assertEquals("REVIEW_BLOCKED",blocked.getFirst().template());
            Intent recovery=new Intent(UUID.randomUUID(),"PROFILE_ROUTE_RETRY",proposal.publicId(),NOW,hr.employee(),hr.account(),"REVIEW_BLOCKED","HR_RECOVERY",owner.employee());
            assertTrue(resolver.eligible(recovery,NOW));
            assign(jdbc,proposal,hr); assertFalse(resolver.eligible(recovery,NOW));
            Recipient reviewer=resolver.resolve("PROFILE_RESUBMIT",proposal.publicId(),NOW).getFirst();
            Intent pinned=intent(proposal,"PROFILE_RESUBMIT",reviewer);
            assertFalse(resolver.eligible(new Intent(pinned.eventId(),pinned.eventType(),pinned.aggregateId(),NOW,pinned.employeeId(),pinned.accountId(),pinned.template(),pinned.eligibilityType(),UUID.randomUUID()),NOW));
            Intent missing=new Intent(UUID.randomUUID(),"PROFILE_SUBMIT",UUID.randomUUID(),NOW,hr.employee(),hr.account(),"REVIEW_REQUIRED","ORGANIZATION_REVIEW",UUID.randomUUID());
            assertFalse(resolver.eligible(missing,NOW));
            jdbc.update("UPDATE profile_proposal SET state='REJECTED',version=version+1 WHERE id=?",proposal.id());
            assertFalse(resolver.eligible(recovery,NOW));
            jdbc.update("UPDATE account_bindings SET revoked_at=? WHERE employee_id=? AND revoked_at IS NULL",time(NOW),owner.id());
            assertTrue(resolver.resolve("PROFILE_REJECT",proposal.publicId(),NOW).stream().allMatch(r->r.accountId()==null));
        }
    }
    @ParameterizedTest @ValueSource(strings={"APPROVE","REJECT","RETURN"})
    void terminalProfileDecisionNotifiesApplicantWithoutSendingAnotherReviewRequest(String decision) {
        try(var fixture=new PostgresFixture()) {
            fixture.flyway().migrate(); fixture.grantRuntime(); JdbcTemplate jdbc=fixture.runtime();
            Person owner=person(jdbc,"EMPLOYEE"); Person reviewer=person(jdbc,"HR_ADMIN"); Proposal proposal=proposal(jdbc,owner);
            UUID assignment=assign(jdbc,proposal,reviewer);
            jdbc.update("INSERT INTO profile_decision(proposal_id,revision_id,assignment_id,action,actor_account_public_id,reason,created_at) SELECT ?,?,id,?,?,'Reviewed',? FROM profile_assignment WHERE public_id=?",proposal.id(),proposal.revision(),decision,reviewer.account(),time(NOW),assignment);
            String state=switch(decision) { case "APPROVE"->"APPROVED"; case "REJECT"->"REJECTED"; default->"RETURNED"; };
            jdbc.update("UPDATE profile_proposal SET state=?,current_assignment_id=NULL,version=version+1 WHERE id=?",state,proposal.id()); close(jdbc,assignment,"CLOSED");
            var notices=new ProfileNotificationResolver(jdbc).resolve("PROFILE_"+decision,proposal.publicId(),NOW);
            assertEquals(1,notices.size()); assertEquals(owner.account(),notices.getFirst().accountId()); assertEquals("REQUEST_STATUS",notices.getFirst().template());
        }
    }
    private static Proposal proposal(JdbcTemplate jdbc,Person owner) {
        UUID assignment=UUID.randomUUID();
        jdbc.update("INSERT INTO employee_assignments(public_id,employee_id,org_unit,job_level,valid_from,created_at) VALUES (?,?,'ENG','L3',?,?)",assignment,owner.id(),time(NOW.minusSeconds(60)),time(NOW));
        UUID publicId=UUID.randomUUID();
        long id=jdbc.queryForObject("INSERT INTO profile_proposal(public_id,employee_id,state,created_at,updated_at) VALUES (?,?,'DRAFT',?,?) RETURNING id",Long.class,publicId,owner.id(),time(NOW),time(NOW));
        long revision=jdbc.queryForObject("INSERT INTO profile_revision(public_id,proposal_id,revision_number,evaluated_org_unit,evaluated_job_level,org_assignment_public_id,created_at) VALUES (gen_random_uuid(),?,1,'ENG','L3',?,?) RETURNING id",Long.class,id,assignment,time(NOW));
        jdbc.update("INSERT INTO profile_revision_change(revision_id,field_name,proposed_value,baseline_version) VALUES (?,'personalPhone','+620000000000',0)",revision);
        jdbc.update("UPDATE profile_revision SET sealed=true WHERE id=?",revision);
        jdbc.update("UPDATE profile_proposal SET state='PENDING',current_revision_id=?,version=version+1 WHERE id=?",revision,id);
        return new Proposal(id,publicId,revision,owner);
    }
    private static UUID assign(JdbcTemplate jdbc,Proposal proposal,Person reviewer) {
        UUID id=UUID.randomUUID();
        long internal=jdbc.queryForObject("INSERT INTO profile_assignment(public_id,proposal_id,revision_id,assignee_account_public_id,created_at) VALUES (?,?,?,?,?) RETURNING id",Long.class,id,proposal.id(),proposal.revision(),reviewer.account(),time(NOW));
        jdbc.update("UPDATE profile_proposal SET current_assignment_id=?,version=version+1 WHERE id=?",internal,proposal.id()); return id;
    }
    private static void close(JdbcTemplate jdbc,UUID assignment,String status) { jdbc.update("UPDATE profile_assignment SET status=?,closed_at=? WHERE public_id=?",status,time(NOW),assignment); }
    private static Intent intent(Proposal proposal,String type,Recipient recipient) { return new Intent(UUID.randomUUID(),type,proposal.publicId(),NOW,recipient.employeeId(),recipient.accountId(),recipient.template(),recipient.eligibilityType(),recipient.assignmentId()); }
    private static Person person(JdbcTemplate jdbc,String role) {
        UUID employee=UUID.randomUUID(); UUID account=UUID.randomUUID();
        long accountInternal=jdbc.queryForObject("INSERT INTO user_accounts(public_id,oidc_issuer,oidc_subject,created_at) VALUES (?,'https://profile-notification.test',?,?) RETURNING id",Long.class,account,account.toString(),time(NOW));
        long employeeInternal=jdbc.queryForObject("INSERT INTO employees(public_id,employee_number,created_at) VALUES (?,?,?) RETURNING id",Long.class,employee,"PROFILE-NOTIFY-"+employee,time(NOW));
        jdbc.update("INSERT INTO account_bindings(employee_id,employee_public_id,account_id,oidc_issuer,oidc_subject,created_at) VALUES (?,?,?,'https://profile-notification.test',?,?)",employeeInternal,employee,accountInternal,account.toString(),time(NOW));
        Person result=new Person(employeeInternal,employee,accountInternal,account); role(jdbc,result,role,NOW.minusSeconds(60)); return result;
    }
    private static void role(JdbcTemplate jdbc,Person person,String role,Instant from) { jdbc.update("INSERT INTO role_assignments(account_id,role,scope,effective_from,created_at) VALUES (?,?,?, ?,?)",person.accountInternal(),role,"EMPLOYEE".equals(role)?null:"ENG",time(from),time(NOW)); }
    private static Timestamp time(Instant at) { return Timestamp.from(at); }
}
