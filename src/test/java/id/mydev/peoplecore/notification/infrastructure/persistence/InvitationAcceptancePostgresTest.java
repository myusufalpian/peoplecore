package id.mydev.peoplecore.notification.infrastructure.persistence;

import id.mydev.peoplecore.common.audit.AuditEventWriter;
import id.mydev.peoplecore.common.command.AdmissionGuard;
import id.mydev.peoplecore.common.security.OperationAccessService;
import id.mydev.peoplecore.identity.application.service.AccountAccessService;
import id.mydev.peoplecore.identity.application.service.IdentityNotificationAccess;
import id.mydev.peoplecore.identity.domain.InvitationCipher;
import id.mydev.peoplecore.identity.infrastructure.persistence.JdbcNotificationIdentityRepository;
import id.mydev.peoplecore.notification.application.NotificationAcceptance;
import id.mydev.peoplecore.notification.application.NotificationSupportService;
import id.mydev.peoplecore.notification.application.NotificationTransactions;
import id.mydev.peoplecore.notification.application.SesFeedbackService;
import id.mydev.peoplecore.notification.domain.NotificationModels.Delivery;
import id.mydev.peoplecore.notification.domain.NotificationModels.Intent;
import id.mydev.peoplecore.organization.application.service.EmployeeService;
import id.mydev.peoplecore.support.PostgresFixture;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("postgres")
class InvitationAcceptancePostgresTest {
    private static final Instant NOW=Instant.parse("2026-11-13T10:00:00Z");
    private static final Clock CLOCK=Clock.fixed(NOW,ZoneOffset.UTC);
    private static final String TOPIC="arn:aws:sns:ap-southeast-1:123456789012:feedback";
    private static final ObjectMapper MAPPER=new ObjectMapper();
    private record Scenario(JdbcTemplate jdbc,JdbcNotificationRepository repository,JdbcNotificationIdentityRepository identities,
                            IdentityNotificationAccess identity,NotificationAcceptance acceptance,AdmissionGuard admission,
                            TransactionTemplate tx,UUID employee,UUID invitation,Delivery claimed,UUID attempt) { }

    @Test void synchronousAcceptanceWithoutFeedbackUpdatesBothPersistedStates() {
        try(var fixture=new PostgresFixture()) {
            Scenario s=scenario(fixture);
            var completion=new NotificationTransactions(s.repository(),s.identity(),List.of(),CLOCK,s.admission(),s.acceptance(),"https://app.example.test");
            s.tx().executeWithoutResult(status->completion.finish(s.claimed(),"ACCEPTED","provider-accepted",null,NOW));
            assertEquals("ACCEPTED",s.repository().find(s.claimed().id(),false).orElseThrow().state());
            assertEquals("SENT",summary(s));
            assertEquals(1,s.jdbc().queryForObject("SELECT count(public_id) FROM notification_evidence WHERE delivery_id=? AND outcome='ACCEPTED'",Integer.class,s.claimed().id()));
        }
    }

    @ParameterizedTest @ValueSource(strings={"Send","Delivery"})
    void trustedFeedbackBeforeSynchronousCompletionUpdatesBothPersistedStatesAndDuplicateIsSemanticallyIdempotent(String eventType) {
        try(var fixture=new PostgresFixture()) {
            Scenario s=scenario(fixture); var feedback=feedback(s);
            String body=body(s.claimed().id(),s.attempt(),eventType,"provider-accepted","feedback-before-finish");
            assertTrue(s.tx().<Boolean>execute(status->feedback.process(body)));
            assertEquals("Send".equals(eventType)?"ACCEPTED":"DELIVERED",s.repository().find(s.claimed().id(),false).orElseThrow().state());
            assertEquals("SENT",summary(s));
            var completion=new NotificationTransactions(s.repository(),s.identity(),List.of(),CLOCK,s.admission(),s.acceptance(),"https://app.example.test");
            s.tx().executeWithoutResult(status->completion.finish(s.claimed(),"ACCEPTED","provider-accepted",null,NOW));
            assertEquals("SENT",summary(s));
            assertTrue(s.tx().<Boolean>execute(status->feedback.process(body)));
            assertEquals("Send".equals(eventType)?"ACCEPTED":"DELIVERED",s.repository().find(s.claimed().id(),false).orElseThrow().state());
            assertEquals("SENT",summary(s));
            assertEquals(1,s.jdbc().queryForObject("SELECT count(public_id) FROM notification_evidence WHERE source_id='ses:feedback-before-finish'",Integer.class));
        }
    }

    @ParameterizedTest @ValueSource(strings={"Send","Delivery"})
    void uncertaintyCanResolveAfterRevocationAddressChangeAndSecretDisposalWithoutSendingOrDecrypting(String eventType) {
        try(var fixture=new PostgresFixture()) {
            Scenario s=scenario(fixture); unknown(s);
            s.jdbc().update("UPDATE enrollment_invitations SET status='REVOKED',revoked_at=? WHERE public_id=?",Timestamp.from(NOW),s.invitation());
            s.identities().wipeInvitation(s.invitation());
            s.identities().verifyDestination(s.employee(),"changed@example.test","new-verified-record","operator",NOW);
            assertEquals("PENDING",summary(s));
            assertTrue(s.tx().<Boolean>execute(status->feedback(s).process(body(s.claimed().id(),s.attempt(),eventType,"provider-accepted","resolve-unknown"))));
            assertEquals("Send".equals(eventType)?"ACCEPTED":"DELIVERED",s.repository().find(s.claimed().id(),false).orElseThrow().state());
            assertEquals("SENT",summary(s));
            assertEquals("REVOKED",s.jdbc().queryForObject("SELECT status FROM enrollment_invitations WHERE public_id=?",String.class,s.invitation()));
            assertEquals(0,s.jdbc().queryForObject("SELECT count(invitation_public_id) FROM invitation_delivery_source WHERE invitation_public_id=? AND ciphertext IS NOT NULL",Integer.class,s.invitation()));
        }
    }

    @Test void staleAttemptAndMismatchedProviderEvidenceCannotCreateAFalseInvitationAcceptance() {
        try(var fixture=new PostgresFixture()) {
            Scenario s=scenario(fixture); unknown(s);
            s.tx().executeWithoutResult(status->s.repository().retry(s.repository().find(s.claimed().id(),true).orElseThrow(),NOW));
            Delivery second=s.tx().execute(status->s.repository().claim(NOW).orElseThrow());
            UUID currentAttempt=s.repository().attemptId(second.id(),second.generation()).orElseThrow();
            assertTrue(s.tx().<Boolean>execute(status->feedback(s).process(body(second.id(),s.attempt(),"Send","old-provider","stale-attempt"))));
            assertEquals("PENDING",summary(s));
            assertEquals("SENDING",s.repository().find(second.id(),false).orElseThrow().state());
            s.tx().executeWithoutResult(status->s.repository().evidence(second.id(),currentAttempt,second.generation(),"ACCEPTED","current-provider",null,"trusted-current-proof",NOW));
            assertFalse(s.tx().<Boolean>execute(status->feedback(s).process(body(second.id(),currentAttempt,"Send","wrong-provider","wrong-provider-proof"))));
            assertEquals("PENDING",summary(s));
            assertTrue(s.tx().<Boolean>execute(status->feedback(s).process(body(second.id(),currentAttempt,"Delivery","current-provider","current-proof"))));
            assertEquals("DELIVERED",s.repository().find(second.id(),false).orElseThrow().state());
            assertEquals("SENT",summary(s));
        }
    }

    @Test void reconciliationOfPersistedAcceptanceEvidenceUsesTheSameSummaryMappingWithoutAnotherSend() {
        try(var fixture=new PostgresFixture()) {
            Scenario s=scenario(fixture); unknown(s);
            s.tx().executeWithoutResult(status->s.repository().evidence(s.claimed().id(),s.attempt(),s.claimed().generation(),"ACCEPTED","provider-accepted",null,"imported-trusted-proof",NOW));
            UUID evidence=s.jdbc().queryForObject("SELECT public_id FROM notification_evidence WHERE source_id='imported-trusted-proof'",UUID.class);
            var caller=mock(Authentication.class); when(caller.getName()).thenReturn("operator");
            var support=new NotificationSupportService(s.repository(),mock(OperationAccessService.class),mock(AuditEventWriter.class),s.admission(),CLOCK,new MockEnvironment(),s.identity(),List.of(),s.acceptance());
            Delivery unknown=s.repository().find(s.claimed().id(),false).orElseThrow();
            var result=s.tx().execute(status->support.reconcile(caller,unknown.id(),unknown.version(),"reconcile-proof","Provider acceptance verified",evidence));
            assertEquals("ACCEPTED",result.state()); assertEquals("SENT",summary(s));
            var replay=s.tx().execute(status->support.reconcile(caller,unknown.id(),unknown.version(),"reconcile-proof","Provider acceptance verified",evidence));
            assertEquals("ACCEPTED",replay.state()); assertEquals("SENT",summary(s));
            assertEquals(1,s.jdbc().queryForObject("SELECT count(public_id) FROM notification_attempt WHERE delivery_id=?",Integer.class,unknown.id()));
            assertEquals(1,s.jdbc().queryForObject("SELECT count(actor) FROM notification_operation WHERE delivery_id=?",Integer.class,unknown.id()));
        }
    }

    private static Scenario scenario(PostgresFixture fixture) {
        fixture.flyway().migrate(); fixture.grantRuntime(); JdbcTemplate jdbc=fixture.runtime();
        var repository=new JdbcNotificationRepository(jdbc); var identities=new JdbcNotificationIdentityRepository(jdbc);
        var tx=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        UUID employee=UUID.randomUUID(); UUID invitation=UUID.randomUUID(); UUID event=UUID.randomUUID();
        jdbc.update("INSERT INTO employees(public_id,employee_number,created_at) VALUES (?,?,?)",employee,"ACCEPTANCE-"+employee,Timestamp.from(NOW));
        var destination=identities.verifyDestination(employee,"verified@example.test","idp-evidence","operator",NOW);
        jdbc.update("INSERT INTO enrollment_invitations(public_id,employee_id,intended_identity_ref,expected_issuer,expected_subject,secret_hash,expires_at,issued_at) SELECT ?,id,'idp-evidence','https://acceptance.test','intended-subject',repeat('a',64),?,? FROM employees WHERE public_id=?",invitation,Timestamp.from(NOW.plusSeconds(3600)),Timestamp.from(NOW),employee);
        tx.executeWithoutResult(status->{
            identities.storeInvitation(invitation,employee,destination.id(),destination.version(),"opaque-ciphertext","key-v1",NOW);
            repository.storeIntent(new Intent(event,"INVITATION_ISSUE",invitation,NOW,employee,null,"INVITATION","INVITATION",null),Optional.of(destination),NOW);
            repository.materialize(event,NOW);
        });
        Delivery claimed=tx.execute(status->repository.claim(NOW).orElseThrow());
        UUID attempt=repository.attemptId(claimed.id(),claimed.generation()).orElseThrow();
        var identity=new IdentityNotificationAccess(identities,mock(AccountAccessService.class),mock(InvitationCipher.class),CLOCK,mock(EmployeeService.class));
        var admission=mock(AdmissionGuard.class); var acceptance=new NotificationAcceptance(repository,identity,admission);
        return new Scenario(jdbc,repository,identities,identity,acceptance,admission,tx,employee,invitation,claimed,attempt);
    }
    private static SesFeedbackService feedback(Scenario s) { return new SesFeedbackService(s.repository(),s.identity(),s.acceptance(),MAPPER,CLOCK,TOPIC); }
    private static void unknown(Scenario s) { s.tx().executeWithoutResult(status->s.repository().outcome(s.claimed(),"UNKNOWN",null,"TIMEOUT",NOW,NOW)); }
    private static String summary(Scenario s) { return s.jdbc().queryForObject("SELECT delivery_status FROM enrollment_invitations WHERE public_id=?",String.class,s.invitation()); }
    private static String body(UUID delivery,UUID attempt,String type,String provider,String source) {
        String event=MAPPER.writeValueAsString(Map.of("eventType",type,"mail",Map.of("messageId",provider,"tags",Map.of("delivery_id",List.of(delivery.toString()),"attempt_id",List.of(attempt.toString())))));
        return MAPPER.writeValueAsString(Map.of("Type","Notification","TopicArn",TOPIC,"MessageId",source,"Message",event));
    }
}
