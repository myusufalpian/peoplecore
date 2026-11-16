package id.mydev.peoplecore.notification.application;

import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.identity.application.service.IdentityNotificationAccess;
import id.mydev.peoplecore.common.audit.AuditEvent;
import id.mydev.peoplecore.common.audit.AuditEventWriter;
import id.mydev.peoplecore.common.command.AdmissionGuard;
import id.mydev.peoplecore.common.command.CommandBusyException;
import id.mydev.peoplecore.common.command.CommandConflictException;
import id.mydev.peoplecore.common.security.OperationAccessService;
import id.mydev.peoplecore.notification.domain.NotificationModels.Delivery;
import id.mydev.peoplecore.notification.domain.repository.NotificationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationSupportServiceTest {
    private static final Instant NOW=Instant.parse("2026-11-13T10:00:00Z");
    private final NotificationRepository repository=mock(NotificationRepository.class);
    private final OperationAccessService access=mock(OperationAccessService.class);
    private final AuditEventWriter audit=mock(AuditEventWriter.class);
    private final AdmissionGuard admission=mock(AdmissionGuard.class);
    private final NotificationAcceptance acceptance=mock(NotificationAcceptance.class);
    private final MockEnvironment environment=new MockEnvironment();
    private final Authentication caller=mock(Authentication.class);
    private final NotificationSupportService service=new NotificationSupportService(repository,access,audit,admission,Clock.fixed(NOW,ZoneOffset.UTC),environment,mock(IdentityNotificationAccess.class),List.of(),acceptance);

    @Test void explicitAcknowledgedRetryIsAuditedAndIdempotent() {
        Delivery row=ready("UNKNOWN");
        service.retry(caller,row.id(),row.version(),"retry-one","Reconciled with recipient",true);
        verify(repository).retry(row,NOW);
        var hash=ArgumentCaptor.forClass(String.class);
        verify(repository).operation(eq("operator"),eq("retry-one"),hash.capture(),eq(row.id()),eq("RETRY"),eq("Reconciled with recipient"),eq(true),eq(NOW));
        var event=ArgumentCaptor.forClass(AuditEvent.class); verify(audit).append(event.capture());
        assertEquals("NOTIFICATION_RETRY",event.getValue().getAction());
        when(repository.operationHash("operator","retry-one")).thenReturn(Optional.of(hash.getValue()));
        assertEquals(row.id(),service.retry(caller,row.id(),row.version(),"retry-one","Reconciled with recipient",true).id());
        verify(repository,times(1)).retry(row,NOW);
        assertThrows(CommandConflictException.class,()->service.retry(caller,row.id(),row.version(),"retry-one","Different reason",true));
    }
    @Test void unknownRequiresDuplicateAcknowledgementAndActiveWorkCannotBeRetried() {
        Delivery row=ready("UNKNOWN");
        assertThrows(IllegalArgumentException.class,()->service.retry(caller,row.id(),row.version(),"key","Reviewed",false));
        assertThrows(CommandConflictException.class,()->service.retry(caller,row.id(),99,"key","Reviewed",true));
        Delivery sending=withState(row,"SENDING"); when(repository.find(row.id(),true)).thenReturn(Optional.of(sending));
        assertThrows(CommandConflictException.class,()->service.retry(caller,row.id(),sending.version(),"key","Reviewed",true));
        verify(repository,never()).retry(any(),any());
    }
    @Test void failedDeliveryCanReceiveOneExplicitSupportAttemptWithoutUnknownAcknowledgement() {
        Delivery row=ready("FAILED");
        assertEquals("FAILED",service.retry(caller,row.id(),row.version(),"key","Provider problem repaired",false).state());
        verify(repository).retry(row,NOW);
    }
    @Test void sharedSupportBudgetRejectsWithoutCreatingAnAttempt() {
        Delivery row=ready("FAILED");
        when(repository.reserve("support:operator",NOW,10)).thenReturn(false);
        assertThrows(CommandBusyException.class,()->service.retry(caller,row.id(),row.version(),"key","Reviewed",false));
        verify(repository,never()).retry(any(),any());
    }
    @Test void recoveryAndMissingReasonDenyRetries() {
        Delivery row=ready("FAILED");
        environment.setProperty("peoplecore.recovery.isolated","true");
        assertThrows(CommandBusyException.class,()->service.retry(caller,row.id(),row.version(),"key","Reviewed",false));
        environment.setProperty("peoplecore.recovery.isolated","false");
        assertThrows(IllegalArgumentException.class,()->service.retry(caller,row.id(),row.version(),"key"," ",false));
        verify(repository,never()).retry(any(),any());
    }
    @Test void reconciliationMustReferencePersistedProviderEvidence() {
        Delivery row=ready("UNKNOWN"); UUID evidence=UUID.randomUUID();
        when(repository.evidenceReference(evidence,row.id())).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class,()->service.reconcile(caller,row.id(),row.version(),"reconcile","Provider inspected",evidence));
        UUID attempt=UUID.randomUUID();
        when(repository.evidenceReference(evidence,row.id())).thenReturn(Optional.of(new NotificationRepository.Evidence(attempt,"known-provider-id","DELIVERED")));
        service.reconcile(caller,row.id(),row.version(),"reconcile","Provider inspected",evidence);
        verify(repository).feedback(row.id(),attempt,"known-provider-id","DELIVERED","reconcile:"+evidence,NOW);
        verify(repository).operation(eq("operator"),eq("reconcile"),anyString(),eq(row.id()),eq("RECONCILE"),eq("Provider inspected"),eq(false),eq(NOW));
    }
    @Test void lossOfOperationsAuthorityDeniesEvenExistingReceipts() {
        Delivery row=ready("UNKNOWN");
        when(access.requireOperator(caller,NOW)).thenThrow(new AccessDeniedException("revoked"));
        assertThrows(AccessDeniedException.class,()->service.retry(caller,row.id(),row.version(),"old-receipt","Reviewed",true));
        assertThrows(AccessDeniedException.class,()->service.detail(caller,row.id()));
        verify(repository,never()).retry(any(),any());
    }
    @Test void readsAreBoundedAndExposeOnlySafeEvidenceAndMetadata() {
        Delivery row=ready("FAILED");
        when(repository.list(20,20)).thenReturn(List.of());
        assertEquals(List.of(),service.list(caller,1,20));
        for(int[] page:new int[][]{{-1,20},{10001,20},{0,0},{0,101}})
            assertThrows(IllegalArgumentException.class,()->service.list(caller,page[0],page[1]));
        assertEquals(row.id(),service.detail(caller,row.id()).id());
        when(repository.evidence(row.id())).thenReturn(List.of());
        assertEquals(List.of(),service.evidence(caller,row.id()));
        when(repository.attentionCount()).thenReturn(2);
        assertEquals(2,service.attention(caller));
        assertThrows(ResourceNotFoundException.class,()->service.detail(caller,UUID.randomUUID()));
    }
    private Delivery ready(String state) {
        when(caller.getName()).thenReturn("operator");
        Delivery row=new Delivery(UUID.randomUUID(),UUID.randomUUID(),"PAYSLIP_PUBLISHED",UUID.randomUUID(),NOW,UUID.randomUUID(),UUID.randomUUID(),"PAYSLIP_AVAILABLE","OWNER",null,UUID.randomUUID(),0,state,3,1,1,false,null,NOW);
        when(repository.find(row.id(),true)).thenReturn(Optional.of(row));
        when(acceptance.lock(row.id())).thenReturn(row);
        when(repository.find(row.id(),false)).thenReturn(Optional.of(row));
        when(repository.reserve("support:operator",NOW,10)).thenReturn(true);
        return row;
    }
    private Delivery withState(Delivery d,String state) {
        return new Delivery(d.id(),d.eventId(),d.eventType(),d.aggregateId(),d.occurredAt(),d.employeeId(),d.accountId(),d.template(),d.eligibilityType(),d.assignmentId(),d.destinationId(),d.destinationVersion(),state,d.version(),d.generation(),d.attempts(),d.manualAttempt(),NOW.plusSeconds(60),NOW);
    }
}
