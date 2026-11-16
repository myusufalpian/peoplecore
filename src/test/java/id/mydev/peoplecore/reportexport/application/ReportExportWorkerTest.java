package id.mydev.peoplecore.reportexport.application;

import id.mydev.peoplecore.reporting.application.ReportRequest;
import id.mydev.peoplecore.reporting.application.ReportSnapshot;
import id.mydev.peoplecore.reporting.application.ReportType;
import id.mydev.peoplecore.reporting.application.ScopedReportService;
import id.mydev.peoplecore.reportexport.domain.repository.ReportExportRepository;
import id.mydev.peoplecore.reportexport.domain.repository.ReportExportRepository.Job;
import id.mydev.peoplecore.reportexport.domain.repository.ReportExportRepository.Candidate;
import id.mydev.peoplecore.reportexport.domain.repository.ReportExportRepository.PendingCandidate;
import id.mydev.peoplecore.reportexport.infrastructure.storage.ExportArtifactStorage;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReportExportWorkerTest {
    private static final Instant NOW=Instant.parse("2026-11-13T10:00:00Z");
    private static final UUID ID=UUID.randomUUID();
    private final ReportExportRepository repository=mock(ReportExportRepository.class);
    private final ReportExportService exports=mock(ReportExportService.class);
    private final ScopedReportService reports=mock(ScopedReportService.class);
    private final ExportArtifactStorage storage=mock(ExportArtifactStorage.class);
    private ReportExportWorker worker;
    private Job job;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setup() {
        ObjectProvider<ExportArtifactStorage> provider=mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(storage);
        worker=new ReportExportWorker(repository,exports,reports,provider,Clock.fixed(NOW,ZoneOffset.UTC),new LocalTransactions());
        job=new Job(ID,UUID.randomUUID(),"https://identity.example","subject","{}","GENERATING",NOW,NOW.plusSeconds(86400),null,
            0,null,1,NOW.plusSeconds(120),null,null,null,null);
        when(repository.claim(NOW,NOW.plusSeconds(120))).thenReturn(Optional.of(job));
    }

    @Test
    void closesSnapshotBeforeRenderingUploadAndFreshPublication() {
        Authentication caller=mock(Authentication.class);
        ReportRequest request=ReportRequest.of(ReportType.ATTENDANCE,LocalDate.of(2026,11,1),LocalDate.of(2026,11,14));
        ReportSnapshot snapshot=new ReportSnapshot(ReportType.ATTENDANCE,1,"hash",List.of("status"),List.of(List.of("READY")),NOW,0,null);
        when(exports.request(job)).thenReturn(request);
        when(exports.requester(job,NOW)).thenReturn(caller);
        when(reports.snapshot(caller,request,50_000)).thenAnswer(invocation->{
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            assertEquals(TransactionDefinition.ISOLATION_REPEATABLE_READ,TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
            return snapshot;
        });
        doAnswer(invocation->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(storage).upload(any(UUID.class),any(byte[].class));
        when(exports.finalizeCandidate(eq(job),any(UUID.class),eq(snapshot))).thenAnswer(invocation->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return true;
        });
        assertTrue(worker.processNext());
        verify(storage).upload(any(UUID.class),any(byte[].class));
        verify(exports).finalizeCandidate(eq(job),any(UUID.class),eq(snapshot));
        verify(exports,never()).failed(job,"GENERATION_FAILED");
    }

    @Test
    void storageFailureRetainsDurableCandidateWithoutClaimingUploadCompletion() {
        ReportRequest request=ReportRequest.of(ReportType.ATTENDANCE,LocalDate.of(2026,11,1),LocalDate.of(2026,11,14));
        Authentication caller=mock(Authentication.class);
        when(exports.request(job)).thenReturn(request);
        when(exports.requester(job,NOW)).thenReturn(caller);
        when(reports.snapshot(caller,request,50_000)).thenReturn(new ReportSnapshot(ReportType.ATTENDANCE,1,"hash",
            List.of("status"),List.of(List.of("READY")),NOW,0,null));
        doThrow(new IllegalStateException("unavailable")).when(storage).upload(any(UUID.class),any(byte[].class));
        assertTrue(worker.processNext());
        verify(repository).registerCandidate(eq(ID),eq(1L),any(UUID.class),eq(NOW),anyLong(),any(String.class));
        verify(repository,never()).completeUpload(any(UUID.class),any(Instant.class),anyLong(),any(String.class));
        verify(exports).failed(job,"GENERATION_FAILED");
    }

    @Test
    void disabledOrIsolatedWorkerDoesNotClaimOrDeleteWork() {
        doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)).when(exports).requireAvailable();
        assertFalse(worker.processNext());
        assertEquals(0,worker.cleanup(10));
        verifyNoInteractions(repository,storage);
    }

    @Test
    void noDueJobDoesNotStartProviderIo() {
        when(repository.claim(NOW,NOW.plusSeconds(120))).thenReturn(Optional.empty());
        assertFalse(worker.processNext());
        verifyNoInteractions(storage);
    }

    @Test
    void cleanupRecordsCompletionOnlyAfterDeleteAndRetriesFailure() {
        Candidate candidate=new Candidate(UUID.randomUUID(),ID);
        when(repository.cleanupCandidates(NOW,10)).thenReturn(List.of(candidate));
        doThrow(new IllegalStateException("unavailable")).doNothing().when(storage).delete(candidate.id());
        assertEquals(0,worker.cleanup(10));
        verify(repository,never()).cleanupComplete(candidate.id(),NOW);
        when(repository.cleanupComplete(candidate.id(),NOW)).thenReturn(true);
        assertEquals(1,worker.cleanup(10));
        assertThrows(IllegalArgumentException.class,()->worker.cleanup(0));
        assertThrows(IllegalArgumentException.class,()->worker.cleanup(101));
    }

    @Test
    void recoveryAcknowledgesOnlyPositiveImmutableObjectEvidenceBeforeCleanup() {
        UUID known=UUID.randomUUID();
        UUID unknown=UUID.randomUUID();
        String digest="a".repeat(64);
        when(repository.pendingCandidates(NOW,10)).thenReturn(List.of(new PendingCandidate(known,5,digest),new PendingCandidate(unknown,5,digest)));
        when(storage.confirmed(known,5,digest)).thenReturn(true);
        when(storage.confirmed(unknown,5,digest)).thenReturn(false);
        when(repository.cleanupCandidates(NOW,10)).thenReturn(List.of());
        assertEquals(0,worker.cleanup(10));
        verify(repository).completeUpload(known,NOW,5,digest);
        verify(repository,never()).completeUpload(unknown,NOW,5,digest);
    }

    private static class LocalTransactions extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction,TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}
