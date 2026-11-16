package id.mydev.peoplecore.notification.application;

import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.common.audit.AuditEvent;
import id.mydev.peoplecore.common.audit.AuditEventWriter;
import id.mydev.peoplecore.common.command.AdmissionGuard;
import id.mydev.peoplecore.common.command.CommandBusyException;
import id.mydev.peoplecore.common.command.CommandConflictException;
import id.mydev.peoplecore.common.command.CommandIds;
import id.mydev.peoplecore.identity.application.service.IdentityNotificationAccess;
import id.mydev.peoplecore.notification.domain.NotificationResolver;
import id.mydev.peoplecore.notification.domain.InvitationReissueRequiredException;
import id.mydev.peoplecore.common.command.IdempotencyKeys;
import id.mydev.peoplecore.common.security.OperationAccessService;
import id.mydev.peoplecore.notification.domain.NotificationModels.Delivery;
import id.mydev.peoplecore.notification.domain.NotificationModels.Summary;
import id.mydev.peoplecore.notification.domain.repository.NotificationRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@ConditionalOnProperty(name="peoplecore.notifications.enabled",havingValue="true")
public class NotificationSupportService {
    private final NotificationRepository repository;
    private final OperationAccessService access;
    private final AuditEventWriter audit;
    private final AdmissionGuard admission;
    private final Clock clock;
    private final Environment environment;
    private final IdentityNotificationAccess identity;
    private final List<NotificationResolver> resolvers;
    private final NotificationAcceptance acceptance;
    public NotificationSupportService(NotificationRepository repository,OperationAccessService access,AuditEventWriter audit,
        AdmissionGuard admission,Clock clock,Environment environment,IdentityNotificationAccess identity,List<NotificationResolver> resolvers,NotificationAcceptance acceptance) {
        this.repository=repository; this.access=access; this.audit=audit; this.admission=admission; this.clock=clock; this.environment=environment; this.identity=identity; this.resolvers=List.copyOf(resolvers); this.acceptance=acceptance;
    }
    @Transactional(readOnly=true)
    public List<Summary> list(Authentication caller,int page,int size) {
        access.requireOperator(caller,clock.instant());
        if(page<0||page>10000||size<1||size>100) throw new IllegalArgumentException("Invalid delivery page");
        return repository.list(Math.multiplyExact(page,size),size);
    }
    @Transactional(readOnly=true)
    public Summary detail(Authentication caller,UUID id) {
        access.requireOperator(caller,clock.instant()); return summary(repository.find(id,false).orElseThrow(ResourceNotFoundException::new));
    }
    @Transactional(readOnly=true)
    public List<NotificationRepository.EvidenceSummary> evidence(Authentication caller,UUID id) {
        access.requireOperator(caller,clock.instant());
        repository.find(id,false).orElseThrow(ResourceNotFoundException::new);
        return repository.evidence(id);
    }
    @Transactional(readOnly=true)
    public int attention(Authentication caller) {
        access.requireOperator(caller,clock.instant()); return repository.attentionCount();
    }
    @Transactional
    public Summary retry(Authentication caller,UUID id,long expectedVersion,String key,String reason,boolean acknowledge) {
        admission.acquire(AdmissionGuard.Mode.SHARED);
        access.requireOperator(caller,clock.instant());
        checkRecovery(); validate(key,reason);
        Delivery current=repository.find(id,true).orElseThrow(ResourceNotFoundException::new);
        access.requireOperator(caller,clock.instant());
        String hash=hash("RETRY|"+id+"|"+expectedVersion+"|"+reason+"|"+acknowledge);
        if(replayed(caller,key,hash)) return summary(current);
        if(current.version()!=expectedVersion) throw new CommandConflictException("Delivery version changed");
        if(!Set.of("UNKNOWN","FAILED").contains(current.state())) throw new CommandConflictException("Delivery is not eligible for support retry");
        if("UNKNOWN".equals(current.state())&&!acknowledge) throw new IllegalArgumentException("Possible duplicate acknowledgement is required");
        rate(caller); repository.retry(current,clock.instant());
        operation(caller,key,hash,id,"RETRY",reason,acknowledge);
        return summary(repository.find(id,false).orElseThrow());
    }
    @Transactional
    public Summary reconcile(Authentication caller,UUID id,long expectedVersion,String key,String reason,UUID evidenceId) {
        admission.acquire(AdmissionGuard.Mode.SHARED);
        access.requireOperator(caller,clock.instant()); checkRecovery(); validate(key,reason);
        Delivery current=acceptance.lock(id);
        access.requireOperator(caller,clock.instant());
        String hash=hash("RECONCILE|"+id+"|"+expectedVersion+"|"+reason+"|"+evidenceId);
        if(replayed(caller,key,hash)) return summary(current);
        if(current.version()!=expectedVersion) throw new CommandConflictException("Delivery version changed");
        var evidence=repository.evidenceReference(evidenceId,id).orElseThrow(ResourceNotFoundException::new);
        rate(caller);
        if(repository.feedback(id,evidence.attemptId(),evidence.providerId(),evidence.outcome(),"reconcile:"+evidenceId,clock.instant()))
            acceptance.accepted(current,evidence.attemptId());
        operation(caller,key,hash,id,"RECONCILE",reason,false);
        return summary(repository.find(id,false).orElseThrow());
    }
    @Transactional
    public Replacement replacement(Authentication caller,UUID id,long expectedVersion,String key,String reason,boolean acknowledge) {
        admission.acquire(AdmissionGuard.Mode.SHARED);
        access.requireOperator(caller,clock.instant()); checkRecovery(); validate(key,reason);
        Delivery original=repository.find(id,true).orElseThrow(ResourceNotFoundException::new);
        access.requireOperator(caller,clock.instant());
        String hash=hash("REPLACE|"+id+"|"+expectedVersion+"|"+reason+"|"+acknowledge);
        UUID replacementId=CommandIds.derive("NOTIFICATION_DELIVERY_REPLACE",caller.getName(),key);
        if(replayed(caller,key,hash))
            return new Replacement(id,summary(repository.find(replacementId,false).orElseThrow(ResourceNotFoundException::new)));
        if(original.version()!=expectedVersion) throw new CommandConflictException("Delivery version changed");
        if("INVITATION".equals(original.template())||"INVITATION".equals(original.eligibilityType()))
            throw new InvitationReissueRequiredException();
        if(!Set.of("FAILED","SUPPRESSED","UNKNOWN").contains(original.state()))
            throw new CommandConflictException("Only failed, suppressed or uncertain delivery can be replaced");
        if("UNKNOWN".equals(original.state())&&!acknowledge)
            throw new IllegalArgumentException("Possible duplicate acknowledgement is required");
        var intent=NotificationTransactions.intent(original);
        if(!identity.accountEligible(intent,clock.instant()) || resolvers.stream().filter(resolver->resolver.supports(original.eventType()))
                .noneMatch(resolver->resolver.eligible(intent,clock.instant())))
            throw new ResourceNotFoundException();
        var destination=identity.destination(original.employeeId())
            .orElseThrow(()->new IllegalArgumentException("A current verified destination is required"));
        if(destination.id().equals(original.destinationId())&&destination.version()==original.destinationVersion())
            throw new CommandConflictException("Destination is unchanged; use delivery retry");
        rate(caller);
        UUID eventId=CommandIds.derive("NOTIFICATION_REPLACEMENT_EVENT",caller.getName(),key);
        repository.replacement(replacementId,eventId,original,destination,clock.instant());
        operation(caller,key,hash,id,"REPLACE",reason,acknowledge);
        return new Replacement(id,summary(repository.find(replacementId,false).orElseThrow()));
    }
    public record Replacement(UUID originalDeliveryId,Summary delivery) { }
    private void rate(Authentication caller) {
        if(!repository.reserve("support:"+caller.getName(),clock.instant().truncatedTo(ChronoUnit.MINUTES),10))
            throw new CommandBusyException("Support action budget exhausted");
    }
    private boolean replayed(Authentication caller,String key,String hash) {
        var existing=repository.operationHash(caller.getName(),key);
        if(existing.isPresent()&&!hash.equals(existing.get())) throw new CommandConflictException("Idempotency key has different content");
        return existing.isPresent();
    }
    private void operation(Authentication caller,String key,String hash,UUID id,String action,String reason,boolean acknowledged) {
        repository.operation(caller.getName(),key,hash,id,action,reason.trim(),acknowledged,clock.instant());
        audit.append(new AuditEvent(UUID.randomUUID(),caller.getName(),"NOTIFICATION_"+action,"NOTIFICATION_DELIVERY",null,id,null,
            reason.trim(),"{\"acknowledgedDuplicate\":"+acknowledged+"}",clock.instant()));
    }
    private void checkRecovery() {
        if(environment.getProperty("peoplecore.recovery.isolated",Boolean.class,false)) throw new CommandBusyException("Recovery isolation is active");
    }
    private static void validate(String key,String reason) {
        IdempotencyKeys.requireValid(key);
        if(!StringUtils.hasText(reason)||reason.length()>1000) throw new IllegalArgumentException("A support reason is required");
    }
    private static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 unavailable",exception); }
    }
    private static Summary summary(Delivery d) {
        return new Summary(d.id(),d.eventId(),d.eventType(),d.aggregateId(),d.template(),d.state(),d.version(),d.attempts(),d.updatedAt());
    }
}
