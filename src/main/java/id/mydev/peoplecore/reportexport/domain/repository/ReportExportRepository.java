package id.mydev.peoplecore.reportexport.domain.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReportExportRepository {
    void lockRequester(UUID accountId);
    long unfinished(UUID accountId);
    void insert(UUID id, UUID requester, String issuer, String subject, String request, Instant created, Instant expiry);
    Optional<Job> find(UUID id, boolean lock);
    Optional<Job> claim(Instant now, Instant leaseUntil);
    List<Job> expiredJobs(Instant now, int limit);
    void registerCandidate(UUID jobId, long generation, UUID candidateId, Instant now, long size, String digest);
    void completeUpload(UUID candidateId, Instant now, long size, String digest);
    boolean ready(UUID id, long generation, UUID candidate, Instant asOf, long authGeneration,
                  Instant boundary, Instant now);
    boolean finish(UUID id, long generation, String state, String reason, Instant now);
    List<Candidate> cleanupCandidates(Instant now, int limit);
    List<PendingCandidate> pendingCandidates(Instant now,int limit);
    void deferReconciliation(UUID candidateId,Instant nextCheck);
    boolean cleanupComplete(UUID candidateId, Instant now);
    void recordTransition(UUID id, String state, Instant now);

    record Job(UUID id, UUID requester, String issuer, String subject, String requestJson, String state,
               Instant createdAt, Instant expiresAt, Instant dataAsOf, long authorizationGeneration,
               Instant nextBoundary, long leaseGeneration, Instant leaseUntil, UUID candidateId,
               Long byteSize, String digest, String reason) { }
    record Candidate(UUID id, UUID jobId) { }
    record PendingCandidate(UUID id, long byteSize, String digest) { }
}
