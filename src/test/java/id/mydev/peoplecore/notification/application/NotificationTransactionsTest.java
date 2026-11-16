package id.mydev.peoplecore.notification.application;

import id.mydev.peoplecore.common.command.AdmissionGuard;
import id.mydev.peoplecore.identity.application.service.IdentityNotificationAccess;
import id.mydev.peoplecore.notification.domain.NotificationModels.Delivery;
import id.mydev.peoplecore.notification.domain.NotificationModels.Destination;
import id.mydev.peoplecore.notification.domain.NotificationResolver;
import id.mydev.peoplecore.notification.domain.repository.NotificationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationTransactionsTest {
    private static final Instant NOW=Instant.parse("2026-11-13T10:00:00Z");
    private final NotificationRepository repository=mock(NotificationRepository.class);
    private final IdentityNotificationAccess identity=mock(IdentityNotificationAccess.class);
    private final NotificationResolver resolver=mock(NotificationResolver.class);
    private final AdmissionGuard admission=mock(AdmissionGuard.class);
    private final NotificationAcceptance acceptance=mock(NotificationAcceptance.class);
    private final Clock clock=Clock.fixed(NOW,ZoneOffset.UTC);
    private final NotificationTransactions transactions=new NotificationTransactions(repository,identity,List.of(resolver),clock,admission,acceptance,"https://app.example.test/");

    @Test void onlyCurrentEligibleRecipientAndPinnedAddressCanProduceMessage() {
        Delivery delivery=delivery("PAYSLIP_AVAILABLE","SENDING",1,NOW.plusSeconds(60));
        ready(delivery);
        var message=transactions.prepare(delivery).orElseThrow();
        assertEquals("verified@example.test",message.address());
        assertTrue(message.body().contains(delivery.aggregateId().toString()));
        assertTrue(message.body().contains(delivery.eventType()));
        assertFalse(message.body().contains("verified@example.test"));
        assertFalse(message.toString().contains("verified@example.test"));
        verify(admission).acquire(AdmissionGuard.Mode.SHARED);
    }
    @Test void obsoleteLeaseCannotAuthorizeAProviderCall() {
        Delivery delivery=delivery("PAYSLIP_AVAILABLE","SENDING",1,NOW.plusSeconds(60));
        when(repository.find(delivery.id(),true)).thenReturn(Optional.of(withLease(delivery,"SENDING",2,NOW.plusSeconds(60))));
        assertTrue(transactions.prepare(delivery).isEmpty());
        when(repository.find(delivery.id(),true)).thenReturn(Optional.of(withLease(delivery,"SENDING",1,NOW)));
        assertTrue(transactions.prepare(delivery).isEmpty());
        when(repository.find(delivery.id(),true)).thenReturn(Optional.of(withLease(delivery,"UNKNOWN",1,null)));
        assertTrue(transactions.prepare(delivery).isEmpty());
        verify(repository,never()).attemptId(any(),anyLong());
    }
    @Test void removedAuthorityAndChangedDestinationAreSuppressed() {
        Delivery delivery=delivery("PAYSLIP_AVAILABLE","SENDING",1,NOW.plusSeconds(60)); ready(delivery);
        when(identity.accountEligible(NotificationTransactions.intent(delivery),NOW)).thenReturn(false);
        assertTrue(transactions.prepare(delivery).isEmpty());
        verify(repository).outcome(delivery,"SUPPRESSED",null,"RECIPIENT_INELIGIBLE",NOW,NOW);
        when(identity.accountEligible(NotificationTransactions.intent(delivery),NOW)).thenReturn(true);
        when(identity.destination(delivery.employeeId())).thenReturn(Optional.of(new Destination(delivery.destinationId(),delivery.employeeId(),"changed@example.test",1,"VERIFIED")));
        assertTrue(transactions.prepare(delivery).isEmpty());
    }
    @Test void invitationRechecksValidityAfterDecryptionAndKeepsSecretOutOfDiagnostics() {
        Delivery delivery=delivery("INVITATION","SENDING",1,NOW.plusSeconds(60)); ready(delivery);
        when(identity.invitation(delivery.aggregateId(),delivery.destinationId(),0)).thenReturn(Optional.of(new IdentityNotificationAccess.InvitationContent("verified@example.test","activation-secret")));
        when(identity.invitationEligible(delivery.aggregateId(),delivery.destinationId(),0)).thenReturn(true);
        var message=transactions.prepare(delivery).orElseThrow();
        assertTrue(message.body().contains("/activate#invitationId="));
        assertTrue(message.body().contains("activation-secret"));
        assertFalse(message.toString().contains("activation-secret"));
        when(identity.invitationEligible(delivery.aggregateId(),delivery.destinationId(),0)).thenReturn(false);
        assertTrue(transactions.prepare(delivery).isEmpty());
    }
    @Test void missingLegacyInvitationMaterialIsNotInvented() {
        Delivery delivery=delivery("INVITATION","SENDING",1,NOW.plusSeconds(60)); ready(delivery);
        when(identity.invitation(delivery.aggregateId(),delivery.destinationId(),0)).thenReturn(Optional.empty());
        assertTrue(transactions.prepare(delivery).isEmpty());
    }
    @Test void synchronousAcceptanceSharesTheTrustedCurrentAttemptMappingEvenAfterFeedbackWon() {
        Delivery delivery=delivery("INVITATION","SENDING",1,NOW.plusSeconds(60));
        UUID attempt=UUID.randomUUID();
        when(acceptance.lock(delivery.id())).thenReturn(delivery);
        when(repository.attemptId(delivery.id(),delivery.generation())).thenReturn(Optional.of(attempt));
        when(repository.outcome(delivery,"ACCEPTED","provider-id",null,NOW,NOW)).thenReturn(false);
        transactions.finish(delivery,"ACCEPTED","provider-id",null,NOW);
        verify(acceptance).lock(delivery.id()); verify(acceptance).accepted(delivery,attempt);
        verify(identity,never()).accepted(any());
    }
    @Test void unknownAndNonInvitationOutcomesDoNotMarkInvitationSent() {
        Delivery delivery=delivery("PAYSLIP_AVAILABLE","SENDING",1,NOW.plusSeconds(60));
        transactions.finish(delivery,"ACCEPTED","provider-id",null,NOW);
        transactions.finish(delivery,"UNKNOWN",null,"TIMEOUT",NOW);
        verify(identity,never()).accepted(any());
    }
    @Test void exhaustedGlobalBudgetCannotClaimAnotherSend() {
        when(repository.reserve("send",NOW,10)).thenReturn(false);
        assertTrue(transactions.claim().isEmpty());
        verify(identity).cleanup(); verify(repository).expireLeases(NOW);
        verify(repository,never()).claim(any());
        when(repository.reserve("send",NOW,10)).thenReturn(true);
        when(repository.claim(NOW)).thenReturn(Optional.empty());
        assertTrue(transactions.claim().isEmpty());
        verify(repository).claim(NOW);
    }
    @Test void applicationUrlCannotExposeSecretsToAnUntrustedSchemeOrAuthority() {
        for(String url:List.of("", "http://app.example.test", "https://user:password@app.example.test", "https://app.example.test/#fragment"))
            assertThrows(IllegalArgumentException.class,()->new NotificationTransactions(repository,identity,List.of(resolver),clock,admission,acceptance,url));
    }
    private void ready(Delivery d) {
        when(repository.find(d.id(),true)).thenReturn(Optional.of(d));
        when(identity.accountEligible(NotificationTransactions.intent(d),NOW)).thenReturn(true);
        when(resolver.supports(d.eventType())).thenReturn(true);
        when(resolver.eligible(NotificationTransactions.intent(d),NOW)).thenReturn(true);
        when(identity.destination(d.employeeId())).thenReturn(Optional.of(new Destination(d.destinationId(),d.employeeId(),"verified@example.test",0,"VERIFIED")));
        when(repository.attemptId(d.id(),d.generation())).thenReturn(Optional.of(UUID.randomUUID()));
    }
    private static Delivery delivery(String template,String state,long generation,Instant end) {
        return new Delivery(UUID.randomUUID(),UUID.randomUUID(),"INVITATION".equals(template)?"INVITATION_ISSUE":"PAYSLIP_PUBLISHED",UUID.randomUUID(),NOW,
            UUID.randomUUID(),UUID.randomUUID(),template,"INVITATION".equals(template)?"INVITATION":"OWNER",null,UUID.randomUUID(),0,state,1,generation,1,false,end,NOW);
    }
    private static Delivery withLease(Delivery d,String state,long generation,Instant end) {
        return new Delivery(d.id(),d.eventId(),d.eventType(),d.aggregateId(),d.occurredAt(),d.employeeId(),d.accountId(),d.template(),d.eligibilityType(),d.assignmentId(),d.destinationId(),d.destinationVersion(),state,d.version(),generation,d.attempts(),d.manualAttempt(),end,NOW);
    }
}
