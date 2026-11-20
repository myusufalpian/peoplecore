package id.mydev.peoplecore.reportexport.application;

import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.common.command.AdmissionGuard;
import id.mydev.peoplecore.common.outbox.OutboxEvent;
import id.mydev.peoplecore.common.outbox.OutboxEventObserver;
import id.mydev.peoplecore.common.outbox.OutboxEventRepository;
import id.mydev.peoplecore.document.application.service.RevocableDocumentTransfer;
import id.mydev.peoplecore.identity.application.service.AccountAccessService;
import id.mydev.peoplecore.identity.application.service.AuthorizationFenceService;
import id.mydev.peoplecore.infrastructure.recovery.ServiceRecoveryGate;
import id.mydev.peoplecore.reporting.application.ReportRequest;
import id.mydev.peoplecore.reporting.application.ReportSnapshot;
import id.mydev.peoplecore.reporting.application.ScopedReportService;
import id.mydev.peoplecore.reportexport.domain.repository.ReportExportRepository;
import id.mydev.peoplecore.reportexport.domain.repository.ReportExportRepository.Job;
import id.mydev.peoplecore.reportexport.infrastructure.storage.ExportArtifactStorage;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
public class ReportExportService {
    public static final Duration RETENTION = Duration.ofHours(24);
    public static final Duration FRESHNESS = Duration.ofMinutes(15);
    private final ReportExportRepository repository;
    private final AccountAccessService access;
    private final AuthorizationFenceService fence;
    private final AdmissionGuard admission;
    private final ScopedReportService reports;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Environment environment;
    private final TransactionTemplate fresh;
    private final ObjectProvider<ExportArtifactStorage> storage;
    private final ObjectProvider<RevocableDocumentTransfer> transfers;
    private final OutboxEventRepository outbox;
    private Supplier<List<OutboxEventObserver>> observers = List::of;
    private ServiceRecoveryGate recoveryGate;

    public ReportExportService(ReportExportRepository repository, AccountAccessService access,
            AuthorizationFenceService fence, AdmissionGuard admission, ScopedReportService reports,
            ObjectMapper mapper, Clock clock, Environment environment, PlatformTransactionManager manager,
            ObjectProvider<ExportArtifactStorage> storage, @Qualifier("exportRevocableTransfer") ObjectProvider<RevocableDocumentTransfer> transfers,
            OutboxEventRepository outbox) {
        this.repository=repository; this.access=access; this.fence=fence; this.admission=admission;
        this.reports=reports; this.mapper=mapper; this.clock=clock; this.environment=environment;
        this.storage=storage; this.transfers=transfers; this.outbox=outbox;
        fresh = new TransactionTemplate(manager);
        fresh.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        fresh.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        fresh.setTimeout(2);
    }

    @Autowired(required = false)
    public void configureRecoveryGate(ObjectProvider<ServiceRecoveryGate> recoveryGate) {
        this.recoveryGate = recoveryGate == null ? null : recoveryGate.getIfAvailable();
    }

    @Autowired(required = false)
    public void configureObservers(ObjectProvider<OutboxEventObserver> observers) {
        this.observers=() -> observers.orderedStream().toList();
    }

    public void outboxObservers(List<OutboxEventObserver> observers) {
        List<OutboxEventObserver> configured=List.copyOf(observers);
        this.observers=() -> configured;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Summary create(UUID id, ReportRequest request, Authentication caller) {
        requireAvailable();
        storage();
        reports.authorize(caller, request);
        var account=access.requireActive(caller,clock.instant());
        repository.lockRequester(account.getPublicId());
        reports.authorize(caller,request);
        if(repository.unfinished(account.getPublicId())>=2) throw new ResponseStatusException(HttpStatus.CONFLICT,"Export quota reached");
        Instant now=clock.instant();
        repository.insert(id,account.getPublicId(),account.getOidcIssuer(),account.getOidcSubject(),
            mapper.writeValueAsString(request),now,now.plus(RETENTION));
        return summary(repository.find(id,false).orElseThrow(ResourceNotFoundException::new));
    }

    @Transactional
    public Summary detail(UUID id, Authentication caller) {
        admission.acquire(AdmissionGuard.Mode.SHARED);
        Job job=owned(id,caller);
        reports.authorize(caller,request(job));
        if(!clock.instant().isBefore(job.expiresAt()) && mutable(job)) {
            transition(job,"EXPIRED","EXPIRED");
            job=repository.find(id,false).orElseThrow(ResourceNotFoundException::new);
        }
        return summary(job);
    }

    public void download(UUID id, Authentication caller, OutputStream output) throws IOException {
        ExportArtifactStorage configured=storage();
        RevocableDocumentTransfer transfer=transfer();
        Job granted=fresh.execute(status -> authorizeDownload(id,caller));
        if(granted==null) throw new ResourceNotFoundException();
        try(var content=configured.open(granted.candidateId())) {
            Instant deadline=granted.nextBoundary()==null || granted.expiresAt().isBefore(granted.nextBoundary())
                ?granted.expiresAt():granted.nextBoundary();
            transfer.transfer(content,output,Objects.requireNonNull(granted.byteSize()),() -> {
                try {
                    return fresh.execute(status -> authorizeDownload(id,caller))!=null;
                } catch(AccessDeniedException | ResourceNotFoundException | ResponseStatusException exception) {
                    return false;
                }
            },deadline,clock);
        }
    }

    public void requireDownload(UUID id, Authentication caller) {
        storage();
        transfer();
        if(fresh.execute(status -> authorizeDownload(id,caller))==null) throw new ResourceNotFoundException();
    }

    private Job authorizeDownload(UUID id, Authentication caller) {
        requireAvailable();
        admission.acquire(AdmissionGuard.Mode.SHARED);
        var window=fence.sharedWindow(clock.instant());
        Job job=owned(id,caller);
        if(!"READY".equals(job.state())) return null;
        try { reports.authorize(caller,request(job)); }
        catch(AccessDeniedException | ResourceNotFoundException exception) {
            transition(job,"INVALIDATED","SCOPE_CHANGED");
            return null;
        }
        if(!valid(job,window.generation(),clock.instant())) {
            boolean expired=!clock.instant().isBefore(job.expiresAt());
            transition(job,expired?"EXPIRED":"INVALIDATED",expired?"EXPIRED":"SCOPE_CHANGED");
            return null;
        }
        return job;
    }

    public boolean finalizeCandidate(Job claimed, UUID candidate, ReportSnapshot snapshot) {
        return Boolean.TRUE.equals(fresh.execute(status -> {
            requireAvailable();
            admission.acquire(AdmissionGuard.Mode.SHARED);
            Instant now=clock.instant();
            var window=fence.sharedWindow(now);
            Job current=repository.find(claimed.id(),true).orElseThrow(ResourceNotFoundException::new);
            if(current.leaseGeneration()!=claimed.leaseGeneration() || !"GENERATING".equals(current.state())) return false;
            if(current.leaseUntil()==null || !now.isBefore(current.leaseUntil())) return false;
            if(!now.isBefore(current.expiresAt())) return transition(current,"EXPIRED","EXPIRED");
            if(now.isAfter(snapshot.dataAsOf().plus(FRESHNESS))) return transition(current,"FAILED","STALE_SNAPSHOT");
            try { reports.authorize(requester(current,now),request(current)); }
            catch(AccessDeniedException | ResourceNotFoundException exception) { return transition(current,"INVALIDATED","SCOPE_CHANGED"); }
            if(snapshot.authorizationGeneration()!=window.generation() || passed(snapshot.nextBoundary(),now))
                return transition(current,"INVALIDATED","SCOPE_CHANGED");
            boolean ready=repository.ready(current.id(),current.leaseGeneration(),candidate,snapshot.dataAsOf(),
                snapshot.authorizationGeneration(),snapshot.nextBoundary(),now);
            if(ready) emit(current.id(),"READY",now);
            return ready;
        }));
    }

    public void failed(Job job,String reason) {
        fresh.executeWithoutResult(status -> {
            if(repository.finish(job.id(),job.leaseGeneration(),"FAILED",reason,clock.instant())) emit(job.id(),"FAILED",clock.instant());
        });
    }

    public int expireDue(int limit) {
        if(recovery()) return 0;
        Integer expired=fresh.execute(status -> {
            admission.acquire(AdmissionGuard.Mode.SHARED);
            int count=0;
            for(Job job:repository.expiredJobs(clock.instant(),limit)) {
                if(repository.finish(job.id(),job.leaseGeneration(),"EXPIRED","EXPIRED",clock.instant())) {
                    emit(job.id(),"EXPIRED",clock.instant());
                    count++;
                }
            }
            return count;
        });
        return expired==null?0:expired;
    }

    public Optional<UUID> notificationEmployee(UUID id) {
        Job job=repository.find(id,false).orElseThrow(ResourceNotFoundException::new);
        var account=access.resolveAccount(requester(job,clock.instant()));
        return account.flatMap(access::ownEmployeePublicId);
    }

    public Optional<UUID> notificationAccount(UUID id) {
        return repository.find(id,false).map(Job::requester);
    }

    public boolean notificationEligible(UUID id,UUID accountId) {
        try {
            requireAvailable();
            Job job=repository.find(id,false).orElseThrow(ResourceNotFoundException::new);
            if(!job.requester().equals(accountId)) return false;
            reports.authorize(requester(job,clock.instant()),request(job));
            return true;
        } catch(AccessDeniedException | ResourceNotFoundException | ResponseStatusException exception) { return false; }
    }

    public ReportRequest request(Job job) { return mapper.readValue(job.requestJson(),ReportRequest.class); }

    public Authentication requester(Job job,Instant now) {
        Jwt jwt=Jwt.withTokenValue("internal-export-requester").header("alg","none")
            .issuer(job.issuer()).subject(job.subject()).issuedAt(now).expiresAt(now.plusSeconds(3600)).build();
        return new JwtAuthenticationToken(jwt);
    }

    public boolean recovery() {
        return environment.getProperty("peoplecore.recovery.isolated",Boolean.class,false)
            || (recoveryGate!=null && recoveryGate.isIsolated());
    }

    public void requireAvailable() {
        if(recovery() || !environment.getProperty("peoplecore.exports.enabled",Boolean.class,false))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Export service unavailable");
    }

    private Job owned(UUID id,Authentication caller) {
        UUID account=access.requireActive(caller,clock.instant()).getPublicId();
        Job job=repository.find(id,false).orElseThrow(ResourceNotFoundException::new);
        if(!account.equals(job.requester())) throw new ResourceNotFoundException();
        return job;
    }

    private static boolean valid(Job job,long generation,Instant now) {
        return job.authorizationGeneration()==generation && now.isBefore(job.expiresAt()) && !passed(job.nextBoundary(),now);
    }

    private static boolean passed(Instant boundary,Instant now) { return boundary!=null && !now.isBefore(boundary); }
    private static boolean mutable(Job job) { return List.of("PENDING","GENERATING","READY").contains(job.state()); }

    private boolean transition(Job job,String state,String reason) {
        boolean changed=repository.finish(job.id(),job.leaseGeneration(),state,reason,clock.instant());
        if(changed) emit(job.id(),state,clock.instant());
        return false;
    }

    private void emit(UUID id,String state,Instant now) {
        repository.recordTransition(id,state,now);
        OutboxEvent event=outbox.save(new OutboxEvent(UUID.randomUUID(),"EXPORT_"+state,1,"REPORT_EXPORT",null,id,"{}",now));
        observers.get().forEach(observer -> observer.created(event));
    }

    private ExportArtifactStorage storage() {
        ExportArtifactStorage value=storage.getIfAvailable();
        if(value==null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Export storage unavailable");
        return value;
    }

    private RevocableDocumentTransfer transfer() {
        RevocableDocumentTransfer value=transfers.getIfUnique();
        if(value==null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Export transfer unavailable");
        return value;
    }

    private static Summary summary(Job job) {
        return new Summary(job.id(),job.state(),job.createdAt(),job.expiresAt(),job.dataAsOf(),job.reason());
    }

    public record Summary(UUID publicId,String status,Instant createdAt,Instant expiresAt,Instant dataAsOf,String reason) { }
}
