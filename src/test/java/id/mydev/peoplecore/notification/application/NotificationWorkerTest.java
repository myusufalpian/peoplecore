package id.mydev.peoplecore.notification.application;

import id.mydev.peoplecore.notification.domain.EmailProvider;
import id.mydev.peoplecore.notification.domain.NotificationModels.Delivery;
import id.mydev.peoplecore.notification.domain.NotificationModels.Message;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationWorkerTest {
    private static final Instant NOW=Instant.parse("2026-11-13T10:00:00Z");
    private static final Clock CLOCK=Clock.fixed(NOW,ZoneOffset.UTC);
    private final NotificationTransactions transactions=mock(NotificationTransactions.class);
    private final EmailProvider provider=mock(EmailProvider.class);
    private final MockEnvironment environment=new MockEnvironment();
    private final NotificationWorker worker=new NotificationWorker(transactions,provider,CLOCK,environment);

    @Test void providerAcceptanceIsRecordedWithoutAnotherDomainAction() {
        Delivery delivery=due(1,false);
        Message message=ready(delivery);
        when(provider.send(message)).thenReturn("provider-accepted");
        assertTrue(worker.poll());
        verify(transactions).finish(delivery,"ACCEPTED","provider-accepted",null,NOW);
    }
    @Test void uncertainProviderCallNeverBecomesAnAutomaticRetry() {
        Delivery delivery=due(1,false);
        Message message=ready(delivery);
        when(provider.send(message)).thenThrow(new IllegalStateException("secret-bearing provider body"));
        assertTrue(worker.poll());
        verify(transactions).finish(delivery,"UNKNOWN",null,"UNCERTAIN_ATTEMPT",NOW);
    }
    @Test void onlyDefiniteNonacceptancePermitsBoundedAutomaticRetry() {
        Delivery delivery=due(1,false);
        Message message=ready(delivery);
        when(provider.send(message)).thenThrow(new EmailProvider.DefinitelyUnsentException(true));
        assertTrue(worker.poll());
        verify(transactions).finish(eq(delivery),eq("PENDING"),isNull(),eq("PROVIDER_REJECTED"),any(Instant.class));
    }
    @Test void manualRetryAuthorizesOneAttemptOnly() {
        Delivery delivery=due(1,true);
        Message message=ready(delivery);
        when(provider.send(message)).thenThrow(new EmailProvider.DefinitelyUnsentException(true));
        assertTrue(worker.poll());
        verify(transactions).finish(eq(delivery),eq("FAILED"),isNull(),eq("PROVIDER_REJECTED"),any(Instant.class));
    }
    @Test void thirdAutomaticAttemptIsTheLast() {
        Delivery delivery=due(3,false);
        Message message=ready(delivery);
        when(provider.send(message)).thenThrow(new EmailProvider.DefinitelyUnsentException(true));
        assertTrue(worker.poll());
        verify(transactions).finish(eq(delivery),eq("FAILED"),isNull(),eq("PROVIDER_REJECTED"),any(Instant.class));
    }
    @Test void unavailableKeyDefersWithoutCallingTheProvider() {
        Delivery delivery=due(1,false);
        when(transactions.claim()).thenReturn(Optional.of(delivery));
        when(transactions.prepare(delivery)).thenThrow(new IllegalStateException("key unavailable"));
        assertTrue(worker.poll());
        verify(provider,never()).send(any());
        verify(transactions).finish(delivery,"PENDING",null,"PREPARATION_UNAVAILABLE",NOW.plusSeconds(30));
    }
    @Test void suppressedRecipientDoesNotReachProvider() {
        Delivery delivery=due(1,false);
        when(transactions.claim()).thenReturn(Optional.of(delivery));
        when(transactions.prepare(delivery)).thenReturn(Optional.empty());
        assertTrue(worker.poll());
        verify(provider,never()).send(any());
    }
    @Test void recoveryIsolationPreventsClaimsAndSends() {
        environment.setProperty("peoplecore.recovery.isolated","true");
        assertFalse(worker.poll());
        verify(transactions,never()).claim();
        verify(provider,never()).send(any());
    }
    @Test void noDueWorkDoesNotCallProvider() {
        when(transactions.claim()).thenReturn(Optional.empty());
        assertFalse(worker.poll());
        verify(provider,never()).send(any());
    }
    private Message ready(Delivery delivery) {
        Message message=new Message(delivery.id(),UUID.randomUUID(),"recipient@example.test","Generic","Reference only");
        when(transactions.claim()).thenReturn(Optional.of(delivery));
        when(transactions.prepare(delivery)).thenReturn(Optional.of(message));
        return message;
    }
    private static Delivery due(int attempts,boolean manual) {
        return new Delivery(UUID.randomUUID(),UUID.randomUUID(),"PAYSLIP_PUBLISHED",UUID.randomUUID(),NOW,
            UUID.randomUUID(),UUID.randomUUID(),"PAYSLIP_AVAILABLE","OWNER",null,UUID.randomUUID(),0,
            "SENDING",1,1,attempts,manual,NOW.plusSeconds(60),NOW);
    }
}
