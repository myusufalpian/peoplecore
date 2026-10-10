package id.mydev.peoplecore.identity.domain.repository;

import id.mydev.peoplecore.identity.domain.model.ActivationAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ActivationAttemptRepository extends JpaRepository<ActivationAttempt, Long> {
    @Query("select count(attempt) from ActivationAttempt attempt "
        + "where attempt.invitationPublicId = :invitationPublicId and attempt.createdAt >= :since "
        + "and attempt.outcome in ('REJECTED', 'THROTTLED')")
    long countRecentFailures(UUID invitationPublicId, Instant since);

    @Query("select min(attempt.createdAt) from ActivationAttempt attempt "
        + "where attempt.invitationPublicId = :invitationPublicId and attempt.createdAt >= :since "
        + "and attempt.outcome in ('REJECTED', 'THROTTLED')")
    Optional<Instant> oldestFailureSince(UUID invitationPublicId, Instant since);

    @Query("select count(attempt) from ActivationAttempt attempt "
        + "where attempt.invitationPublicId = :invitationPublicId "
        + "and attempt.principalIssuer = :issuer and attempt.principalSubject = :subject "
        + "and attempt.createdAt >= :since and attempt.outcome in ('REJECTED', 'THROTTLED')")
    long countRecentFailuresByPrincipal(UUID invitationPublicId, String issuer, String subject, Instant since);

    @Query("select min(attempt.createdAt) from ActivationAttempt attempt "
        + "where attempt.invitationPublicId = :invitationPublicId "
        + "and attempt.principalIssuer = :issuer and attempt.principalSubject = :subject "
        + "and attempt.createdAt >= :since and attempt.outcome in ('REJECTED', 'THROTTLED')")
    Optional<Instant> oldestFailureSinceByPrincipal(UUID invitationPublicId, String issuer, String subject,
                                                     Instant since);

    @Query("select count(attempt) from ActivationAttempt attempt "
        + "where attempt.principalIssuer = :issuer and attempt.principalSubject = :subject "
        + "and attempt.createdAt >= :since and attempt.outcome in ('REJECTED', 'THROTTLED')")
    long countRecentFailuresGlobal(String issuer, String subject, Instant since);

    @Query("select min(attempt.createdAt) from ActivationAttempt attempt "
        + "where attempt.principalIssuer = :issuer and attempt.principalSubject = :subject "
        + "and attempt.createdAt >= :since and attempt.outcome in ('REJECTED', 'THROTTLED')")
    Optional<Instant> oldestGlobalFailureSince(String issuer, String subject, Instant since);

    List<ActivationAttempt> findByInvitationPublicIdOrderByCreatedAtAsc(UUID invitationPublicId);

    @Modifying
    void deleteByInvitationPublicId(UUID invitationPublicId);

    @Modifying
    void deleteByCreatedAtBefore(Instant cutoff);
}
