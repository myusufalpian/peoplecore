package id.mydev.peoplecore.notification.infrastructure.persistence;

import id.mydev.peoplecore.identity.infrastructure.persistence.JdbcNotificationIdentityRepository;
import id.mydev.peoplecore.identity.application.service.IdentityNotificationAccess;
import id.mydev.peoplecore.common.outbox.OutboxEvent;
import id.mydev.peoplecore.notification.application.NotificationIntentService;
import id.mydev.peoplecore.notification.domain.NotificationResolver;
import id.mydev.peoplecore.notification.domain.NotificationModels.Recipient;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.ArrayList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import id.mydev.peoplecore.notification.domain.NotificationModels.Delivery;
import id.mydev.peoplecore.notification.domain.NotificationModels.Intent;
import id.mydev.peoplecore.support.PostgresFixture;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("postgres")
class NotificationDeliveryPostgresTest {
    private static final Instant NOW=Instant.parse("2026-11-13T10:00:00Z");
    private static final List<String> NOTIFICATION_EVENTS=List.of(
        "INVITATION_ISSUE","INVITATION_REISSUE",
        "PROFILE_SUBMIT","PROFILE_RESUBMIT","PROFILE_WITHDRAW","PROFILE_APPROVE","PROFILE_REJECT","PROFILE_RETURN","PROFILE_REASSIGN","PROFILE_ROUTE_RETRY",
        "ATTENDANCE_CORRECTION_SUBMIT","ATTENDANCE_CORRECTION_RESUBMIT","ATTENDANCE_CORRECTION_WITHDRAW","ATTENDANCE_CORRECTION_APPROVE",
        "ATTENDANCE_CORRECTION_REJECT","ATTENDANCE_CORRECTION_RETURN","ATTENDANCE_CORRECTION_REASSIGN","ATTENDANCE_CORRECTION_ROUTE_RETRY",
        "LEAVE_REQUEST_SUBMIT","LEAVE_REQUEST_RESUBMIT","LEAVE_REQUEST_WITHDRAW","LEAVE_REQUEST_APPROVE","LEAVE_REQUEST_REJECT",
        "LEAVE_REQUEST_RETURN","LEAVE_REQUEST_REASSIGN","LEAVE_REQUEST_RETRY_ROUTE","LEAVE_CANCELLATION_CREATE","LEAVE_CANCELLATION_WITHDRAW",
        "LEAVE_CANCELLATION_APPROVE","LEAVE_CANCELLATION_REJECT","LEAVE_CANCELLATION_REASSIGN","LEAVE_CANCELLATION_RETRY_ROUTE",
        "REIMBURSEMENT_SUBMIT","REIMBURSEMENT_RESUBMIT","REIMBURSEMENT_WITHDRAW","REIMBURSEMENT_APPROVE","REIMBURSEMENT_REJECT",
        "REIMBURSEMENT_RETURN","REIMBURSEMENT_REASSIGN","REIMBURSEMENT_RETRY_ROUTE","REIMBURSEMENT_RECORD_PAYMENT","REIMBURSEMENT_CORRECT_PAYMENT",
        "PAYSLIP_PUBLISHED","EXPORT_READY","EXPORT_FAILED","EXPORT_EXPIRED","EXPORT_INVALIDATED");

    @Test void everyProductNotificationEventMaterializesOnlyOnceWhenTheEventIsReplayed() {
        try(var fixture=new PostgresFixture()) {
            fixture.flyway().migrate(); fixture.grantRuntime(); JdbcTemplate jdbc=fixture.runtime(); var tx=transactions(jdbc);
            var repository=new JdbcNotificationRepository(jdbc); var identity=new JdbcNotificationIdentityRepository(jdbc);
            UUID employee=employee(jdbc);
            var destination=identity.verifyDestination(employee,"verified@example.test","idp-record","operator",NOW);
            List<UUID> eventIds=new ArrayList<>();
            for(String type:NOTIFICATION_EVENTS) {
                // 1. Positive recipient: valid verified destination -> materializes 1 delivery in PENDING state
                UUID event=UUID.randomUUID(); eventIds.add(event);
                Intent intent=new Intent(event,type,UUID.randomUUID(),NOW,employee,null,"REQUEST_STATUS","OWNER",null);
                tx.executeWithoutResult(status->repository.storeIntent(intent,Optional.of(destination),NOW));

                // 2. Suppressed/ineligible recipient (no destination) -> materializes FAILED delivery with DESTINATION_UNAVAILABLE attention
                UUID suppressedEvent=UUID.randomUUID();
                Intent suppressedIntent=new Intent(suppressedEvent,type,UUID.randomUUID(),NOW,employee,null,"REQUEST_STATUS","OWNER",null);
                tx.executeWithoutResult(status->repository.storeIntent(suppressedIntent,Optional.empty(),NOW));
                assertEquals(1,(int)tx.execute(status->repository.materialize(suppressedEvent,NOW)));
                assertEquals(0,(int)tx.execute(status->repository.materialize(suppressedEvent,NOW.plusSeconds(1)))); // duplicate suppressed event
                assertEquals("FAILED",jdbc.queryForObject("SELECT state FROM notification_delivery WHERE event_id=?",String.class,suppressedEvent));
                assertEquals("DESTINATION_UNAVAILABLE",jdbc.queryForObject("SELECT error_code FROM notification_delivery WHERE event_id=?",String.class,suppressedEvent));
                assertEquals(1,jdbc.queryForObject("SELECT count(public_id) FROM notification_attention WHERE event_id=? AND code='DESTINATION_UNAVAILABLE'",Integer.class,suppressedEvent));
            }
            // 3. Positive delivery assertion & Duplicate event suppression for every trigger
            for(UUID event:eventIds) {
                assertEquals(1,(int)tx.execute(status->repository.materialize(event,NOW)));
                assertEquals(0,(int)tx.execute(status->repository.materialize(event,NOW.plusSeconds(1)))); // duplicate/replayed event
                assertEquals(1,jdbc.queryForObject("SELECT count(public_id) FROM notification_delivery WHERE event_id=?",Integer.class,event));
                assertEquals("PENDING",jdbc.queryForObject("SELECT state FROM notification_delivery WHERE event_id=?",String.class,event));
            }
            assertEquals(NOTIFICATION_EVENTS.size()*2,jdbc.queryForObject("SELECT count(DISTINCT event_id) FROM notification_delivery",Integer.class));
            // Ensure only positive recipient deliveries are claimable (pending)
            assertEquals(NOTIFICATION_EVENTS.size(),jdbc.queryForObject("SELECT count(public_id) FROM notification_delivery WHERE state='PENDING'",Integer.class));
        }
    }

    @Test void deliverySurvivesLeaseLossWithoutRepeatingSendAndStaleWorkerCannotOverwrite() {
        try(var fixture=new PostgresFixture()) {
            fixture.flyway().migrate(); fixture.grantRuntime();
            JdbcTemplate jdbc=fixture.runtime(); var tx=transactions(jdbc);
            var repository=new JdbcNotificationRepository(jdbc);
            var identity=new JdbcNotificationIdentityRepository(jdbc);
            UUID employee=employee(jdbc);
            var destination=identity.verifyDestination(employee,"verified@example.test","idp-record","operator",NOW);
            UUID event=UUID.randomUUID();
            Intent intent=new Intent(event,"PAYSLIP_PUBLISHED",UUID.randomUUID(),NOW,employee,UUID.randomUUID(),"PAYSLIP_AVAILABLE","OWNER",null);
            tx.executeWithoutResult(status->repository.storeIntent(intent,Optional.of(destination),NOW));
            assertEquals(1,(int)tx.execute(status->repository.materialize(event,NOW)));
            assertEquals(0,(int)tx.execute(status->repository.materialize(event,NOW)));
            Delivery first=tx.execute(status->repository.claim(NOW).orElseThrow());
            tx.executeWithoutResult(status->repository.expireLeases(NOW.plusSeconds(60)));
            assertEquals("UNKNOWN",repository.find(first.id(),false).orElseThrow().state());
            assertTrue(tx.execute(status->repository.claim(NOW.plusSeconds(61))).isEmpty());
            tx.executeWithoutResult(status->repository.retry(repository.find(first.id(),true).orElseThrow(),NOW.plusSeconds(61)));
            Delivery replacement=tx.execute(status->repository.claim(NOW.plusSeconds(61)).orElseThrow());
            assertTrue(replacement.manualAttempt());
            assertTrue(replacement.generation()>first.generation());
            tx.executeWithoutResult(status->repository.outcome(first,"ACCEPTED","old-response",null,NOW,NOW.plusSeconds(62)));
            assertEquals("SENDING",repository.find(first.id(),false).orElseThrow().state());
            tx.executeWithoutResult(status->repository.outcome(replacement,"ACCEPTED","new-response",null,NOW,NOW.plusSeconds(63)));
            UUID attempt=repository.attemptId(first.id(),replacement.generation()).orElseThrow();
            assertFalse(Boolean.TRUE.equals(tx.execute(status->repository.feedback(first.id(),attempt,"spoofed-provider-id","DELIVERED","spoof",NOW.plusSeconds(64)))));
            assertTrue(Boolean.TRUE.equals(tx.execute(status->repository.feedback(first.id(),attempt,"new-response","DELIVERED","real",NOW.plusSeconds(64)))));
            assertEquals("DELIVERED",repository.find(first.id(),false).orElseThrow().state());
            assertTrue(Boolean.TRUE.equals(tx.execute(status->repository.feedback(first.id(),attempt,"new-response","ACCEPTED","late-send",NOW.plusSeconds(65)))));
            assertEquals("DELIVERED",repository.find(first.id(),false).orElseThrow().state());
            assertThrows(DataAccessException.class,()->jdbc.update("UPDATE notification_attempt SET generation=99 WHERE delivery_id=?",first.id()));
            assertThrows(DataAccessException.class,()->jdbc.update("UPDATE notification_delivery SET employee_public_id=? WHERE public_id=?",UUID.randomUUID(),first.id()));
        }
    }

    @Test void rollbackKeepsIntentAndDeliveryAtomicAndFrozenRecipientSurvivesDelayedIngestion() {
        try(var fixture=new PostgresFixture()) {
            fixture.flyway().migrate(); fixture.grantRuntime(); JdbcTemplate jdbc=fixture.runtime();
            var repository=new JdbcNotificationRepository(jdbc); var identity=new JdbcNotificationIdentityRepository(jdbc); var tx=transactions(jdbc);
            UUID employee=employee(jdbc); UUID originalReviewer=UUID.randomUUID(); UUID event=UUID.randomUUID();
            var destination=identity.verifyDestination(employee,"original@example.test","independent-verification","operator",NOW);
            Intent intent=new Intent(event,"PROFILE_SUBMIT",UUID.randomUUID(),NOW,employee,originalReviewer,"REVIEW_REQUIRED","ORGANIZATION_REVIEW",UUID.randomUUID());
            tx.executeWithoutResult(status->{ repository.storeIntent(intent,Optional.of(destination),NOW); status.setRollbackOnly(); });
            assertEquals(0,(int)tx.execute(status->repository.materialize(event,NOW)));
            tx.executeWithoutResult(status->repository.storeIntent(intent,Optional.of(destination),NOW));
            identity.verifyDestination(employee,"replacement@example.test","new-verification","operator",NOW.plusSeconds(1));
            tx.executeWithoutResult(status->repository.materialize(event,NOW.plusSeconds(10)));
            Delivery delivery=tx.execute(status->repository.claim(NOW.plusSeconds(10)).orElseThrow());
            assertEquals(originalReviewer,delivery.accountId());
            assertEquals(0,delivery.destinationVersion());
            assertEquals(1,identity.currentDestination(employee).orElseThrow().version());
            assertThrows(DataAccessException.class,()->jdbc.update("UPDATE notification_intent SET account_public_id=? WHERE event_id=?",UUID.randomUUID(),event));
        }
    }

    @Test void invitationSourceIsDurableButInvalidInvitationCannotReleaseItsSecret() {
        try(var fixture=new PostgresFixture()) {
            fixture.flyway().migrate(); fixture.grantRuntime(); JdbcTemplate jdbc=fixture.runtime(); var tx=transactions(jdbc);
            var identity=new JdbcNotificationIdentityRepository(jdbc); UUID employee=employee(jdbc); UUID invitation=UUID.randomUUID();
            var destination=identity.verifyDestination(employee,"intended@example.test","verified-idp","operator",NOW);
            jdbc.update("INSERT INTO enrollment_invitations(public_id,employee_id,intended_identity_ref,expected_issuer,expected_subject,secret_hash,expires_at,issued_at) "
                + "SELECT ?,id,'verified-idp','https://issuer.test','verified-subject',repeat('a',64),?,? FROM employees WHERE public_id=?",
                invitation,Timestamp.from(NOW.plusSeconds(3600)),Timestamp.from(NOW),employee);
            tx.executeWithoutResult(status->identity.storeInvitation(invitation,employee,destination.id(),destination.version(),"authenticated-ciphertext","key-v1",NOW));
            assertNotNull(tx.execute(status->identity.lockInvitation(invitation,NOW).orElseThrow()));
            jdbc.update("UPDATE enrollment_invitations SET status='REVOKED',revoked_at=? WHERE public_id=?",Timestamp.from(NOW.plusSeconds(1)),invitation);
            assertTrue(tx.execute(status->identity.lockInvitation(invitation,NOW.plusSeconds(1))).isEmpty());
            tx.executeWithoutResult(status->identity.cleanupInvalidInvitations(NOW.plusSeconds(1)));
            assertEquals(0,jdbc.queryForObject("SELECT count(invitation_public_id) FROM invitation_delivery_source WHERE ciphertext IS NOT NULL",Integer.class));
            assertThrows(DataAccessException.class,()->jdbc.update("UPDATE invitation_delivery_source SET ciphertext='new-secret' WHERE invitation_public_id=?",invitation));
        }
    }

    @Test void sharedRateBudgetCannotMultiplyAcrossRepositoryInstances() {
        try(var fixture=new PostgresFixture()) {
            fixture.flyway().migrate(); fixture.grantRuntime(); JdbcTemplate jdbc=fixture.runtime(); var tx=transactions(jdbc);
            var first=new JdbcNotificationRepository(jdbc); var second=new JdbcNotificationRepository(jdbc);
            for(int i=0;i<10;i++) assertTrue(Boolean.TRUE.equals(tx.execute(status->first.reserve("send",NOW,10))));
            assertFalse(Boolean.TRUE.equals(tx.execute(status->second.reserve("send",NOW,10))));
            assertTrue(Boolean.TRUE.equals(tx.execute(status->second.reserve("send",NOW.plusSeconds(1),10))));
        }
    }
    @Test void duplicateEligibleRecipientIsPinnedOnceWithDeterministicBlockedPriority() {
        try(var fixture=new PostgresFixture()) {
            fixture.flyway().migrate(); fixture.grantRuntime(); JdbcTemplate jdbc=fixture.runtime(); var tx=transactions(jdbc);
            var repository=new JdbcNotificationRepository(jdbc); var source=new JdbcNotificationIdentityRepository(jdbc);
            UUID employee=employee(jdbc); UUID account=UUID.randomUUID();
            var destination=source.verifyDestination(employee,"verified@example.test","idp-record","operator",NOW);
            var identity=mock(IdentityNotificationAccess.class); when(identity.destination(employee)).thenReturn(Optional.of(destination));
            var resolver=mock(NotificationResolver.class); when(resolver.supports("PROFILE_REASSIGN")).thenReturn(true);
            var event=new OutboxEvent(UUID.randomUUID(),"PROFILE_REASSIGN",1,"PROFILE_PROPOSAL",null,UUID.randomUUID(),"{}",NOW);
            when(resolver.resolve(event.getEventType(),event.getAggregatePublicId(),NOW)).thenReturn(List.of(
                new Recipient(employee,account,"REVIEW_REASSIGNED","OWNER",null),new Recipient(employee,account,"REVIEW_BLOCKED","OWNER",null)));
            var observer=new NotificationIntentService(List.of(resolver),repository,identity,Clock.fixed(NOW,ZoneOffset.UTC),"");
            tx.executeWithoutResult(status->observer.created(event));
            assertEquals(1,jdbc.queryForObject("SELECT count(event_id) FROM notification_intent WHERE event_id=?",Integer.class,event.getEventId()));
            assertEquals(1,(int)tx.execute(status->repository.materialize(event.getEventId(),NOW)));
            assertEquals("REVIEW_BLOCKED",tx.execute(status->repository.claim(NOW).orElseThrow()).template());
        }
    }
    private static UUID employee(JdbcTemplate jdbc) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO employees(public_id,employee_number,created_at) VALUES (?,?,?)",id,"NOTIFY-"+id,Timestamp.from(NOW));
        return id;
    }
    private static TransactionTemplate transactions(JdbcTemplate jdbc) {
        return new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    }
}
