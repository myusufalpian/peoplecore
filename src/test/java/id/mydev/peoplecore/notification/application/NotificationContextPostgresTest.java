package id.mydev.peoplecore.notification.application;

import id.mydev.peoplecore.common.outbox.EventEnvelope;
import id.mydev.peoplecore.identity.application.command.EnrollmentCommands;
import id.mydev.peoplecore.identity.application.service.NotificationDestinationService;
import id.mydev.peoplecore.identity.domain.InvitationCipher;
import id.mydev.peoplecore.identity.domain.exception.InvitationConflictException;
import id.mydev.peoplecore.notification.application.NotificationWorker;
import id.mydev.peoplecore.notification.domain.EmailProvider;
import id.mydev.peoplecore.notification.domain.NotificationModels.Message;
import id.mydev.peoplecore.notification.domain.repository.NotificationRepository;
import id.mydev.peoplecore.support.JwtAuthFixture;
import id.mydev.peoplecore.support.PostgresFixture;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.kms.KmsClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@Tag("postgres")
@SpringBootTest(properties={"peoplecore.runtime.role=worker","spring.jpa.hibernate.ddl-auto=validate",
    "peoplecore.organization.allowed-units=ENG","peoplecore.notifications.enabled=true",
    "peoplecore.notifications.send.enabled=false","peoplecore.notifications.feedback.enabled=false",
    "peoplecore.notifications.application-url=https://app.example.test","peoplecore.notifications.region=ap-southeast-1",
    "peoplecore.notifications.invitation-key-id=test-only-key","peoplecore.exports.enabled=true","peoplecore.reports.enabled=true"})
@Import(NotificationContextPostgresTest.CryptoFixture.class)
class NotificationContextPostgresTest {
    private static final String ISSUER="https://notification-context.test";
    private static final PostgresFixture DATABASE=new PostgresFixture();
    static { DATABASE.flyway().migrate(); DATABASE.grantRuntime(); }
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",()->DATABASE.url);
        properties.add("spring.datasource.username",()->DATABASE.runtimeUsername);
        properties.add("spring.datasource.password",()->DATABASE.runtimePassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired EnrollmentCommands commands;
    @Autowired NotificationDestinationService destinations;
    @Autowired NotificationIntentService ingestion;
    @Autowired NotificationTransactions deliveries;
    @Autowired NotificationRepository repository;
    @Autowired PlatformTransactionManager manager;
    @AfterEach void clearCaller() { SecurityContextHolder.clearContext(); }
    @AfterAll static void closeDatabase() { DATABASE.close(); }

    @Test void fullFeatureContextPinsAndRecoversInvitationWithoutExternalSendAndReissueSuppressesOldWork() {
        String subject="operator-"+UUID.randomUUID();
        UUID operator=UUID.randomUUID();
        jdbc.update("INSERT INTO user_accounts(public_id,oidc_issuer,oidc_subject,created_at) VALUES (?,?,?,clock_timestamp())",operator,ISSUER,subject);
        for(String role:new String[]{"HR_ADMIN","SYSTEM_ADMIN"})
            jdbc.update("INSERT INTO role_assignments(account_id,role,effective_from,created_at) SELECT id,?,clock_timestamp()-interval '1 minute',clock_timestamp() FROM user_accounts WHERE public_id=?",role,operator);
        UUID employee=UUID.randomUUID();
        jdbc.update("INSERT INTO employees(public_id,employee_number,created_at) VALUES (?,?,clock_timestamp())",employee,"CONTEXT-"+employee);
        jdbc.update("INSERT INTO employee_assignments(public_id,employee_id,org_unit,job_level,valid_from,created_at) SELECT gen_random_uuid(),id,'ENG','L3',clock_timestamp()-interval '1 minute',clock_timestamp() FROM employees WHERE public_id=?",employee);
        var caller=JwtAuthFixture.token(ISSUER,subject); SecurityContextHolder.getContext().setAuthentication(caller);
        destinations.verify(employee,"controlled@example.test","trusted-idp-record","Independently verified","destination-"+employee,caller);
        var issued=commands.issue(caller,"issue-"+employee,employee,"trusted-idp-record",ISSUER,"recipient-"+employee);
        assertNotNull(issued.secret());
        UUID event=jdbc.queryForObject("SELECT event_id FROM notification_intent WHERE aggregate_public_id=? AND template='INVITATION'",UUID.class,issued.result().invitationId());
        var transaction=new TransactionTemplate(manager);
        transaction.executeWithoutResult(status->ingestion.ingest(new EventEnvelope(event,"INVITATION_ISSUE",1,"ENROLLMENT_INVITATION",issued.result().invitationId(),Instant.now(),null,Map.of())));
        transaction.executeWithoutResult(status->ingestion.ingest(new EventEnvelope(event,"INVITATION_ISSUE",1,"ENROLLMENT_INVITATION",issued.result().invitationId(),Instant.now(),null,Map.of())));
        assertEquals(1,jdbc.queryForObject("SELECT count(public_id) FROM notification_delivery WHERE event_id=?",Integer.class,event));
        var claimed=deliveries.claim().orElseThrow();
        var message=deliveries.prepare(claimed).orElseThrow();
        assertEquals("controlled@example.test",message.address());
        assertTrue(message.body().contains(issued.secret()));
        assertFalse(message.toString().contains(issued.secret()));
        assertEquals(0,jdbc.queryForObject("SELECT count(event_id) FROM outbox_events WHERE payload LIKE ?",Integer.class,"%"+issued.secret()+"%"));
        assertEquals(0,jdbc.queryForObject("SELECT count(id) FROM command_receipts WHERE result_payload LIKE ?",Integer.class,"%"+issued.secret()+"%"));
        var reissued=commands.reissue(caller,"reissue-"+employee,employee,"trusted-idp-record",ISSUER,"recipient-"+employee);
        assertTrue(deliveries.prepare(claimed).isEmpty());
        assertEquals("SUPPRESSED",repository.find(claimed.id(),false).orElseThrow().state());
        assertEquals(0,jdbc.queryForObject("SELECT count(invitation_public_id) FROM invitation_delivery_source WHERE invitation_public_id=? AND ciphertext IS NOT NULL",Integer.class,issued.result().invitationId()));

        UUID reissueEvent=jdbc.queryForObject("SELECT event_id FROM notification_intent WHERE aggregate_public_id=? AND template='INVITATION'",UUID.class,reissued.result().invitationId());
        EventEnvelope reissueEnvelope=new EventEnvelope(reissueEvent,"INVITATION_REISSUE",1,"ENROLLMENT_INVITATION",reissued.result().invitationId(),Instant.now(),null,Map.of());
        transaction.executeWithoutResult(status->ingestion.ingest(reissueEnvelope));
        transaction.executeWithoutResult(status->ingestion.ingest(reissueEnvelope));
        assertEquals(1,jdbc.queryForObject("SELECT count(public_id) FROM notification_delivery WHERE event_id=?",Integer.class,reissueEvent));
        var reissueDelivery=deliveries.claim().orElseThrow();
        assertEquals("INVITATION_REISSUE",reissueDelivery.eventType());
        var reissueMessage=deliveries.prepare(reissueDelivery).orElseThrow();
        assertEquals("controlled@example.test",reissueMessage.address());
        assertTrue(reissueMessage.body().contains(reissued.secret()));
    }

    @Test void localDummyProviderCompletesAuthorizedInvitationAndIntendedIdentityActivation() {
        Instant started=Instant.now();
        String subject="local-operator-"+UUID.randomUUID();
        UUID operator=UUID.randomUUID();
        jdbc.update("INSERT INTO user_accounts(public_id,oidc_issuer,oidc_subject,created_at) VALUES (?,?,?,clock_timestamp())",operator,ISSUER,subject);
        for(String role:new String[]{"HR_ADMIN","SYSTEM_ADMIN"})
            jdbc.update("INSERT INTO role_assignments(account_id,role,effective_from,created_at) SELECT id,?,clock_timestamp()-interval '1 minute',clock_timestamp() FROM user_accounts WHERE public_id=?",role,operator);
        UUID employee=UUID.randomUUID();
        jdbc.update("INSERT INTO employees(public_id,employee_number,created_at) VALUES (?,?,clock_timestamp())",employee,"LOCAL-"+employee);
        jdbc.update("INSERT INTO employee_assignments(public_id,employee_id,org_unit,job_level,valid_from,created_at) SELECT gen_random_uuid(),id,'ENG','L3',clock_timestamp()-interval '1 minute',clock_timestamp() FROM employees WHERE public_id=?",employee);
        var operatorCaller=JwtAuthFixture.token(ISSUER,subject);
        SecurityContextHolder.getContext().setAuthentication(operatorCaller);
        destinations.verify(employee,"local-recipient@example.test","local-idp-proof","Local acceptance fixture","local-destination-"+employee,operatorCaller);
        String expectedSubject="local-recipient-"+UUID.randomUUID();
        var issued=commands.issue(operatorCaller,"local-issue-"+employee,employee,"local-idp-proof",ISSUER,expectedSubject);
        UUID event=jdbc.queryForObject("SELECT event_id FROM notification_intent WHERE aggregate_public_id=? AND template='INVITATION'",UUID.class,issued.result().invitationId());
        new TransactionTemplate(manager).executeWithoutResult(status->ingestion.ingest(new EventEnvelope(event,"INVITATION_ISSUE",1,"ENROLLMENT_INVITATION",issued.result().invitationId(),Instant.now(),null,Map.of())));

        AtomicReference<Message> captured=new AtomicReference<>();
        EmailProvider localProvider=message->{captured.set(message); return "local-provider-"+message.attemptId();};
        var worker=new NotificationWorker(deliveries,localProvider,Clock.systemUTC(),new MockEnvironment());
        assertTrue(worker.poll());
        Message delivered=captured.get();
        assertNotNull(delivered);
        assertEquals("local-recipient@example.test",delivered.address());
        assertTrue(delivered.body().contains(issued.secret()));
        assertEquals("ACCEPTED",jdbc.queryForObject("SELECT state FROM notification_delivery WHERE aggregate_public_id=?",String.class,issued.result().invitationId()));
        assertEquals("SENT",jdbc.queryForObject("SELECT delivery_status FROM enrollment_invitations WHERE public_id=?",String.class,issued.result().invitationId()));
        assertTrue(Duration.between(started,Instant.now()).compareTo(Duration.ofMinutes(5))<0,"local provider acceptance exceeded five minutes");

        String fragment=delivered.body().substring(delivered.body().lastIndexOf('#')+1);
        String[] activation=fragment.split("&",2);
        UUID invitationId=UUID.fromString(activation[0].substring("invitationId=".length()));
        String secret=activation[1].substring("secret=".length());
        var wrongIdentity=JwtAuthFixture.token(ISSUER,"local-wrong-"+UUID.randomUUID());
        SecurityContextHolder.getContext().setAuthentication(wrongIdentity);
        assertThrows(InvitationConflictException.class,()->commands.activate(wrongIdentity,"local-wrong-activation-"+employee,invitationId,secret));
        var intendedIdentity=JwtAuthFixture.token(ISSUER,expectedSubject);
        SecurityContextHolder.getContext().setAuthentication(intendedIdentity);
        var accepted=commands.activate(intendedIdentity,"local-activation-"+employee,invitationId,secret);
        assertEquals(employee,accepted.employeeId());
        assertEquals(invitationId,accepted.invitationId());
        assertEquals(ISSUER,accepted.issuer());
        assertEquals(expectedSubject,accepted.subject());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM account_bindings WHERE employee_public_id=? AND oidc_issuer=? AND oidc_subject=? AND revoked_at IS NULL",Integer.class,employee,ISSUER,expectedSubject));
    }
    @TestConfiguration(proxyBeanMethods=false)
    static class CryptoFixture {
        @Bean @Primary KmsClient kmsFixture() { return mock(KmsClient.class); }
        @Bean @Primary InvitationCipher invitationCipherFixture() {
            return new InvitationCipher() {
                @Override public String keyId() { return "test-only-key"; }
                @Override public String encrypt(UUID invitation,UUID destination,String secret) {
                    return Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8));
                }
                @Override public String decrypt(UUID invitation,UUID destination,String ciphertext,String keyId) {
                    return new String(Base64.getDecoder().decode(ciphertext),StandardCharsets.UTF_8);
                }
            };
        }
    }
}
