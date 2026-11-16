package id.mydev.peoplecore.notification.application;

import id.mydev.peoplecore.identity.application.service.IdentityNotificationAccess;
import id.mydev.peoplecore.notification.domain.repository.NotificationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.Optional;
import java.util.List;
import id.mydev.peoplecore.notification.domain.NotificationModels.Delivery;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SesFeedbackServiceTest {
    private static final String TOPIC="arn:aws:sns:ap-southeast-1:123456789012:feedback";
    private static final Instant NOW=Instant.parse("2026-11-13T10:00:00Z");
    private final NotificationRepository repository=mock(NotificationRepository.class);
    private final IdentityNotificationAccess identity=mock(IdentityNotificationAccess.class);
    private final ObjectMapper mapper=new ObjectMapper();
    private final NotificationAcceptance acceptance=mock(NotificationAcceptance.class);
    private final SesFeedbackService service=new SesFeedbackService(repository,identity,acceptance,mapper,Clock.fixed(NOW,ZoneOffset.UTC),TOPIC);
    @Test void onlyTrustedCorrelatedDeliveryEvidenceIsAccepted() {
        UUID delivery=UUID.randomUUID(); UUID attempt=UUID.randomUUID();
        String body=body(TOPIC,delivery,attempt);
        when(repository.feedback(delivery,attempt,"ses-message","DELIVERED","sns-message",NOW)).thenReturn(true);
        assertTrue(service.process(body));
        verify(repository).feedback(delivery,attempt,"ses-message","DELIVERED","sns-message",NOW);
        assertThrows(IllegalArgumentException.class,()->service.process(body("arn:aws:sns:ap-southeast-1:000000000000:attacker",delivery,attempt)));
    }
    @Test void invalidCorrelationNeverClaimsDelivery() {
        UUID delivery=UUID.randomUUID(); UUID attempt=UUID.randomUUID();
        String body=body(TOPIC,delivery,attempt).replace(delivery.toString(),"invalid");
        assertThrows(IllegalArgumentException.class,()->service.process(body));
    }
    @Test void invalidEnvelopeAndOversizedEvidenceNeverReachDeliveryState() {
        assertThrows(IllegalArgumentException.class,()->service.process(null));
        assertThrows(IllegalArgumentException.class,()->service.process("a".repeat(65537)));
        UUID delivery=UUID.randomUUID(); UUID attempt=UUID.randomUUID(); String valid=body(TOPIC,delivery,attempt);
        assertThrows(IllegalArgumentException.class,()->service.process(valid.replace("Notification","SubscriptionConfirmation")));
        assertThrows(IllegalArgumentException.class,()->service.process(valid.replace("ses-message","")));
        assertThrows(IllegalArgumentException.class,()->service.process(valid.replace("ses-message","x".repeat(256))));
        assertThrows(IllegalArgumentException.class,()->service.process(valid.replace("sns-message","")));
        assertThrows(IllegalArgumentException.class,()->service.process(valid.replace("sns-message","x".repeat(201))));
        assertThrows(IllegalArgumentException.class,()->new SesFeedbackService(repository,identity,acceptance,mapper,Clock.fixed(NOW,ZoneOffset.UTC),"untrusted-topic"));
    }
    @Test void providerAcceptanceIsDifferentFromDeliveryAndKnownPermanentFailure() {
        UUID delivery=UUID.randomUUID(); UUID attempt=UUID.randomUUID(); String valid=body(TOPIC,delivery,attempt);
        when(repository.feedback(delivery,attempt,"ses-message","ACCEPTED","sns-message",NOW)).thenReturn(true);
        assertTrue(service.process(valid.replace("Delivery","Send")));
        verify(repository).feedback(delivery,attempt,"ses-message","ACCEPTED","sns-message",NOW);
        when(repository.feedback(delivery,attempt,"ses-message","FAILED","sns-message",NOW)).thenReturn(true);
        for(String type:List.of("Reject","Rendering Failure")) assertTrue(service.process(valid.replace("Delivery",type)));
        verify(identity,never()).suppress(any());
    }
    @Test void trustedBounceAndComplaintSuppressOnlyTheirCorrelatedFrozenDestination() {
        UUID delivery=UUID.randomUUID(); UUID attempt=UUID.randomUUID(); UUID destination=UUID.randomUUID();
        Delivery row=new Delivery(delivery,UUID.randomUUID(),"PAYSLIP_PUBLISHED",UUID.randomUUID(),NOW,UUID.randomUUID(),UUID.randomUUID(),"PAYSLIP_AVAILABLE","OWNER",null,destination,0,"UNKNOWN",1,1,1,false,null,NOW);
        when(repository.feedback(delivery,attempt,"ses-message","FAILED","sns-message",NOW)).thenReturn(true);
        when(repository.find(delivery,false)).thenReturn(Optional.of(row));
        String valid=body(TOPIC,delivery,attempt);
        assertTrue(service.process(valid.replace("Delivery","Bounce"))); verify(identity).suppress(destination);
        when(repository.find(delivery,false)).thenReturn(Optional.empty());
        assertTrue(service.process(valid.replace("Delivery","Complaint")));
    }
    @Test void staleMismatchedOrUnrecognizedFeedbackDoesNotClaimSuccessOrSuppressUnrelatedDestination() {
        UUID delivery=UUID.randomUUID(); UUID attempt=UUID.randomUUID(); String valid=body(TOPIC,delivery,attempt);
        assertFalse(service.process(valid.replace("Delivery","Open")));
        when(repository.feedback(delivery,attempt,"ses-message","FAILED","sns-message",NOW)).thenReturn(false);
        assertFalse(service.process(valid.replace("Delivery","Bounce")));
        verify(identity,never()).suppress(any());
    }
    private String body(String topic,UUID delivery,UUID attempt) {
        String event="{\"eventType\":\"Delivery\",\"mail\":{\"messageId\":\"ses-message\",\"tags\":{\"delivery_id\":[\""+delivery+"\"],\"attempt_id\":[\""+attempt+"\"]}}}";
        return mapper.writeValueAsString(java.util.Map.of("Type","Notification","TopicArn",topic,"MessageId","sns-message","Message",event));
    }
}
