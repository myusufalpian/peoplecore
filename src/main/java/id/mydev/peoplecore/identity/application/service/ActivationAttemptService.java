package id.mydev.peoplecore.identity.application.service;

import id.mydev.peoplecore.common.audit.AuditEvent;
import id.mydev.peoplecore.common.audit.AuditEventWriter;
import id.mydev.peoplecore.common.command.CommandExecutionService;
import id.mydev.peoplecore.identity.application.mapper.JwtIdentityMapper;
import id.mydev.peoplecore.identity.domain.exception.ActivationThrottledException;
import id.mydev.peoplecore.identity.domain.model.ActivationAttempt;
import id.mydev.peoplecore.identity.domain.model.ActivationAdmissionLock;
import id.mydev.peoplecore.identity.domain.model.ActivationBudgetSlot;
import id.mydev.peoplecore.identity.domain.repository.ActivationAttemptRepository;
import id.mydev.peoplecore.identity.domain.repository.ActivationAdmissionLockRepository;
import id.mydev.peoplecore.identity.domain.repository.ActivationBudgetRepository;
import id.mydev.peoplecore.identity.domain.repository.EnrollmentInvitationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class ActivationAttemptService {

    public static final int MAX_PER_PRINCIPAL_INVITATION = 5;
    public static final int MAX_PER_INVITATION = 30;
    public static final int MAX_PER_PRINCIPAL_GLOBAL = 20;
    public static final Duration WINDOW = Duration.ofMinutes(15);
    private static final String AGGREGATE_INVITATION = "ENROLLMENT_INVITATION";

    private final ActivationAttemptRepository attempts;
    private final ActivationBudgetRepository budgets;
    private final ActivationAdmissionLockRepository admissionLock;
    private final EnrollmentInvitationRepository invitations;
    private final AuditEventWriter auditWriter;
    private final Clock clock;

    public ActivationAttemptService(ActivationAttemptRepository attempts,
                                    ActivationBudgetRepository budgets,
                                    ActivationAdmissionLockRepository admissionLock,
                                    EnrollmentInvitationRepository invitations,
                                    AuditEventWriter auditWriter, Clock clock) {
        this.attempts = attempts;
        this.budgets = budgets;
        this.admissionLock = admissionLock;
        this.invitations = invitations;
        this.auditWriter = auditWriter;
        this.clock = clock;
    }

    private record Tier(String scope, String refA, String refB, int limit) { }

    private record Expected(String issuer, String subject) { }

    @Transactional
    public void reserveAdmission(UUID invitationPublicId, JwtIdentityMapper.OidcIdentity principal) {
        Instant now = Instant.now(clock);
        Expected expected = expectedIdentity(invitationPublicId);
        List<Tier> tiers = admissionTiers(invitationPublicId, principal, expected);
        tiers.sort(Comparator.comparing(Tier::scope).thenComparing(Tier::refA).thenComparing(Tier::refB));
        budgets.deleteByWindowStartBefore(now.minus(WINDOW));
        admissionLock.lockSingleton()
            .orElseGet(() -> admissionLock.save(ActivationAdmissionLock.singleton()));
        List<ActivationBudgetSlot> slots = new ArrayList<>();
        for (Tier tier : tiers) {
            ActivationBudgetSlot slot = budgets.findByScopeAndRefAAndRefB(
                    tier.scope(), tier.refA(), tier.refB())
                .orElseGet(() -> budgets.save(
                    new ActivationBudgetSlot(tier.scope(), tier.refA(), tier.refB(), now, 0)));
            if (slot.getWindowStart().isBefore(now.minus(WINDOW))) {
                slot.reset(now);
            }
            if (slot.getUsed() >= tier.limit()) {
                long retryAfter = Math.max(1,
                    WINDOW.minus(Duration.between(slot.getWindowStart(), now)).getSeconds());
                throw new ActivationThrottledException("Activation attempts are temporarily limited", retryAfter);
            }
            slots.add(slot);
        }
        for (ActivationBudgetSlot slot : slots) {
            slot.use();
        }
    }

    private List<Tier> admissionTiers(UUID invitationPublicId, JwtIdentityMapper.OidcIdentity principal,
                                      Expected expected) {
        List<Tier> tiers = new ArrayList<>();
        tiers.add(new Tier(ActivationBudgetSlot.SCOPE_PRINCIPAL, principal.issuer(), principal.subject(),
            MAX_PER_PRINCIPAL_GLOBAL));
        if (expected != null) {
            tiers.add(new Tier(ActivationBudgetSlot.SCOPE_INVITATION_PRINCIPAL,
                invitationPublicId.toString(), principalRef(principal), MAX_PER_PRINCIPAL_INVITATION));
            if (!isRecipient(expected, principal)) {
                tiers.add(new Tier(ActivationBudgetSlot.SCOPE_INVITATION, invitationPublicId.toString(), "",
                    MAX_PER_INVITATION));
            }
        }
        return tiers;
    }

    private Expected expectedIdentity(UUID invitationPublicId) {
        return invitations.findByPublicId(invitationPublicId)
            .map(invitation -> new Expected(invitation.getExpectedIssuer(), invitation.getExpectedSubject()))
            .orElse(null);
    }

    private static boolean isRecipient(Expected expected, JwtIdentityMapper.OidcIdentity principal) {
        return expected != null
            && expected.issuer().equals(principal.issuer())
            && expected.subject().equals(principal.subject());
    }

    private static String principalRef(JwtIdentityMapper.OidcIdentity principal) {
        return CommandExecutionService.computeSha256(principal.issuer() + "|" + principal.subject());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRejection(UUID invitationPublicId, JwtIdentityMapper.OidcIdentity principal, String actor) {
        Instant now = Instant.now(clock);
        attempts.deleteByCreatedAtBefore(now.minus(WINDOW));
        attempts.save(new ActivationAttempt(invitationPublicId, principal.issuer(), principal.subject(),
            ActivationAttempt.OUTCOME_REJECTED, now));
        auditWriter.append(new AuditEvent(UUID.randomUUID(), actor, "ACTIVATION_REJECTED",
            AGGREGATE_INVITATION, null, invitationPublicId, null,
            "Activation rejected for " + principal.issuer() + " (subject withheld)", null, now));
    }

    @Transactional
    public void clearAttempts(UUID invitationPublicId) {
        attempts.deleteByInvitationPublicId(invitationPublicId);
    }
}
