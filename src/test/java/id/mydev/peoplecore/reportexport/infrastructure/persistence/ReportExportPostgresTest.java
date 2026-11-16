package id.mydev.peoplecore.reportexport.infrastructure.persistence;

import id.mydev.peoplecore.reportexport.domain.repository.ReportExportRepository.Job;
import id.mydev.peoplecore.support.PostgresFixture;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("postgres")
class ReportExportPostgresTest {
    private static final Instant NOW=Instant.parse("2026-11-13T10:00:00Z");

    @Test
    void restartedWorkerCannotPublishFromAnExpiredLeaseAndCandidatesRemainImmutable() {
        try(var fixture=new PostgresFixture()) {
            fixture.flyway().migrate();
            var jdbc=fixture.jdbc;
            var exports=new JdbcReportExportRepository(jdbc);
            var tx=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
            UUID account=account(jdbc);
            UUID job=UUID.randomUUID();
            exports.insert(job,account,"https://identity.example","subject","{}",NOW,NOW.plusSeconds(86400));
            Job first=tx.execute(status->exports.claim(NOW,NOW.plusSeconds(10)).orElseThrow());
            UUID oldCandidate=UUID.randomUUID();
            exports.registerCandidate(job,first.leaseGeneration(),oldCandidate,NOW,5,"a".repeat(64));
            exports.completeUpload(oldCandidate,NOW,5,"a".repeat(64));
            Job replacement=tx.execute(status->exports.claim(NOW.plusSeconds(10),NOW.plusSeconds(130)).orElseThrow());
            assertEquals(first.leaseGeneration()+1,replacement.leaseGeneration());
            assertFalse(exports.ready(job,first.leaseGeneration(),oldCandidate,NOW,0,null,NOW.plusSeconds(11)));
            UUID currentCandidate=UUID.randomUUID();
            exports.registerCandidate(job,replacement.leaseGeneration(),currentCandidate,NOW,5,"b".repeat(64));
            exports.completeUpload(currentCandidate,NOW,5,"b".repeat(64));
            assertTrue(exports.ready(job,replacement.leaseGeneration(),currentCandidate,NOW,0,null,NOW.plusSeconds(11)));
            assertEquals(currentCandidate,exports.find(job,false).orElseThrow().candidateId());
            assertThrows(DataAccessException.class,()->jdbc.update("UPDATE report_export_job SET candidate_id=? WHERE public_id=?",oldCandidate,job));
            assertThrows(DataAccessException.class,()->jdbc.update("UPDATE report_export_candidate SET sha256=? WHERE public_id=?","c".repeat(64),currentCandidate));
            assertThrows(DataAccessException.class,()->jdbc.update("UPDATE report_export_job SET request_json='changed' WHERE public_id=?",job));
            assertTrue(exports.finish(job,replacement.leaseGeneration(),"INVALIDATED","SCOPE_CHANGED",NOW));
            assertThrows(DataAccessException.class,()->jdbc.update("UPDATE report_export_job SET status='PENDING' WHERE public_id=?",job));
        }
    }

    @Test
    void cleanupNeedsExpiryAndConfirmedUploadRatherThanAnExpiredWorkerLease() {
        try(var fixture=new PostgresFixture()) {
            fixture.flyway().migrate();
            JdbcTemplate jdbc=fixture.jdbc;
            var exports=new JdbcReportExportRepository(jdbc);
            UUID account=account(jdbc);
            UUID job=UUID.randomUUID();
            exports.insert(job,account,"https://identity.example","subject","{}",NOW,NOW.plusSeconds(86400));
            UUID candidate=UUID.randomUUID();
            exports.registerCandidate(job,1,candidate,NOW,5,"a".repeat(64));
            assertTrue(exports.cleanupCandidates(NOW.plusSeconds(86400),10).isEmpty());
            assertFalse(exports.cleanupComplete(candidate,NOW.plusSeconds(86400)));
            exports.completeUpload(candidate,NOW,5,"a".repeat(64));
            assertTrue(exports.cleanupCandidates(NOW.plusSeconds(86399),10).isEmpty());
            assertEquals(candidate,exports.cleanupCandidates(NOW.plusSeconds(86400),10).getFirst().id());
            assertTrue(exports.cleanupComplete(candidate,NOW.plusSeconds(86400)));
            assertTrue(exports.cleanupCandidates(NOW.plusSeconds(86400),10).isEmpty());
            assertFalse(exports.cleanupComplete(candidate,NOW.plusSeconds(86401)));
        }
    }

    @Test
    void expirySweepReleasesUnfinishedQuotaAndEmitsAppendOnlyTransitionEvidence() {
        try(var fixture=new PostgresFixture()) {
            fixture.flyway().migrate();
            JdbcTemplate jdbc=fixture.jdbc;
            var exports=new JdbcReportExportRepository(jdbc);
            UUID account=account(jdbc);
            UUID job=UUID.randomUUID();
            exports.insert(job,account,"https://identity.example","subject","{}",NOW,NOW.plusSeconds(86400));
            assertEquals(1,exports.unfinished(account));
            var tx=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
            tx.executeWithoutResult(status -> {
                Job expired=exports.expiredJobs(NOW.plusSeconds(86400),10).getFirst();
                assertTrue(exports.finish(expired.id(),expired.leaseGeneration(),"EXPIRED","EXPIRED",NOW.plusSeconds(86400)));
                exports.recordTransition(job,"EXPIRED",NOW.plusSeconds(86400));
            });
            assertEquals(0,exports.unfinished(account));
            assertNotNull(jdbc.queryForObject("SELECT event_id FROM audit_events WHERE aggregate_public_id=?",UUID.class,job));
        }
    }

    private static UUID account(JdbcTemplate jdbc) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO user_accounts(public_id,oidc_issuer,oidc_subject,created_at) VALUES(?,?,?,?)",id,
            "https://identity.example",id.toString(),NOW.atOffset(ZoneOffset.UTC));
        return id;
    }
}
