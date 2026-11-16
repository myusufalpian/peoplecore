package id.mydev.peoplecore.notification.infrastructure.persistence;

import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.common.audit.AuditEvent;
import id.mydev.peoplecore.common.audit.AuditEventWriter;
import id.mydev.peoplecore.common.command.AdmissionGuard;
import id.mydev.peoplecore.common.command.CommandConflictException;
import id.mydev.peoplecore.common.command.CommandIds;
import id.mydev.peoplecore.common.security.OperationAccessService;
import id.mydev.peoplecore.identity.application.service.IdentityNotificationAccess;
import id.mydev.peoplecore.identity.infrastructure.persistence.JdbcNotificationIdentityRepository;
import id.mydev.peoplecore.notification.application.NotificationSupportService;
import id.mydev.peoplecore.notification.application.NotificationAcceptance;
import id.mydev.peoplecore.notification.application.NotificationTransactions;
import id.mydev.peoplecore.notification.domain.NotificationModels.Delivery;
import id.mydev.peoplecore.notification.domain.NotificationModels.Destination;
import id.mydev.peoplecore.notification.domain.NotificationModels.Intent;
import id.mydev.peoplecore.notification.domain.NotificationResolver;
import id.mydev.peoplecore.support.PostgresFixture;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("postgres")
class NotificationReplacementPostgresTest {
    private static final Instant NOW=Instant.parse("2026-11-13T10:00:00Z");
    private record Setup(JdbcNotificationRepository repository,JdbcNotificationIdentityRepository identities,
                         TransactionTemplate transactions,Delivery original,UUID employee) { }
    private record Support(NotificationSupportService service,IdentityNotificationAccess identity,
                           NotificationResolver resolver,Authentication caller,AuditEventWriter audit,MockEnvironment environment) { }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void verifiedDestinationRepairCreatesOneCausallyLinkedDeliveryWithoutChangingOldEvidenceOrDomainWork(boolean missingOriginalDestination) {
        try(var fixture=new PostgresFixture()) {
            Setup setup=setup(fixture,missingOriginalDestination,"PROFILE_SUBMIT","REVIEW_REQUIRED");
            Delivery original=setup.original(); Support support=support(setup);
            Destination destination=setup.identities().verifyDestination(setup.employee(),"repaired@example.test","independently-verified","operator",NOW);
            when(support.identity().destination(setup.employee())).thenReturn(Optional.of(destination));
            var replacement=setup.transactions().execute(status->support.service().replacement(support.caller(),original.id(),original.version(),"repair-one","Verified destination repaired",false));
            UUID expected=CommandIds.derive("NOTIFICATION_DELIVERY_REPLACE","operator","repair-one");
            assertEquals(expected,replacement.delivery().id()); assertEquals(original.id(),replacement.originalDeliveryId());
            Delivery repaired=setup.repository().find(expected,false).orElseThrow();
            assertEquals("PENDING",repaired.state()); assertTrue(repaired.manualAttempt());
            assertNotEquals(original.eventId(),repaired.eventId());
            assertEquals(original.eventType(),repaired.eventType()); assertEquals(original.aggregateId(),repaired.aggregateId());
            assertEquals(original.occurredAt(),repaired.occurredAt()); assertEquals(original.employeeId(),repaired.employeeId());
            assertEquals(original.accountId(),repaired.accountId()); assertEquals(original.assignmentId(),repaired.assignmentId());
            assertEquals(original.template(),repaired.template()); assertEquals(destination.id(),repaired.destinationId());
            assertEquals(destination.version(),repaired.destinationVersion());
            assertEquals(original,setup.repository().find(original.id(),false).orElseThrow());
            assertEquals(original.id(),fixture.jdbc.queryForObject("SELECT original_delivery_id FROM notification_delivery WHERE public_id=?",UUID.class,expected));
            var audit=ArgumentCaptor.forClass(AuditEvent.class); verify(support.audit()).append(audit.capture());
            assertEquals("NOTIFICATION_REPLACE",audit.getValue().getAction()); assertEquals(original.id(),audit.getValue().getAggregatePublicId());
            assertEquals("Verified destination repaired",audit.getValue().getReason());
            var replay=setup.transactions().execute(status->support.service().replacement(support.caller(),original.id(),original.version(),"repair-one","Verified destination repaired",false));
            assertEquals(expected,replay.delivery().id());
            assertEquals(2,fixture.jdbc.queryForObject("SELECT count(public_id) FROM notification_delivery",Integer.class));
            assertEquals(1,fixture.jdbc.queryForObject("SELECT count(actor) FROM notification_operation",Integer.class));
            assertEquals(0,fixture.jdbc.queryForObject("SELECT count(event_id) FROM outbox_events",Integer.class));
            assertThrows(CommandConflictException.class,()->setup.transactions().execute(status->support.service().replacement(support.caller(),original.id(),original.version(),"repair-one","Changed repair reason",false)));
        }
    }

    @Test void staleReviewerOrAccountCannotBeRetargetedAndInvitationRequiresIdentityReissue() {
        try(var fixture=new PostgresFixture()) {
            Setup setup=setup(fixture,true,"PROFILE_SUBMIT","REVIEW_REQUIRED"); Support support=support(setup); Delivery original=setup.original();
            when(support.identity().accountEligible(NotificationTransactions.intent(original),NOW)).thenReturn(false);
            assertThrows(ResourceNotFoundException.class,()->setup.transactions().execute(status->support.service().replacement(support.caller(),original.id(),original.version(),"ineligible-account","Reviewed repair",false)));
            when(support.identity().accountEligible(NotificationTransactions.intent(original),NOW)).thenReturn(true);
            when(support.resolver().eligible(NotificationTransactions.intent(original),NOW)).thenReturn(false);
            assertThrows(ResourceNotFoundException.class,()->setup.transactions().execute(status->support.service().replacement(support.caller(),original.id(),original.version(),"obsolete-assignment","Reviewed repair",false)));
            assertEquals(1,fixture.jdbc.queryForObject("SELECT count(public_id) FROM notification_delivery",Integer.class));
        }
        try(var fixture=new PostgresFixture()) {
            Setup setup=setup(fixture,true,"INVITATION_ISSUE","INVITATION"); Support support=support(setup); Delivery original=setup.original();
            var rejected=assertThrows(IllegalArgumentException.class,()->setup.transactions().execute(status->support.service().replacement(support.caller(),original.id(),original.version(),"invitation-repair","Reviewed repair",true)));
            assertTrue(rejected.getMessage().contains("identity reissue"));
            assertEquals(1,fixture.jdbc.queryForObject("SELECT count(public_id) FROM notification_delivery",Integer.class));
            assertEquals(0,fixture.jdbc.queryForObject("SELECT count(actor) FROM notification_operation",Integer.class));
        }
    }

    @Test void unchangedOrUnverifiedDestinationCannotGenerateANewIdentity() {
        try(var fixture=new PostgresFixture()) {
            Setup setup=setup(fixture,false,"PROFILE_SUBMIT","REVIEW_REQUIRED"); Support support=support(setup); Delivery original=setup.original();
            when(support.identity().destination(setup.employee())).thenReturn(setup.identities().currentDestination(setup.employee()));
            assertThrows(CommandConflictException.class,()->setup.transactions().execute(status->support.service().replacement(support.caller(),original.id(),original.version(),"unchanged","Reviewed repair",false)));
            when(support.identity().destination(setup.employee())).thenReturn(Optional.empty());
            assertThrows(IllegalArgumentException.class,()->setup.transactions().execute(status->support.service().replacement(support.caller(),original.id(),original.version(),"unverified","Reviewed repair",false)));
            assertEquals(1,fixture.jdbc.queryForObject("SELECT count(public_id) FROM notification_delivery",Integer.class));
        }
    }

    private static Setup setup(PostgresFixture fixture,boolean missing,String type,String template) {
        fixture.flyway().migrate(); fixture.grantRuntime(); JdbcTemplate jdbc=fixture.runtime();
        UUID employee=UUID.randomUUID();
        jdbc.update("INSERT INTO employees(public_id,employee_number,created_at) VALUES (?,?,?)",employee,"REPLACE-"+employee,Timestamp.from(NOW));
        var repository=new JdbcNotificationRepository(jdbc); var identities=new JdbcNotificationIdentityRepository(jdbc);
        var tx=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        Optional<Destination> destination=missing?Optional.empty():Optional.of(identities.verifyDestination(employee,"original@example.test","original-verification","operator",NOW));
        UUID event=UUID.randomUUID(); Intent intent=new Intent(event,type,UUID.randomUUID(),NOW,employee,UUID.randomUUID(),template,"INVITATION".equals(template)?"INVITATION":"ORGANIZATION_REVIEW",UUID.randomUUID());
        tx.executeWithoutResult(status->{repository.storeIntent(intent,destination,NOW);repository.materialize(event,NOW);});
        UUID id=jdbc.queryForObject("SELECT public_id FROM notification_delivery WHERE event_id=?",UUID.class,event);
        if(!missing) tx.executeWithoutResult(status->{Delivery claimed=repository.claim(NOW).orElseThrow();repository.outcome(claimed,"SUPPRESSED",null,"DESTINATION_CHANGED",NOW,NOW);});
        return new Setup(repository,identities,tx,repository.find(id,false).orElseThrow(),employee);
    }
    private static Support support(Setup setup) {
        var identity=mock(IdentityNotificationAccess.class); var resolver=mock(NotificationResolver.class);
        var caller=mock(Authentication.class); when(caller.getName()).thenReturn("operator");
        var audit=mock(AuditEventWriter.class); var environment=new MockEnvironment();
        when(identity.accountEligible(NotificationTransactions.intent(setup.original()),NOW)).thenReturn(true);
        when(resolver.supports(setup.original().eventType())).thenReturn(true);
        when(resolver.eligible(NotificationTransactions.intent(setup.original()),NOW)).thenReturn(true);
        var service=new NotificationSupportService(setup.repository(),mock(OperationAccessService.class),audit,mock(AdmissionGuard.class),Clock.fixed(NOW,ZoneOffset.UTC),environment,identity,List.of(resolver),mock(NotificationAcceptance.class));
        return new Support(service,identity,resolver,caller,audit,environment);
    }
}
