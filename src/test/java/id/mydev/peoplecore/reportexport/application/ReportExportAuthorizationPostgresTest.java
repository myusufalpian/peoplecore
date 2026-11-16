package id.mydev.peoplecore.reportexport.application;

import id.mydev.peoplecore.approval.application.admission.ProfileAdmissionGuard;
import id.mydev.peoplecore.approval.infrastructure.persistence.JdbcProfileAdmissionLockRepository;
import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.common.outbox.OutboxEvent;
import id.mydev.peoplecore.common.outbox.OutboxEventRepository;
import id.mydev.peoplecore.common.reporting.ReportFilter;
import id.mydev.peoplecore.document.application.service.RevocableDocumentTransfer;
import id.mydev.peoplecore.identity.application.service.AccountAccessService;
import id.mydev.peoplecore.identity.application.service.AuthorizationFenceService;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.identity.infrastructure.persistence.JdbcAuthorizationFenceRepository;
import id.mydev.peoplecore.identity.infrastructure.persistence.JdbcReportAuthorizationRepository;
import id.mydev.peoplecore.leave.infrastructure.persistence.JdbcLeaveReportQuery;
import id.mydev.peoplecore.reporting.application.ReportRequest;
import id.mydev.peoplecore.reporting.application.ReportSnapshot;
import id.mydev.peoplecore.reporting.application.ReportType;
import id.mydev.peoplecore.reporting.application.ScopedReportService;
import id.mydev.peoplecore.reportexport.domain.repository.ReportExportRepository.Job;
import id.mydev.peoplecore.reportexport.infrastructure.persistence.JdbcReportExportRepository;
import id.mydev.peoplecore.reportexport.infrastructure.storage.ExportArtifactStorage;
import id.mydev.peoplecore.support.JwtAuthFixture;
import id.mydev.peoplecore.support.PostgresFixture;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("postgres")
class ReportExportAuthorizationPostgresTest {
    private static final Instant NOW=Instant.parse("2026-11-13T10:00:00Z");
    private static final ReportRequest REQUEST=ReportRequest.of(ReportType.ATTENDANCE,LocalDate.of(2026,11,1),LocalDate.of(2026,11,14));

    @Test
    void roleRevokedWhileRepeatableReadSnapshotIsOpenCannotPublishThroughStaleSnapshot() throws Exception {
        try(var context=new Context();var threads=Executors.newFixedThreadPool(2)) {
            Claimed work=context.claim();
            CountDownLatch captured=new CountDownLatch(1);
            CountDownLatch mutationCommitted=new CountDownLatch(1);
            var reading=threads.submit(()->{
                TransactionTemplate repeatable=new TransactionTemplate(context.manager);
                repeatable.setReadOnly(true);
                repeatable.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
                return repeatable.execute(status->{
                    var window=context.fences.currentBoundary(NOW);
                    assertEquals(1,context.currentRoles());
                    captured.countDown();
                    await(mutationCommitted);
                    assertEquals(1,context.currentRoles());
                    assertEquals(window.generation(),context.fences.currentBoundary(NOW).generation());
                    return snapshot(window.generation(),window.nextBoundary());
                });
            });
            assertTrue(captured.await(3,TimeUnit.SECONDS));
            var mutation=threads.submit(()->{
                try {
                    context.fixture.jdbc.update("UPDATE role_assignments SET effective_to=? WHERE account_id=?",
                        NOW.atOffset(ZoneOffset.UTC),context.accountId);
                } finally { mutationCommitted.countDown(); }
            });
            mutation.get(3,TimeUnit.SECONDS);
            ReportSnapshot stale=reading.get(3,TimeUnit.SECONDS);
            assertEquals(0,context.currentRoles());
            assertFalse(context.service.finalizeCandidate(work.job(),work.candidate(),stale));
            assertEquals("INVALIDATED",context.repository.find(work.job().id(),false).orElseThrow().state());
            assertThrows(ResourceNotFoundException.class,()->context.service.requireDownload(work.job().id(),context.caller));
        }
    }

    @Test
    void scopeRemovalAfterReadyCommitsInvalidationBeforeReturningDownloadDenial() {
        try(var context=new Context()) {
            Claimed work=context.claim();
            ReportSnapshot data=context.snapshot();
            assertTrue(context.service.finalizeCandidate(work.job(),work.candidate(),data));
            context.service.requireDownload(work.job().id(),context.caller);
            context.fixture.jdbc.update("UPDATE role_assignments SET effective_to=? WHERE account_id=?",NOW.atOffset(ZoneOffset.UTC),context.accountId);
            assertThrows(ResourceNotFoundException.class,()->context.service.requireDownload(work.job().id(),context.caller));
            assertEquals("INVALIDATED",context.repository.find(work.job().id(),false).orElseThrow().state());
            assertEquals(1,context.fixture.jdbc.queryForObject("SELECT count(event_id) FROM audit_events WHERE aggregate_public_id=? AND action='EXPORT_INVALIDATED'",
                Integer.class,work.job().id()));
        }
    }

    @Test
    void scheduledRoleBoundaryInvalidatesReadyExportWithoutAnotherDatabaseWrite() {
        try(var context=new Context()) {
            Instant boundary=NOW.plusSeconds(60);
            context.fixture.jdbc.update("UPDATE role_assignments SET effective_to=? WHERE account_id=?",boundary.atOffset(ZoneOffset.UTC),context.accountId);
            Claimed work=context.claim();
            ReportSnapshot data=context.snapshot();
            assertEquals(boundary,data.nextBoundary());
            assertTrue(context.service.finalizeCandidate(work.job(),work.candidate(),data));
            context.clock.at.set(boundary);
            assertThrows(ResourceNotFoundException.class,()->context.service.requireDownload(work.job().id(),context.caller));
            assertEquals("INVALIDATED",context.repository.find(work.job().id(),false).orElseThrow().state());
        }
    }

    @Test
    void candidateWithoutConfirmedProviderCompletionCannotBePublished() {
        try(var context=new Context()) {
            UUID id=context.insertJob();
            Job job=context.tx.execute(status->context.repository.claim(NOW,NOW.plusSeconds(120)).orElseThrow());
            UUID candidate=UUID.randomUUID();
            context.repository.registerCandidate(id,job.leaseGeneration(),candidate,NOW,5,"a".repeat(64));
            ReportSnapshot data=context.snapshot();
            assertFalse(context.service.finalizeCandidate(job,candidate,data));
            assertEquals("GENERATING",context.repository.find(id,false).orElseThrow().state());
            assertThrows(ResourceNotFoundException.class,()->context.service.requireDownload(id,context.caller));
            context.repository.completeUpload(candidate,NOW,5,"a".repeat(64));
            assertTrue(context.service.finalizeCandidate(job,candidate,data));
            assertEquals("READY",context.repository.find(id,false).orElseThrow().state());
        }
    }

    @Test
    void competingRequestsCannotExceedTwoUnfinishedJobsForOneRequester() throws Exception {
        try(var context=new Context();var threads=Executors.newFixedThreadPool(2)) {
            context.insertJob();
            CountDownLatch start=new CountDownLatch(1);
            var first=threads.submit(()->context.createAfter(start));
            var second=threads.submit(()->context.createAfter(start));
            start.countDown();
            int accepted=first.get(3,TimeUnit.SECONDS)+second.get(3,TimeUnit.SECONDS);
            assertEquals(1,accepted);
            assertEquals(2,context.repository.unfinished(context.account));
        }
    }

    @Test
    void completingAnOutsideTeamReviewInvalidatesOldExportEvenWhileBroadTeamAuthorityRemains() {
        try(var context=new Context()) {
            ReviewScope review=reviewScope(context);
            var authority=new JdbcReportAuthorizationRepository(context.fixture.jdbc.getDataSource());
            assertTrue(authority.containsTarget(context.accountId,"REQUEST_STATUS",review.outsideEmployee(),null,NOW,deadline()));
            assertTrue(authority.containsTarget(context.accountId,"REQUEST_STATUS",review.teamEmployee(),null,NOW,deadline()));
            ReportRequest request=new ReportRequest(ReportType.REQUEST_STATUS,1,LocalDate.of(2026,11,1),LocalDate.of(2026,11,14),
                null,null,null,null,null,"LEAVE",null,null,null,null,null);
            Claimed work=context.claim(request);
            var query=new JdbcLeaveReportQuery(context.fixture.jdbc.getDataSource());
            ReportFilter filter=new ReportFilter("REQUEST_STATUS",request.from(),request.to(),null,null,null,null,"LEAVE",null,null,null,null,
                null,context.accountId,NOW,100,deadline());
            TransactionTemplate snapshot=new TransactionTemplate(context.manager);
            snapshot.setReadOnly(true);
            snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            ReportSnapshot before=snapshot.execute(status->{
                var window=context.fences.currentBoundary(NOW);
                var rows=query.query(filter);
                return new ReportSnapshot(ReportType.REQUEST_STATUS,1,"review-scope",rows.columns(),rows.rows(),NOW,window.generation(),window.nextBoundary());
            });
            assertEquals(2,before.rows().size());
            assertTrue(before.rows().stream().anyMatch(row->row.contains(review.outsideEmployee().toString())));
            assertTrue(context.service.finalizeCandidate(work.job(),work.candidate(),before));
            context.service.requireDownload(work.job().id(),context.caller);

            context.fixture.grantRuntime();
            context.fixture.runtime().update("INSERT INTO leave_decision(request_id,revision,step,reviewer_account_public_id,reviewer_employee_public_id,outcome,created_at) "
                +"VALUES(?,1,1,?,?,'APPROVE',?)",review.requestId(),context.account,review.reviewerEmployee(),NOW.atOffset(ZoneOffset.UTC));

            assertFalse(authority.containsTarget(context.accountId,"REQUEST_STATUS",review.outsideEmployee(),null,NOW,deadline()));
            assertTrue(authority.containsTarget(context.accountId,"REQUEST_STATUS",review.teamEmployee(),null,NOW,deadline()));
            assertTrue(authority.containsTarget(context.accountId,"REQUEST_STATUS",null,null,NOW,deadline()));
            assertEquals(1,query.query(filter).rows().size());
            assertTrue(context.snapshot().authorizationGeneration()>before.authorizationGeneration());
            assertThrows(ResourceNotFoundException.class,()->context.service.requireDownload(work.job().id(),context.caller));
            assertEquals("INVALIDATED",context.repository.find(work.job().id(),false).orElseThrow().state());
        }
    }

    private static ReviewScope reviewScope(Context context) {
        var jdbc=context.fixture.jdbc;
        UUID reviewer=UUID.randomUUID();
        long reviewerId=jdbc.queryForObject("INSERT INTO employees(public_id,employee_number,created_at) VALUES(?,?,?) RETURNING id",Long.class,
            reviewer,"REVIEWER-"+reviewer,NOW.atOffset(ZoneOffset.UTC));
        jdbc.update("INSERT INTO account_bindings(employee_id,employee_public_id,account_id,oidc_issuer,oidc_subject,created_at) VALUES(?,?,?,'https://identity.example','requester',?)",
            reviewerId,reviewer,context.accountId,NOW.atOffset(ZoneOffset.UTC));
        jdbc.update("INSERT INTO role_assignments(account_id,role,effective_from,created_at) VALUES(?,'MANAGER',?,?)",context.accountId,
            NOW.minusSeconds(3600).atOffset(ZoneOffset.UTC),NOW.atOffset(ZoneOffset.UTC));
        UUID team=UUID.randomUUID();
        UUID outside=UUID.randomUUID();
        long teamId=reportEmployee(context,team,"ENG",reviewerId);
        long outsideId=reportEmployee(context,outside,"FIN",null);
        leaveRequest(context,teamId,"DRAFT");
        long request=leaveRequest(context,outsideId,"DRAFT");
        jdbc.update("INSERT INTO leave_revision(request_id,revision,year,policy_revision_id,half_units,org_unit,from_date,to_date,portion,reason,submitted_at) "
            +"VALUES(?,1,2026,1,2,'FIN',?,?,'FULL','Reviewed leave',?)",request,LocalDate.of(2026,11,13),LocalDate.of(2026,11,13),NOW.atOffset(ZoneOffset.UTC));
        jdbc.update("UPDATE leave_request SET status='PENDING',revision=1,version=version+1 WHERE id=?",request);
        jdbc.update("INSERT INTO leave_review_assignment(request_id,revision,step,reviewer_account_public_id,reviewer_employee_public_id,created_at) VALUES(?,1,1,?,?,?)",
            request,context.account,reviewer,NOW.atOffset(ZoneOffset.UTC));
        return new ReviewScope(request,reviewer,team,outside);
    }

    private static long reportEmployee(Context context,UUID id,String unit,Long manager) {
        long employee=context.fixture.jdbc.queryForObject("INSERT INTO employees(public_id,employee_number,created_at) VALUES(?,?,?) RETURNING id",Long.class,
            id,"SCOPE-"+id,NOW.atOffset(ZoneOffset.UTC));
        context.fixture.jdbc.update("INSERT INTO employee_assignments(public_id,employee_id,org_unit,manager_employee_id,job_level,valid_from,created_at) "
            +"VALUES(?,?,?,?,'L1',?,?)",UUID.randomUUID(),employee,unit,manager,NOW.minusSeconds(3600).atOffset(ZoneOffset.UTC),NOW.atOffset(ZoneOffset.UTC));
        return employee;
    }

    private static long leaveRequest(Context context,long employee,String state) {
        return context.fixture.jdbc.queryForObject("INSERT INTO leave_request(public_id,employee_id,status,from_date,to_date,portion,reason,created_at) "
            +"VALUES(?,?,?,?,?,'FULL','Report scope',?) RETURNING id",Long.class,UUID.randomUUID(),employee,state,
            LocalDate.of(2026,11,13),LocalDate.of(2026,11,13),NOW.atOffset(ZoneOffset.UTC));
    }

    private static long deadline() { return System.nanoTime()+TimeUnit.SECONDS.toNanos(30); }
    private record ReviewScope(long requestId,UUID reviewerEmployee,UUID teamEmployee,UUID outsideEmployee) { }

    private static ReportSnapshot snapshot(long generation,Instant boundary) {
        return new ReportSnapshot(ReportType.ATTENDANCE,1,"filter",List.of("status"),List.of(List.of("READY")),NOW,generation,boundary);
    }

    private static void await(CountDownLatch latch) {
        try {
            if(!latch.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("Timed out waiting for concurrent change");
        } catch(InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted",exception);
        }
    }

    private record Claimed(Job job,UUID candidate) { }

    private static class MutableClock extends Clock {
        private final AtomicReference<Instant> at=new AtomicReference<>(NOW);
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return at.get(); }
    }

    private static class Context implements AutoCloseable {
        private final PostgresFixture fixture=new PostgresFixture();
        private final UUID account=UUID.randomUUID();
        private final long accountId;
        private final Authentication caller=JwtAuthFixture.token("https://identity.example","requester");
        private final MutableClock clock=new MutableClock();
        private final JsonMapper mapper=JsonMapper.builder().build();
        private final JdbcReportExportRepository repository;
        private final DataSourceTransactionManager manager;
        private final TransactionTemplate tx;
        private final AuthorizationFenceService fences;
        private final ReportExportService service;

        @SuppressWarnings("unchecked")
        private Context() {
            fixture.flyway().migrate();
            accountId=fixture.jdbc.queryForObject("INSERT INTO user_accounts(public_id,oidc_issuer,oidc_subject,created_at) VALUES(?,?,?,?) RETURNING id",
                Long.class,account,"https://identity.example","requester",NOW.atOffset(ZoneOffset.UTC));
            fixture.jdbc.update("INSERT INTO role_assignments(account_id,role,effective_from,created_at) VALUES(?,'EMPLOYEE',?,?)",
                accountId,NOW.minusSeconds(30).atOffset(ZoneOffset.UTC),NOW.atOffset(ZoneOffset.UTC));
            repository=new JdbcReportExportRepository(fixture.jdbc);
            manager=new DataSourceTransactionManager(fixture.jdbc.getDataSource());
            tx=new TransactionTemplate(manager);
            fences=new AuthorizationFenceService(new JdbcAuthorizationFenceRepository(fixture.jdbc));
            var access=mock(AccountAccessService.class);
            when(access.requireActive(any(Authentication.class),any(Instant.class))).thenReturn(new UserAccount(account,"https://identity.example","requester",NOW));
            ObjectProvider<ExportArtifactStorage> storage=mock(ObjectProvider.class);
            when(storage.getIfAvailable()).thenReturn(mock(ExportArtifactStorage.class));
            ObjectProvider<RevocableDocumentTransfer> transfers=mock(ObjectProvider.class);
            when(transfers.getIfUnique()).thenReturn(mock(RevocableDocumentTransfer.class));
            OutboxEventRepository outbox=mock(OutboxEventRepository.class);
            when(outbox.save(any(OutboxEvent.class))).thenAnswer(invocation->invocation.getArgument(0));
            service=new ReportExportService(repository,access,fences,new ProfileAdmissionGuard(new JdbcProfileAdmissionLockRepository(fixture.jdbc)),
                mock(ScopedReportService.class),mapper,clock,new MockEnvironment().withProperty("peoplecore.exports.enabled","true"),
                manager,storage,transfers,outbox);
        }

        private UUID insertJob() {
            return insertJob(REQUEST);
        }

        private UUID insertJob(ReportRequest request) {
            UUID id=UUID.randomUUID();
            repository.insert(id,account,"https://identity.example","requester",mapper.writeValueAsString(request),NOW,NOW.plusSeconds(86400));
            return id;
        }

        private Claimed claim() {
            return claim(REQUEST);
        }

        private Claimed claim(ReportRequest request) {
            insertJob(request);
            Job job=tx.execute(status->repository.claim(NOW,NOW.plusSeconds(120)).orElseThrow());
            UUID candidate=UUID.randomUUID();
            repository.registerCandidate(job.id(),job.leaseGeneration(),candidate,NOW,5,"a".repeat(64));
            repository.completeUpload(candidate,NOW,5,"a".repeat(64));
            return new Claimed(job,candidate);
        }

        private ReportSnapshot snapshot() {
            return tx.execute(status->{
                var window=fences.currentBoundary(clock.instant());
                return ReportExportAuthorizationPostgresTest.snapshot(window.generation(),window.nextBoundary());
            });
        }

        private int currentRoles() {
            return fixture.jdbc.queryForObject("SELECT count(id) FROM role_assignments WHERE account_id=? AND effective_from<=? AND (effective_to IS NULL OR effective_to>?)",
                Integer.class,accountId,NOW.atOffset(ZoneOffset.UTC),NOW.atOffset(ZoneOffset.UTC));
        }

        private int createAfter(CountDownLatch start) {
            await(start);
            try {
                tx.execute(status->service.create(UUID.randomUUID(),REQUEST,caller));
                return 1;
            } catch(ResponseStatusException exception) {
                assertEquals(409,exception.getStatusCode().value());
                return 0;
            }
        }

        @Override public void close() { fixture.close(); }
    }
}
