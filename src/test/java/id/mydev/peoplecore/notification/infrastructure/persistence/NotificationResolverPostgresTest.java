package id.mydev.peoplecore.notification.infrastructure.persistence;

import id.mydev.peoplecore.notification.domain.NotificationModels.Intent;
import id.mydev.peoplecore.organization.infrastructure.persistence.ProfileNotificationResolver;
import id.mydev.peoplecore.support.PostgresFixture;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("postgres")
class NotificationResolverPostgresTest {
    private static final Instant NOW=Instant.parse("2026-11-13T10:00:00Z");
    @Test void reassignmentCannotRetargetAnOldEventAndRemovedScopeSuppressesThePinnedReviewer() {
        try(var fixture=new PostgresFixture()) {
            fixture.flyway().migrate(); fixture.grantRuntime(); JdbcTemplate jdbc=fixture.runtime();
            UUID owner=employee(jdbc); UUID reviewer=employee(jdbc); UUID account=bind(jdbc,reviewer);
            long ownerId=id(jdbc,"employees",owner);
            jdbc.update("INSERT INTO employee_assignments(public_id,employee_id,org_unit,job_level,valid_from,created_at) VALUES (gen_random_uuid(),?,'ENG','L3',?,?)",
                ownerId,Timestamp.from(NOW.minusSeconds(60)),Timestamp.from(NOW));
            jdbc.update("INSERT INTO role_assignments(account_id,role,scope,effective_from,created_at) SELECT id,'HR_ADMIN','ENG',?,? FROM user_accounts WHERE public_id=?",
                Timestamp.from(NOW.minusSeconds(60)),Timestamp.from(NOW),account);
            UUID proposal=UUID.randomUUID();
            jdbc.update("INSERT INTO profile_proposal(public_id,employee_id,state,created_at,updated_at) VALUES (?,?,'PENDING',?,?)",proposal,ownerId,Timestamp.from(NOW),Timestamp.from(NOW));
            long proposalId=id(jdbc,"profile_proposal",proposal);
            UUID revision=UUID.randomUUID();
            jdbc.update("INSERT INTO profile_revision(public_id,proposal_id,revision_number,created_at) VALUES (?,?,1,?)",revision,proposalId,Timestamp.from(NOW));
            long revisionId=id(jdbc,"profile_revision",revision);
            UUID assignment=UUID.randomUUID();
            jdbc.update("INSERT INTO profile_assignment(public_id,proposal_id,revision_id,assignee_account_public_id,created_at) VALUES (?,?,?,?,?)",assignment,proposalId,revisionId,account,Timestamp.from(NOW));
            jdbc.update("UPDATE profile_proposal SET current_revision_id=?,current_assignment_id=? WHERE public_id=?",revisionId,id(jdbc,"profile_assignment",assignment),proposal);
            var resolver=new ProfileNotificationResolver(jdbc);
            var recipient=resolver.resolve("PROFILE_SUBMIT",proposal,NOW).getFirst();
            assertEquals(account,recipient.accountId()); assertEquals(assignment,recipient.assignmentId());
            Intent pinned=new Intent(UUID.randomUUID(),"PROFILE_SUBMIT",proposal,NOW,reviewer,account,"REVIEW_REQUIRED",recipient.eligibilityType(),assignment);
            assertTrue(resolver.eligible(pinned,NOW));
            jdbc.update("UPDATE role_assignments SET effective_to=? WHERE account_id=(SELECT id FROM user_accounts WHERE public_id=?)",Timestamp.from(NOW),account);
            assertFalse(resolver.eligible(pinned,NOW));
            jdbc.update("UPDATE profile_assignment SET status='SUPERSEDED',closed_at=? WHERE public_id=?",Timestamp.from(NOW.plusSeconds(1)),assignment);
            UUID replacementAccount=bind(jdbc,employee(jdbc)); UUID replacement=UUID.randomUUID();
            jdbc.update("INSERT INTO profile_assignment(public_id,proposal_id,revision_id,assignee_account_public_id,created_at) VALUES (?,?,?,?,?)",replacement,proposalId,revisionId,replacementAccount,Timestamp.from(NOW.plusSeconds(1)));
            jdbc.update("UPDATE profile_proposal SET current_assignment_id=? WHERE public_id=?",id(jdbc,"profile_assignment",replacement),proposal);
            assertFalse(resolver.eligible(pinned,NOW.plusSeconds(2)));
            assertEquals(account,pinned.accountId());
        }
    }
    private static UUID employee(JdbcTemplate jdbc) {
        UUID id=UUID.randomUUID(); jdbc.update("INSERT INTO employees(public_id,employee_number,created_at) VALUES (?,?,?)",id,"RECIPIENT-"+id,Timestamp.from(NOW)); return id;
    }
    private static UUID bind(JdbcTemplate jdbc,UUID employee) {
        UUID id=UUID.randomUUID(); jdbc.update("INSERT INTO user_accounts(public_id,oidc_issuer,oidc_subject,created_at) VALUES (?,'https://notification.test',?,?)",id,id.toString(),Timestamp.from(NOW));
        jdbc.update("INSERT INTO account_bindings(employee_id,employee_public_id,account_id,oidc_issuer,oidc_subject,created_at) SELECT e.id,e.public_id,u.id,u.oidc_issuer,u.oidc_subject,? FROM employees e JOIN user_accounts u ON u.public_id=? WHERE e.public_id=?",Timestamp.from(NOW),id,employee); return id;
    }
    private static long id(JdbcTemplate jdbc,String table,UUID id) {
        return jdbc.queryForObject("SELECT id FROM "+table+" WHERE public_id=?",Long.class,id);
    }
}
