package id.mydev.peoplecore.reportexport.application;

import id.mydev.peoplecore.reporting.application.ReportSnapshot;
import id.mydev.peoplecore.reporting.application.ScopedReportService;
import id.mydev.peoplecore.reportexport.domain.repository.ReportExportRepository;
import id.mydev.peoplecore.reportexport.domain.repository.ReportExportRepository.Job;
import id.mydev.peoplecore.reportexport.infrastructure.storage.ExportArtifactStorage;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReportExportWorker {
    private static final Logger LOG=LoggerFactory.getLogger(ReportExportWorker.class);
    private static final Duration GENERATION_BUDGET=Duration.ofSeconds(120);
    private final ReportExportRepository repository;
    private final ReportExportService exports;
    private final ScopedReportService reports;
    private final ObjectProvider<ExportArtifactStorage> storage;
    private final Clock clock;
    private final TransactionTemplate fresh;
    private final TransactionTemplate snapshot;
    private final ReportCsvWriter csv=new ReportCsvWriter();

    public ReportExportWorker(ReportExportRepository repository,ReportExportService exports,ScopedReportService reports,
            ObjectProvider<ExportArtifactStorage> storage,Clock clock,PlatformTransactionManager manager) {
        this.repository=repository; this.exports=exports; this.reports=reports; this.storage=storage; this.clock=clock;
        fresh=new TransactionTemplate(manager);
        fresh.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        fresh.setTimeout(2);
        snapshot=new TransactionTemplate(manager);
        snapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setReadOnly(true);
        snapshot.setTimeout(30);
    }

    public boolean processNext() {
        if(!available()) return false;
        exports.expireDue(25);
        ExportArtifactStorage configured=storage.getIfAvailable();
        if(configured==null) return false;
        Optional<Job> claimed=fresh.execute(status -> repository.claim(clock.instant(),clock.instant().plus(GENERATION_BUDGET)));
        if(claimed==null || claimed.isEmpty()) return false;
        Job job=claimed.get();
        long started=System.nanoTime();
        try {
            exports.requireAvailable();
            ReportSnapshot data=snapshot.execute(status -> reports.snapshot(exports.requester(job,clock.instant()),exports.request(job),50_000));
            if(data==null) throw new IllegalStateException("Report snapshot unavailable");
            byte[] bytes=csv.render(data.columns(),data.rows());
            requireBudget(started);
            UUID candidate=UUID.randomUUID();
            String digest=digest(bytes);
            fresh.executeWithoutResult(status -> repository.registerCandidate(job.id(),job.leaseGeneration(),candidate,clock.instant(),bytes.length,digest));
            configured.upload(candidate,bytes);
            fresh.executeWithoutResult(status -> repository.completeUpload(candidate,clock.instant(),bytes.length,digest));
            requireBudget(started);
            exports.finalizeCandidate(job,candidate,data);
        } catch(RuntimeException exception) {
            LOG.warn("Export generation failed job={} errorType={}",job.id(),exception.getClass().getSimpleName());
            exports.failed(job,"GENERATION_FAILED");
        }
        return true;
    }

    public int cleanup(int limit) {
        if(limit<1 || limit>100) throw new IllegalArgumentException("Cleanup limit must be 1 to 100");
        if(!available()) return 0;
        ExportArtifactStorage configured=storage.getIfAvailable();
        if(configured==null) return 0;
        reconcileUploads(configured,limit);
        int removed=0;
        for(var candidate:repository.cleanupCandidates(clock.instant(),limit)) {
            if(exports.recovery()) break;
            try {
                configured.delete(candidate.id());
                Boolean completed=fresh.execute(status -> repository.cleanupComplete(candidate.id(),clock.instant()));
                if(Boolean.TRUE.equals(completed)) removed++;
            } catch(RuntimeException exception) {
                LOG.warn("Export cleanup pending candidate={} errorType={}",candidate.id(),exception.getClass().getSimpleName());
            }
        }
        return removed;
    }

    private static void requireBudget(long started) {
        if(System.nanoTime()-started>=GENERATION_BUDGET.toNanos()) throw new IllegalStateException("Export generation timed out");
    }

    private void reconcileUploads(ExportArtifactStorage configured,int limit) {
        for(var candidate:repository.pendingCandidates(clock.instant(),limit)) {
            if(exports.recovery()) return;
            try {
                fresh.executeWithoutResult(status->repository.deferReconciliation(candidate.id(),clock.instant().plusSeconds(30)));
                if(configured.confirmed(candidate.id(),candidate.byteSize(),candidate.digest())) {
                    fresh.executeWithoutResult(status->repository.completeUpload(candidate.id(),clock.instant(),candidate.byteSize(),candidate.digest()));
                }
            } catch(RuntimeException exception) {
                LOG.warn("Export upload outcome unresolved candidate={} errorType={}",candidate.id(),exception.getClass().getSimpleName());
            }
        }
    }

    private boolean available() {
        try { exports.requireAvailable(); return true; }
        catch(ResponseStatusException exception) { return false; }
    }

    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch(NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 unavailable",exception); }
    }
}
