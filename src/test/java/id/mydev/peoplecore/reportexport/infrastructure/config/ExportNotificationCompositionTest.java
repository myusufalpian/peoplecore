package id.mydev.peoplecore.reportexport.infrastructure.config;

import id.mydev.peoplecore.common.command.AdmissionGuard;
import id.mydev.peoplecore.common.outbox.OutboxEvent;
import id.mydev.peoplecore.common.outbox.OutboxEventRepository;
import id.mydev.peoplecore.identity.application.service.AccountAccessService;
import id.mydev.peoplecore.identity.application.service.AuthorizationFenceService;
import id.mydev.peoplecore.identity.application.service.IdentityNotificationAccess;
import id.mydev.peoplecore.notification.application.NotificationIntentService;
import id.mydev.peoplecore.notification.domain.repository.NotificationRepository;
import id.mydev.peoplecore.reportexport.application.ExportNotificationResolver;
import id.mydev.peoplecore.reportexport.application.ReportExportService;
import id.mydev.peoplecore.reportexport.domain.repository.ReportExportRepository;
import id.mydev.peoplecore.reportexport.domain.repository.ReportExportRepository.Job;
import id.mydev.peoplecore.reporting.application.ScopedReportService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExportNotificationCompositionTest {
    @Test
    void enabledExportAndNotificationModulesComposeWithoutCircularCreationAndRetainMissingRecipientAttention() {
        Instant now=Instant.parse("2026-11-13T10:00:00Z");
        UUID jobId=UUID.randomUUID();
        Job job=new Job(jobId,UUID.randomUUID(),"https://identity.example","requester","{}","GENERATING",now,now.plusSeconds(86400),
            null,0,null,1,now.plusSeconds(120),null,null,null,null);
        ReportExportRepository exports=mock(ReportExportRepository.class);
        when(exports.find(jobId,false)).thenReturn(Optional.of(job));
        when(exports.finish(jobId,1,"FAILED","GENERATION_FAILED",now)).thenReturn(true);
        OutboxEventRepository outbox=mock(OutboxEventRepository.class);
        when(outbox.save(any(OutboxEvent.class))).thenAnswer(invocation->invocation.getArgument(0));
        NotificationRepository notifications=mock(NotificationRepository.class);
        new ApplicationContextRunner().withUserConfiguration(ReportExportService.class,ExportNotificationResolver.class,NotificationIntentService.class)
            .withPropertyValues("peoplecore.exports.enabled=true","peoplecore.notifications.enabled=true")
            .withBean(ReportExportRepository.class,()->exports)
            .withBean(AccountAccessService.class,()->mock(AccountAccessService.class))
            .withBean(AuthorizationFenceService.class,()->mock(AuthorizationFenceService.class))
            .withBean(AdmissionGuard.class,()->mock(AdmissionGuard.class))
            .withBean(ScopedReportService.class,()->mock(ScopedReportService.class))
            .withBean(ObjectMapper.class,()->JsonMapper.builder().build())
            .withBean(Clock.class,()->Clock.fixed(now,ZoneOffset.UTC))
            .withBean(PlatformTransactionManager.class,LocalTransactions::new)
            .withBean(OutboxEventRepository.class,()->outbox)
            .withBean(NotificationRepository.class,()->notifications)
            .withBean(IdentityNotificationAccess.class,()->mock(IdentityNotificationAccess.class))
            .run(context->{
                assertNull(context.getStartupFailure());
                assertNotNull(context.getBean(NotificationIntentService.class));
                context.getBean(ReportExportService.class).failed(job,"GENERATION_FAILED");
                verify(notifications).attention(any(UUID.class),isNull(),eq("RECIPIENT_UNAVAILABLE"),eq(now));
            });
    }

    private static class LocalTransactions extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction,TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}
