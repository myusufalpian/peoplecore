package id.mydev.peoplecore.reportexport.infrastructure.persistence;

import id.mydev.peoplecore.reportexport.domain.repository.ReportExportRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcReportExportRepository implements ReportExportRepository {
    private static final String COLUMNS = "public_id,requester_public_id,requester_issuer,requester_subject,request_json,status,"
        + "created_at,expires_at,data_as_of,authorization_generation,next_boundary,lease_generation,lease_until,candidate_id,byte_size,sha256,reason_code";
    private final JdbcTemplate jdbc;

    public JdbcReportExportRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void lockRequester(UUID accountId) {
        jdbc.queryForObject("SELECT id FROM user_accounts WHERE public_id=? FOR UPDATE", Long.class, accountId);
    }

    @Override
    public long unfinished(UUID accountId) {
        Long count = jdbc.queryForObject("SELECT count(public_id) FROM report_export_job WHERE requester_public_id=? "
            + "AND status IN ('PENDING','GENERATING')", Long.class, accountId);
        return count == null ? 0 : count;
    }

    @Override
    public void insert(UUID id, UUID requester, String issuer, String subject, String request, Instant created, Instant expiry) {
        jdbc.update("INSERT INTO report_export_job(public_id,requester_public_id,requester_issuer,requester_subject,request_json,created_at,expires_at)"
            + " VALUES(?,?,?,?,?,?,?)", id, requester, issuer, subject, request, timestamp(created), timestamp(expiry));
    }

    @Override
    public Optional<Job> find(UUID id, boolean lock) {
        return jdbc.query("SELECT " + COLUMNS + " FROM report_export_job WHERE public_id=?" + (lock ? " FOR UPDATE" : ""),
            this::job, id).stream().findFirst();
    }

    @Override
    public Optional<Job> claim(Instant now, Instant leaseUntil) {
        var due = jdbc.query("SELECT " + COLUMNS + " FROM report_export_job WHERE expires_at>? AND "
            + "(status='PENDING' OR (status='GENERATING' AND lease_until<=?)) ORDER BY created_at,public_id "
            + "LIMIT 1 FOR UPDATE SKIP LOCKED", this::job, timestamp(now), timestamp(now));
        if (due.isEmpty()) return Optional.empty();
        Job selected = due.getFirst();
        jdbc.update("UPDATE report_export_job SET status='GENERATING',lease_generation=lease_generation+1,lease_until=? WHERE public_id=?",
            timestamp(leaseUntil), selected.id());
        return find(selected.id(), false);
    }

    @Override
    public List<Job> expiredJobs(Instant now,int limit) {
        return jdbc.query("SELECT "+COLUMNS+" FROM report_export_job WHERE expires_at<=? AND status IN ('PENDING','GENERATING','READY') "
            +"ORDER BY expires_at,public_id LIMIT ? FOR UPDATE SKIP LOCKED",this::job,timestamp(now),limit);
    }

    @Override
    public void registerCandidate(UUID jobId, long generation, UUID candidateId, Instant now,long size,String digest) {
        jdbc.update("INSERT INTO report_export_candidate(public_id,job_public_id,lease_generation,registered_at,byte_size,sha256,next_reconcile_at) VALUES(?,?,?,?,?,?,?)",
            candidateId, jobId, generation, timestamp(now),size,digest,timestamp(now));
    }

    @Override
    public void completeUpload(UUID id, Instant now, long size, String digest) {
        jdbc.update("UPDATE report_export_candidate SET uploaded_at=? WHERE public_id=? AND uploaded_at IS NULL AND byte_size=? AND sha256=?",
            timestamp(now), id, size, digest);
    }

    @Override
    public boolean ready(UUID id, long generation, UUID candidate, Instant asOf, long authGeneration, Instant boundary, Instant now) {
        return jdbc.update("UPDATE report_export_job SET status='READY',data_as_of=?,authorization_generation=?,next_boundary=?,"
            + "candidate_id=?,byte_size=c.byte_size,sha256=c.sha256,lease_until=NULL FROM report_export_candidate c "
            + "WHERE report_export_job.public_id=? AND report_export_job.status='GENERATING' AND report_export_job.lease_generation=? "
            + "AND report_export_job.lease_until>? AND report_export_job.expires_at>? AND c.public_id=? "
            + "AND c.job_public_id=report_export_job.public_id AND c.lease_generation=? AND c.uploaded_at IS NOT NULL AND c.deleted_at IS NULL",
            timestamp(asOf), authGeneration, timestamp(boundary), candidate, id, generation, timestamp(now), timestamp(now), candidate, generation) == 1;
    }

    @Override
    public boolean finish(UUID id, long generation, String state, String reason, Instant now) {
        return jdbc.update("UPDATE report_export_job SET status=?,reason_code=?,lease_until=NULL WHERE public_id=? "
            + "AND lease_generation=? AND status IN ('PENDING','GENERATING','READY')", state, reason, id, generation) == 1;
    }

    @Override
    public List<Candidate> cleanupCandidates(Instant now, int limit) {
        return jdbc.query("SELECT c.public_id,c.job_public_id FROM report_export_candidate c JOIN report_export_job j ON j.public_id=c.job_public_id "
            + "WHERE j.expires_at<=? AND c.uploaded_at IS NOT NULL AND c.deleted_at IS NULL "
            + "ORDER BY c.registered_at,c.public_id LIMIT ?", (rs,row) -> new Candidate(rs.getObject(1, UUID.class),rs.getObject(2, UUID.class)),
            timestamp(now), limit);
    }

    @Override
    public List<PendingCandidate> pendingCandidates(Instant now,int limit) {
        return jdbc.query("SELECT public_id,byte_size,sha256 FROM report_export_candidate WHERE uploaded_at IS NULL "
            +"AND deleted_at IS NULL AND next_reconcile_at<=? ORDER BY next_reconcile_at,registered_at,public_id LIMIT ?",
            (rs,row)->new PendingCandidate(rs.getObject(1,UUID.class),rs.getLong(2),rs.getString(3)),timestamp(now),limit);
    }

    @Override
    public void deferReconciliation(UUID candidateId,Instant nextCheck) {
        jdbc.update("UPDATE report_export_candidate SET next_reconcile_at=? WHERE public_id=? AND uploaded_at IS NULL",
            timestamp(nextCheck),candidateId);
    }

    @Override
    public boolean cleanupComplete(UUID candidateId, Instant now) {
        return jdbc.update("UPDATE report_export_candidate c SET deleted_at=? FROM report_export_job j WHERE c.public_id=? "
            + "AND j.public_id=c.job_public_id AND j.expires_at<=? AND c.uploaded_at IS NOT NULL AND c.deleted_at IS NULL",
            timestamp(now), candidateId, timestamp(now)) == 1;
    }

    @Override
    public void recordTransition(UUID id, String state, Instant now) {
        jdbc.update("INSERT INTO audit_events(event_id,actor_id,action,aggregate_type,aggregate_public_id,details,created_at) "
            + "VALUES(?,'system:report-export',?,'REPORT_EXPORT',?,?,?)", UUID.randomUUID(), "EXPORT_" + state, id,
            "{\"status\":\"" + state + "\"}", timestamp(now));
    }

    private Job job(ResultSet rs, int row) throws SQLException {
        Long generation = rs.getObject("authorization_generation", Long.class);
        return new Job(rs.getObject("public_id", UUID.class), rs.getObject("requester_public_id", UUID.class),
            rs.getString("requester_issuer"),rs.getString("requester_subject"),rs.getString("request_json"),rs.getString("status"),
            instant(rs,"created_at"),instant(rs,"expires_at"),instant(rs,"data_as_of"),generation==null?0:generation,
            instant(rs,"next_boundary"),rs.getLong("lease_generation"),instant(rs,"lease_until"),rs.getObject("candidate_id",UUID.class),
            rs.getObject("byte_size",Long.class),rs.getString("sha256"),rs.getString("reason_code"));
    }

    private static Instant instant(ResultSet rs,String name) throws SQLException {
        OffsetDateTime value=rs.getObject(name,OffsetDateTime.class);
        return value==null?null:value.toInstant();
    }
    private static OffsetDateTime timestamp(Instant value) { return value==null?null:value.atOffset(ZoneOffset.UTC); }
}
