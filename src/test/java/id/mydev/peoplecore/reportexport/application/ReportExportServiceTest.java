package id.mydev.peoplecore.reportexport.application;

import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.common.command.AdmissionGuard;
import id.mydev.peoplecore.common.outbox.OutboxEvent;
import id.mydev.peoplecore.common.outbox.OutboxEventObserver;
import id.mydev.peoplecore.common.outbox.OutboxEventRepository;
import id.mydev.peoplecore.document.application.service.RevocableDocumentTransfer;
import id.mydev.peoplecore.identity.application.service.AccountAccessService;
import id.mydev.peoplecore.identity.application.service.AuthorizationFenceService;
import id.mydev.peoplecore.identity.application.service.AuthorizationFenceService.AuthorizationWindow;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.reporting.application.ReportRequest;
import id.mydev.peoplecore.reporting.application.ReportSnapshot;
import id.mydev.peoplecore.reporting.application.ReportType;
import id.mydev.peoplecore.reporting.application.ScopedReportService;
import id.mydev.peoplecore.reportexport.domain.repository.ReportExportRepository;
import id.mydev.peoplecore.reportexport.domain.repository.ReportExportRepository.Job;
import id.mydev.peoplecore.reportexport.infrastructure.storage.ExportArtifactStorage;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class ReportExportServiceTest {
    private static final Instant NOW=Instant.parse("2026-11-13T10:00:00Z");
    private static final UUID JOB=UUID.randomUUID();
    private static final UUID ACCOUNT=UUID.randomUUID();
    private static final UUID CANDIDATE=UUID.randomUUID();
    private static final ReportRequest REQUEST=ReportRequest.of(ReportType.ATTENDANCE,LocalDate.of(2026,11,1),LocalDate.of(2026,11,14));
    private final ReportExportRepository repository=mock(ReportExportRepository.class);
    private final AccountAccessService access=mock(AccountAccessService.class);
    private final AuthorizationFenceService fence=mock(AuthorizationFenceService.class);
    private final AdmissionGuard admission=mock(AdmissionGuard.class);
    private final ScopedReportService reports=mock(ScopedReportService.class);
    private final OutboxEventRepository outbox=mock(OutboxEventRepository.class);
    private final OutboxEventObserver observer=mock(OutboxEventObserver.class);
    private final MockEnvironment environment=new MockEnvironment().withProperty("peoplecore.exports.enabled","true");
    private final JsonMapper mapper=JsonMapper.builder().findAndAddModules().build();
    private final Authentication caller=new JwtAuthenticationToken(Jwt.withTokenValue("verified").header("alg","none")
        .issuer("https://identity.example").subject("requester").issuedAt(NOW).expiresAt(NOW.plusSeconds(3600)).build());
    private ReportExportService service;
    private ObjectProvider<ExportArtifactStorage> storage;
    private ObjectProvider<RevocableDocumentTransfer> transfers;
    private ExportArtifactStorage artifactStorage;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setup() {
        storage=mock(ObjectProvider.class);
        transfers=mock(ObjectProvider.class);
        artifactStorage=mock(ExportArtifactStorage.class);
        when(storage.getIfAvailable()).thenReturn(artifactStorage);
        service=new ReportExportService(repository,access,fence,admission,reports,mapper,Clock.fixed(NOW,ZoneOffset.UTC),
            environment,new LocalTransactions(),storage,transfers,outbox);
        service.outboxObservers(List.of(observer));
        when(fence.sharedWindow(NOW)).thenReturn(new AuthorizationWindow(7,null));
        when(repository.find(JOB,true)).thenReturn(Optional.of(job("GENERATING",1,NOW.minusSeconds(10),NOW.plusSeconds(100),null)));
        when(outbox.save(any(OutboxEvent.class))).thenAnswer(invocation->invocation.getArgument(0));
    }

    @Test
    void freshGenerationCheckInvalidatesCandidateRenderedUnderOldScope() {
        Job job=job("GENERATING",1,NOW.minusSeconds(10),NOW.plusSeconds(100),null);
        when(repository.finish(JOB,1,"INVALIDATED","SCOPE_CHANGED",NOW)).thenReturn(true);
        assertFalse(service.finalizeCandidate(job,CANDIDATE,snapshot(NOW.minusSeconds(2),6,null)));
        verify(repository).finish(JOB,1,"INVALIDATED","SCOPE_CHANGED",NOW);
        verify(observer).created(any(OutboxEvent.class));
        verify(repository,never()).ready(JOB,1,CANDIDATE,NOW.minusSeconds(2),6,null,NOW);
    }

    @Test
    void scheduledBoundaryInvalidatesEvenWhenGenerationHasNotChanged() {
        Job job=job("GENERATING",1,NOW.minusSeconds(10),NOW.plusSeconds(100),null);
        assertFalse(service.finalizeCandidate(job,CANDIDATE,snapshot(NOW.minusSeconds(2),7,NOW)));
        verify(repository).finish(JOB,1,"INVALIDATED","SCOPE_CHANGED",NOW);
    }

    @Test
    void staleWorkerCannotPublishOrInvalidateReplacement() {
        Job stale=job("GENERATING",0,NOW.minusSeconds(10),NOW.plusSeconds(100),null);
        assertFalse(service.finalizeCandidate(stale,CANDIDATE,snapshot(NOW.minusSeconds(2),7,null)));
        verify(repository,never()).finish(JOB,1,"INVALIDATED","SCOPE_CHANGED",NOW);
    }

    @Test
    void expiredLeaseCannotPublishEvenIfNoOtherWorkerHasClaimed() {
        when(repository.find(JOB,true)).thenReturn(Optional.of(job("GENERATING",1,NOW.minusSeconds(10),NOW,null)));
        assertFalse(service.finalizeCandidate(job("GENERATING",1,NOW.minusSeconds(10),NOW,null),CANDIDATE,snapshot(NOW,7,null)));
    }

    @Test
    void currentAuthorityAndMatchingGenerationAllowReadyWithinFreshnessBound() {
        Job current=job("GENERATING",1,NOW.minusSeconds(1000),NOW.plusSeconds(100),null);
        when(repository.find(JOB,true)).thenReturn(Optional.of(current));
        Instant asOf=NOW.minusSeconds(900);
        when(repository.ready(JOB,1,CANDIDATE,asOf,7,null,NOW)).thenReturn(true);
        assertTrue(service.finalizeCandidate(current,CANDIDATE,snapshot(asOf,7,null)));
        verify(repository).recordTransition(JOB,"READY",NOW);
        verify(observer).created(any(OutboxEvent.class));
    }

    @Test
    void staleSnapshotFailsInsteadOfBecomingReady() {
        Job current=job("GENERATING",1,NOW.minusSeconds(1000),NOW.plusSeconds(100),null);
        assertFalse(service.finalizeCandidate(current,CANDIDATE,snapshot(NOW.minusSeconds(901),7,null)));
        verify(repository).finish(JOB,1,"FAILED","STALE_SNAPSHOT",NOW);
    }

    @Test
    void authorityRemovedDuringRenderingInvalidatesPublication() {
        Job current=job("GENERATING",1,NOW.minusSeconds(10),NOW.plusSeconds(100),null);
        doThrow(new AccessDeniedException("revoked")).when(reports).authorize(any(Authentication.class),any(ReportRequest.class));
        assertFalse(service.finalizeCandidate(current,CANDIDATE,snapshot(NOW,7,null)));
        verify(repository).finish(JOB,1,"INVALIDATED","SCOPE_CHANGED",NOW);
    }

    @Test
    void expiredJobDoesNotPublishEvenIfSnapshotIsFresh() {
        Job current=job("GENERATING",1,NOW.minusSeconds(86400),NOW.plusSeconds(100),null);
        when(repository.find(JOB,true)).thenReturn(Optional.of(current));
        assertFalse(service.finalizeCandidate(current,CANDIDATE,snapshot(NOW,7,null)));
        verify(repository).finish(JOB,1,"EXPIRED","EXPIRED",NOW);
    }

    @Test
    void recoveryFencesReadyAndCleanupExpiration() {
        environment.setProperty("peoplecore.recovery.isolated","true");
        assertThrows(ResponseStatusException.class,()->service.finalizeCandidate(job("GENERATING",1,NOW,NOW.plusSeconds(100),null),CANDIDATE,snapshot(NOW,7,null)));
        assertEquals(0,service.expireDue(10));
    }

    @Test
    void requesterQuotaRejectsThirdUnfinishedExport() {
        UserAccount account=new UserAccount(ACCOUNT,"https://identity.example","requester",NOW);
        when(access.requireActive(caller,NOW)).thenReturn(account);
        when(repository.unfinished(ACCOUNT)).thenReturn(2L);
        assertThrows(ResponseStatusException.class,()->service.create(JOB,REQUEST,caller));
        verify(repository).lockRequester(ACCOUNT);
    }

    @Test
    void detailDoesNotExposeAnotherRequestersJob() {
        when(access.requireActive(caller,NOW)).thenReturn(new UserAccount(UUID.randomUUID(),"https://identity.example","requester",NOW));
        when(repository.find(JOB,false)).thenReturn(Optional.of(job("READY",1,NOW,NOW.plusSeconds(100),null)));
        assertThrows(ResourceNotFoundException.class,()->service.detail(JOB,caller));
    }

    @Test
    void readyContentStreamsOnlyAfterFreshAuthorizationAndNeverLeaksTheCandidateKey() throws Exception {
        byte[] csv="data!".getBytes();
        when(access.requireActive(caller,NOW)).thenReturn(new UserAccount(ACCOUNT,"https://identity.example","requester",NOW));
        when(repository.find(JOB,false)).thenReturn(Optional.of(job("READY",1,NOW.minusSeconds(10),null,null)));
        when(artifactStorage.open(CANDIDATE)).thenReturn(new ByteArrayInputStream(csv));
        try(var transfer=new RevocableDocumentTransfer(1,Duration.ofMillis(20),Duration.ofMillis(500))) {
            when(transfers.getIfUnique()).thenReturn(transfer);
            ByteArrayOutputStream output=new ByteArrayOutputStream();
            service.download(JOB,caller,output);
            assertArrayEquals(csv,output.toByteArray());
        }
    }

    @Test
    void scopeRevocationClosesAnAlreadyStalledStreamAndCommitsInvalidation() throws Exception {
        AtomicBoolean revoked=new AtomicBoolean();
        AtomicReference<Job> state=new AtomicReference<>(job("READY",1,NOW.minusSeconds(10),null,null));
        CountDownLatch reading=new CountDownLatch(1);
        CountDownLatch closed=new CountDownLatch(1);
        InputStream source=new InputStream() {
            @Override public int read() throws IOException {
                reading.countDown();
                try { closed.await(); }
                catch(InterruptedException exception) { Thread.currentThread().interrupt();throw new IOException(exception); }
                throw new IOException("Stream closed");
            }
            @Override public void close() { closed.countDown(); }
        };
        when(access.requireActive(caller,NOW)).thenReturn(new UserAccount(ACCOUNT,"https://identity.example","requester",NOW));
        when(repository.find(JOB,false)).thenAnswer(invocation->Optional.of(state.get()));
        when(fence.sharedWindow(NOW)).thenAnswer(invocation->new AuthorizationWindow(revoked.get()?8:7,null));
        when(repository.finish(JOB,1,"INVALIDATED","SCOPE_CHANGED",NOW)).thenAnswer(invocation->{
            state.set(job("INVALIDATED",1,NOW.minusSeconds(10),null,null));
            return true;
        });
        when(artifactStorage.open(CANDIDATE)).thenReturn(source);
        try(var transfer=new RevocableDocumentTransfer(1,Duration.ofMillis(20),Duration.ofMillis(500));
            var thread=Executors.newSingleThreadExecutor()) {
            when(transfers.getIfUnique()).thenReturn(transfer);
            ByteArrayOutputStream output=new ByteArrayOutputStream();
            var running=thread.submit(()->assertThrows(IOException.class,()->service.download(JOB,caller,output)));
            assertTrue(reading.await(2,TimeUnit.SECONDS));
            revoked.set(true);
            assertTrue(closed.await(2,TimeUnit.SECONDS));
            running.get(2,TimeUnit.SECONDS);
            assertEquals("INVALIDATED",state.get().state());
            assertEquals(0,output.size());
            verify(repository).recordTransition(JOB,"INVALIDATED",NOW);
        }
    }

    @Test
    void unavailableStorageOrTransferFailsBeforeGrantingContent() {
        when(storage.getIfAvailable()).thenReturn(null);
        assertThrows(ResponseStatusException.class,()->service.requireDownload(JOB,caller));
        when(storage.getIfAvailable()).thenReturn(artifactStorage);
        assertThrows(ResponseStatusException.class,()->service.requireDownload(JOB,caller));
    }

    @Test
    void pendingAndExpiredExportsDoNotGrantAnyDownload() {
        when(access.requireActive(caller,NOW)).thenReturn(new UserAccount(ACCOUNT,"https://identity.example","requester",NOW));
        when(transfers.getIfUnique()).thenReturn(mock(RevocableDocumentTransfer.class));
        when(repository.find(JOB,false)).thenReturn(Optional.of(job("PENDING",0,NOW,null,null)));
        assertThrows(ResourceNotFoundException.class,()->service.requireDownload(JOB,caller));
        when(repository.find(JOB,false)).thenReturn(Optional.of(job("READY",1,NOW.minusSeconds(86400),null,null)));
        assertThrows(ResourceNotFoundException.class,()->service.requireDownload(JOB,caller));
        verify(repository).finish(JOB,1,"EXPIRED","EXPIRED",NOW);
        verify(artifactStorage,never()).open(CANDIDATE);
    }

    @Test
    void expirySweepEmitsExactlyForRowsWhoseStateActuallyChanges() {
        Job expired=job("PENDING",0,NOW.minusSeconds(86400),null,null);
        when(repository.expiredJobs(NOW,10)).thenReturn(List.of(expired));
        assertEquals(0,service.expireDue(10));
        when(repository.finish(JOB,0,"EXPIRED","EXPIRED",NOW)).thenReturn(true);
        assertEquals(1,service.expireDue(10));
        verify(repository).recordTransition(JOB,"EXPIRED",NOW);
    }

    @Test
    void expiryDoesNotResurrectOrRewriteAnAlreadyFailedJob() {
        when(access.requireActive(caller,NOW)).thenReturn(new UserAccount(ACCOUNT,"https://identity.example","requester",NOW));
        when(repository.find(JOB,false)).thenReturn(Optional.of(job("FAILED",1,NOW.minusSeconds(86400),null,null)));
        assertEquals("FAILED",service.detail(JOB,caller).status());
        verify(repository,never()).finish(JOB,1,"EXPIRED","EXPIRED",NOW);
    }

    @Test
    void recipientEligibilityRechecksRequesterAndAuthorityWithoutGrantingAnotherAccount() {
        Job current=job("READY",1,NOW.minusSeconds(10),null,null);
        when(repository.find(JOB,false)).thenReturn(Optional.of(current));
        assertTrue(service.notificationEligible(JOB,ACCOUNT));
        assertFalse(service.notificationEligible(JOB,UUID.randomUUID()));
        doThrow(new AccessDeniedException("removed")).when(reports).authorize(any(Authentication.class),any(ReportRequest.class));
        assertFalse(service.notificationEligible(JOB,ACCOUNT));
        when(repository.find(JOB,false)).thenReturn(Optional.empty());
        assertFalse(service.notificationEligible(JOB,ACCOUNT));
    }

    @ParameterizedTest
    @ValueSource(strings={"EXPORT_READY","EXPORT_FAILED","EXPORT_EXPIRED","EXPORT_INVALIDATED"})
    void everyExportLifecycleTriggerResolvesTheRequesterAndSuppressesAfterAuthorityRemoval(String eventType) {
        Job current=job("READY",1,NOW.minusSeconds(10),null,null);
        UserAccount account=new UserAccount(ACCOUNT,"https://identity.example","requester",NOW);
        UUID employee=UUID.randomUUID();
        when(repository.find(JOB,false)).thenReturn(Optional.of(current));
        when(access.resolveAccount(any(Authentication.class))).thenReturn(Optional.of(account));
        when(access.ownEmployeePublicId(account)).thenReturn(Optional.of(employee));
        var resolver=new ExportNotificationResolver(service);

        assertTrue(resolver.supports(eventType));
        var recipients=resolver.resolve(eventType,JOB,NOW);
        assertEquals(1,recipients.size());
        var recipient=recipients.getFirst();
        assertEquals(employee,recipient.employeeId());
        assertEquals(ACCOUNT,recipient.accountId());
        var intent=new id.mydev.peoplecore.notification.domain.NotificationModels.Intent(UUID.randomUUID(),eventType,JOB,NOW,
            employee,ACCOUNT,recipient.template(),recipient.eligibilityType(),recipient.assignmentId());
        assertTrue(resolver.eligible(intent,NOW));

        doThrow(new AccessDeniedException("scope removed"))
            .when(reports).authorize(any(Authentication.class),any(ReportRequest.class));
        assertFalse(resolver.eligible(intent,NOW));
    }

    @Test
    void completedJobCannotBePublishedAgainByTheSameLease() {
        Job current=job("READY",1,NOW.minusSeconds(10),null,null);
        when(repository.find(JOB,true)).thenReturn(Optional.of(current));
        assertFalse(service.finalizeCandidate(current,CANDIDATE,snapshot(NOW,7,null)));
    }

    @Test
    void validRequestRecordsTheExactRequesterAndTwentyFourHourExpiry() {
        when(access.requireActive(caller,NOW)).thenReturn(new UserAccount(ACCOUNT,"https://identity.example","requester",NOW));
        when(repository.find(JOB,false)).thenReturn(Optional.of(job("PENDING",0,NOW,null,null)));
        var summary=service.create(JOB,REQUEST,caller);
        assertEquals("PENDING",summary.status());
        assertEquals(NOW.plusSeconds(86400),summary.expiresAt());
        verify(repository).insert(JOB,ACCOUNT,"https://identity.example","requester",mapper.writeValueAsString(REQUEST),NOW,NOW.plusSeconds(86400));
    }

    @Test
    void declaredFutureBoundaryCanBePublishedAndAStartedTransferUsesThatEarlierDeadline() throws Exception {
        Instant boundary=NOW.plusSeconds(60);
        Job generating=job("GENERATING",1,NOW.minusSeconds(10),NOW.plusSeconds(100),boundary);
        when(repository.find(JOB,true)).thenReturn(Optional.of(generating));
        when(repository.ready(JOB,1,CANDIDATE,NOW,7,boundary,NOW)).thenReturn(true);
        assertTrue(service.finalizeCandidate(generating,CANDIDATE,snapshot(NOW,7,boundary)));
        when(access.requireActive(caller,NOW)).thenReturn(new UserAccount(ACCOUNT,"https://identity.example","requester",NOW));
        when(repository.find(JOB,false)).thenReturn(Optional.of(job("READY",1,NOW.minusSeconds(10),null,boundary)));
        when(artifactStorage.open(CANDIDATE)).thenReturn(new ByteArrayInputStream(new byte[]{1,2,3,4,5}));
        try(var transfer=new RevocableDocumentTransfer(1,Duration.ofMillis(20),Duration.ofMillis(500))) {
            when(transfers.getIfUnique()).thenReturn(transfer);
            ByteArrayOutputStream output=new ByteArrayOutputStream();
            service.download(JOB,caller,output);
            assertEquals(5,output.size());
        }
    }

    @Test
    void failureAndExpirationPublishAnIntentOnlyWhenTheDurableStateChanges() {
        Job current=job("GENERATING",1,NOW.minusSeconds(10),NOW.plusSeconds(100),null);
        when(repository.finish(JOB,1,"FAILED","GENERATION_FAILED",NOW)).thenReturn(true,false);
        service.failed(current,"GENERATION_FAILED");
        service.failed(current,"GENERATION_FAILED");
        verify(repository,times(1)).recordTransition(JOB,"FAILED",NOW);
    }

    @Test
    void notificationResolutionUsesTheStoredRequesterAndCurrentEmployeeBinding() {
        Job current=job("READY",1,NOW.minusSeconds(10),null,null);
        UserAccount account=new UserAccount(ACCOUNT,"https://identity.example","requester",NOW);
        UUID employee=UUID.randomUUID();
        when(repository.find(JOB,false)).thenReturn(Optional.of(current));
        when(access.resolveAccount(any(Authentication.class))).thenReturn(Optional.of(account));
        when(access.ownEmployeePublicId(account)).thenReturn(Optional.of(employee));
        assertEquals(Optional.of(employee),service.notificationEmployee(JOB));
        assertEquals(Optional.of(ACCOUNT),service.notificationAccount(JOB));
        when(access.resolveAccount(any(Authentication.class))).thenReturn(Optional.empty());
        assertEquals(Optional.empty(),service.notificationEmployee(JOB));
        when(repository.find(JOB,false)).thenReturn(Optional.empty());
        assertEquals(Optional.empty(),service.notificationAccount(JOB));
    }

    @Test
    void disablingTheFeatureDeniesFurtherRecipientAndDownloadAdmission() {
        environment.setProperty("peoplecore.exports.enabled","false");
        assertThrows(ResponseStatusException.class,service::requireAvailable);
        assertFalse(service.notificationEligible(JOB,ACCOUNT));
    }

    private Job job(String state,long leaseGeneration,Instant created,Instant leaseUntil,Instant boundary) {
        return new Job(JOB,ACCOUNT,"https://identity.example","requester",mapper.writeValueAsString(REQUEST),state,
            created,created.plusSeconds(86400),NOW.minusSeconds(2),7,boundary,leaseGeneration,leaseUntil,CANDIDATE,5L,"a".repeat(64),null);
    }
    private static ReportSnapshot snapshot(Instant asOf,long generation,Instant boundary) {
        return new ReportSnapshot(ReportType.ATTENDANCE,1,"filter",List.of("status"),List.of(List.of("READY")),asOf,generation,boundary);
    }

    private static class LocalTransactions extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction,TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}
