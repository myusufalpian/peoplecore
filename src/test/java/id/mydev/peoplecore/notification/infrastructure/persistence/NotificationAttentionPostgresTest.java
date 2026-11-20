package id.mydev.peoplecore.notification.infrastructure.persistence;

import id.mydev.peoplecore.common.command.AdmissionGuard;
import id.mydev.peoplecore.identity.application.service.IdentityNotificationAccess;
import id.mydev.peoplecore.identity.infrastructure.persistence.JdbcNotificationIdentityRepository;
import id.mydev.peoplecore.notification.application.NotificationAcceptance;
import id.mydev.peoplecore.notification.application.NotificationAttentionWorker;
import id.mydev.peoplecore.notification.application.SesFeedbackService;
import id.mydev.peoplecore.notification.domain.NotificationAttentionSink.Attention;
import id.mydev.peoplecore.notification.domain.NotificationAttentionSink;
import id.mydev.peoplecore.notification.domain.NotificationModels.Delivery;
import id.mydev.peoplecore.notification.domain.NotificationModels.Destination;
import id.mydev.peoplecore.notification.domain.NotificationModels.Intent;
import id.mydev.peoplecore.notification.infrastructure.provider.WebhookNotificationAttentionSink;
import id.mydev.peoplecore.support.PostgresFixture;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@Tag("postgres")
class NotificationAttentionPostgresTest {
    private static final Instant NOW=Instant.parse("2026-11-14T00:00:00Z");
    private static final String TOPIC="arn:aws:sns:ap-southeast-1:123456789012:feedback";
    private static final ObjectMapper MAPPER=new ObjectMapper();

    @ParameterizedTest @ValueSource(strings={"Reject","Rendering Failure","Bounce","Complaint"})
    void trustedPermanentProviderFailureCreatesOneDurableAlertEvenWithDuplicateFeedback(String type) {
        try(var fixture=new PostgresFixture()) {
            fixture.flyway().migrate(); fixture.grantRuntime();
            JdbcTemplate jdbc=fixture.runtime(); var tx=transactions(jdbc); var repository=new JdbcNotificationRepository(jdbc);
            Delivery delivery=delivery(jdbc,repository,true).orElseThrow(); UUID attempt=repository.attemptId(delivery.id(),delivery.generation()).orElseThrow();
            var identity=mock(IdentityNotificationAccess.class);
            var service=new SesFeedbackService(repository,identity,new NotificationAcceptance(repository,identity,mock(AdmissionGuard.class)),MAPPER,
                Clock.fixed(NOW,ZoneOffset.UTC),TOPIC);
            String body=feedback(delivery.id(),attempt,type,"provider-id","feedback-id");
            assertEquals(Boolean.TRUE,tx.execute(status->service.process(body)));
            assertEquals(Boolean.TRUE,tx.execute(status->service.process(body)));
            assertEquals("FAILED",repository.find(delivery.id(),false).orElseThrow().state());
            assertEquals(1,repository.attentionCount());
            assertEquals(1,jdbc.queryForObject("SELECT count(public_id) FROM notification_attention",Integer.class));
            var alerts=new JdbcNotificationAttentionRepository(jdbc);
            var received=new ArrayList<Attention>();
            new NotificationAttentionWorker(alerts,received::add,Clock.fixed(NOW.plusSeconds(5),ZoneOffset.UTC),manager(jdbc)).poll();
            assertEquals(1,received.size()); assertEquals("FAILED",received.getFirst().code());
            assertEquals(delivery.id(),received.getFirst().deliveryId()); assertEquals(0,repository.attentionCount());
        }
    }
    @Test void staleAttemptEvidenceNeverRaisesANewCurrentFailureAlert() {
        try(var fixture=new PostgresFixture()) {
            fixture.flyway().migrate(); fixture.grantRuntime(); JdbcTemplate jdbc=fixture.runtime(); var tx=transactions(jdbc);
            var repository=new JdbcNotificationRepository(jdbc); Delivery first=delivery(jdbc,repository,true).orElseThrow();
            UUID firstAttempt=repository.attemptId(first.id(),first.generation()).orElseThrow();
            tx.executeWithoutResult(status->repository.outcome(first,"FAILED",null,"UNSENT",NOW,NOW));
            var alerts=new JdbcNotificationAttentionRepository(jdbc);
            new NotificationAttentionWorker(alerts,attention->{ },Clock.fixed(NOW,ZoneOffset.UTC),manager(jdbc)).poll();
            tx.executeWithoutResult(status->repository.retry(repository.find(first.id(),true).orElseThrow(),NOW));
            Delivery second=tx.execute(status->repository.claim(NOW).orElseThrow());
            tx.executeWithoutResult(status->repository.feedback(second.id(),firstAttempt,"old-provider","FAILED","late-old-attempt",NOW));
            assertEquals("SENDING",repository.find(second.id(),false).orElseThrow().state()); assertEquals(0,repository.attentionCount());
        }
    }
    @Test void alertOutageAndWorkerRestartRetainOldUnacknowledgedWorkUntilSuccessfulDelivery() {
        try(var fixture=new PostgresFixture()) {
            fixture.flyway().migrate(); fixture.grantRuntime(); JdbcTemplate jdbc=fixture.runtime(); var tx=transactions(jdbc);
            var repository=new JdbcNotificationRepository(jdbc); UUID event=UUID.randomUUID();
            tx.executeWithoutResult(status->{repository.attention(event,null,"RECIPIENT_UNAVAILABLE",NOW);status.setRollbackOnly();});
            assertEquals(0,repository.attentionCount());
            tx.executeWithoutResult(status->repository.attention(event,null,"RECIPIENT_UNAVAILABLE",NOW));
            tx.executeWithoutResult(status->repository.attention(event,null,"RECIPIENT_UNAVAILABLE",NOW));
            assertEquals(1,repository.attentionCount());
            var alerts=new JdbcNotificationAttentionRepository(jdbc); var received=new ArrayList<Attention>();
            var unavailable=new AtomicBoolean(true);
            NotificationAttentionSink sink=attention->{
                received.add(attention); assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                if(unavailable.get()) throw new IllegalStateException("channel unavailable");
            };
            assertTrue(new NotificationAttentionWorker(alerts,sink,Clock.fixed(NOW.plusSeconds(600),ZoneOffset.UTC),manager(jdbc)).poll());
            assertEquals(1,repository.attentionCount());
            assertFalse(new NotificationAttentionWorker(alerts,sink,Clock.fixed(NOW.plusSeconds(629),ZoneOffset.UTC),manager(jdbc)).poll());
            unavailable.set(false);
            assertTrue(new NotificationAttentionWorker(alerts,sink,Clock.fixed(NOW.plusSeconds(630),ZoneOffset.UTC),manager(jdbc)).poll());
            assertEquals(received.getFirst().id(),received.getLast().id()); assertEquals(0,repository.attentionCount());
            assertThrows(DataAccessException.class,()->jdbc.update("UPDATE notification_attention SET code='CHANGED'"));
            assertThrows(DataAccessException.class,()->jdbc.update("UPDATE notification_attention_alert SET delivered_at=NULL"));
            assertThrows(DataAccessException.class,()->jdbc.update("DELETE FROM notification_attention_alert"));
        }
    }
    @Test void competingReplicasAndExpiredLeasesCannotLoseOrPrematurelyAcknowledgeAlerts() throws Exception {
        try(var fixture=new PostgresFixture(); var executor=Executors.newSingleThreadExecutor()) {
            fixture.flyway().migrate(); fixture.grantRuntime(); JdbcTemplate jdbc=fixture.runtime(); var tx=transactions(jdbc);
            new JdbcNotificationRepository(jdbc).attention(UUID.randomUUID(),null,"UNKNOWN",NOW);
            var first=new JdbcNotificationAttentionRepository(jdbc); var second=new JdbcNotificationAttentionRepository(jdbc);
            var claim=tx.execute(status->{
                var held=first.claim(NOW).orElseThrow();
                try { assertTrue(executor.submit(()->transactions(jdbc).execute(other->second.claim(NOW))).get(5,TimeUnit.SECONDS).isEmpty()); }
                catch(Exception failure) { throw new IllegalStateException(failure); }
                return held;
            });
            var replacement=tx.execute(status->second.claim(NOW.plusSeconds(60)).orElseThrow());
            assertEquals(Boolean.FALSE,tx.execute(status->first.complete(claim,NOW.plusSeconds(61))));
            tx.executeWithoutResult(status->first.defer(claim,NOW.plusSeconds(61)));
            assertEquals(Boolean.TRUE,tx.execute(status->second.complete(replacement,NOW.plusSeconds(61))));
            assertTrue(tx.execute(status->first.claim(NOW.plusSeconds(120))).isEmpty());
        }
    }
    @Test void missingDestinationFailsWithDurableAttentionAndUpgradeQueuesPreviouslyUnalertedFailures() {
        try(var fixture=new PostgresFixture()) {
            Flyway.configure().dataSource(fixture.url,fixture.username,fixture.password).schemas(fixture.schema).target("33").load().migrate();
            JdbcTemplate jdbc=fixture.jdbc; var repository=new JdbcNotificationRepository(jdbc);
            Delivery legacy=delivery(jdbc,repository,true).orElseThrow();
            jdbc.update("UPDATE notification_delivery SET state='FAILED',lease_ends_at=NULL WHERE public_id=?",legacy.id());
            assertEquals(2,fixture.flyway().migrate().migrationsExecuted); fixture.grantRuntime();
            assertEquals(1,repository.attentionCount());
            delivery(jdbc,repository,false);
            assertEquals(2,repository.attentionCount());
        }
    }
    @Test void persistedAlertReachesIndependentLoopbackWebhookAndRecoversAfterAnHttpOutage() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var response=new AtomicInteger(503); var observed=new AtomicReference<String>(); var key=new AtomicReference<String>();
        server.createContext("/alerts",exchange->{
            observed.set(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            key.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            exchange.sendResponseHeaders(response.get(),-1); exchange.close();
        });
        server.start();
        try(var fixture=new PostgresFixture(); var sink=new WebhookNotificationAttentionSink(
                URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/alerts"),"local-channel-token",MAPPER)) {
            fixture.flyway().migrate(); fixture.grantRuntime(); JdbcTemplate jdbc=fixture.runtime();
            var repository=new JdbcNotificationRepository(jdbc); repository.attention(UUID.randomUUID(),null,"UNKNOWN",NOW);
            var alerts=new JdbcNotificationAttentionRepository(jdbc);
            new NotificationAttentionWorker(alerts,sink,Clock.fixed(NOW.plusSeconds(5),ZoneOffset.UTC),manager(jdbc)).poll();
            String first=key.get(); assertEquals(1,repository.attentionCount());
            response.set(204);
            new NotificationAttentionWorker(alerts,sink,Clock.fixed(NOW.plusSeconds(35),ZoneOffset.UTC),manager(jdbc)).poll();
            assertEquals(first,key.get()); assertEquals(0,repository.attentionCount());
            assertEquals("UNKNOWN",MAPPER.readTree(observed.get()).path("code").asText());
            assertFalse(observed.get().contains("token"));
            assertEquals(Timestamp.from(NOW.plusSeconds(35)),jdbc.queryForObject("SELECT delivered_at FROM notification_attention_alert",Timestamp.class));
        } finally { server.stop(0); }
    }
    private static Optional<Delivery> delivery(JdbcTemplate jdbc,JdbcNotificationRepository repository,boolean verified) {
        UUID employee=UUID.randomUUID(); UUID event=UUID.randomUUID();
        jdbc.update("INSERT INTO employees(public_id,employee_number,created_at) VALUES (?,?,?)",employee,"ALERT-"+employee,Timestamp.from(NOW));
        Optional<Destination> destination=verified?Optional.of(new JdbcNotificationIdentityRepository(jdbc).verifyDestination(employee,"controlled@example.test","verified-record","operator",NOW)):Optional.empty();
        var tx=transactions(jdbc);
        tx.executeWithoutResult(status->{repository.storeIntent(new Intent(event,"PAYSLIP_PUBLISHED",UUID.randomUUID(),NOW,employee,UUID.randomUUID(),"PAYSLIP_AVAILABLE","OWNER",null),destination,NOW);repository.materialize(event,NOW);});
        return verified?tx.execute(status->repository.claim(NOW)):Optional.empty();
    }
    private static DataSourceTransactionManager manager(JdbcTemplate jdbc) { return new DataSourceTransactionManager(jdbc.getDataSource()); }
    private static TransactionTemplate transactions(JdbcTemplate jdbc) { return new TransactionTemplate(manager(jdbc)); }
    private static String feedback(UUID delivery,UUID attempt,String type,String provider,String source) {
        String event=MAPPER.writeValueAsString(Map.of("eventType",type,"mail",Map.of("messageId",provider,"tags",Map.of("delivery_id",List.of(delivery.toString()),"attempt_id",List.of(attempt.toString())))));
        return MAPPER.writeValueAsString(Map.of("Type","Notification","TopicArn",TOPIC,"MessageId",source,"Message",event));
    }
}
