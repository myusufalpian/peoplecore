package id.mydev.peoplecore.common.outbox;

import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.common.audit.AuditEvent;
import id.mydev.peoplecore.common.audit.AuditEventWriter;
import id.mydev.peoplecore.common.command.IdempotencyKeys;
import id.mydev.peoplecore.common.command.AdmissionGuard;
import id.mydev.peoplecore.common.security.OperationAccessService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.Objects;
import org.springframework.core.env.Environment;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

@Service
public class EventReplayService {
    private final TransportRepository repository;
    private final OperationAccessService access;
    private final AuditEventWriter audit;
    private final EventIngestionService ingestion;
    private final Clock clock;
    private final Environment environment;
    private final AdmissionGuard admission;

    public EventReplayService(TransportRepository repository, OperationAccessService access, AuditEventWriter audit,
                               EventIngestionService ingestion, Clock clock, Environment environment, AdmissionGuard admission) {
        this.repository = repository;
        this.access = access;
        this.audit = audit;
        this.ingestion = ingestion;
        this.clock = clock;
        this.environment = environment;
        this.admission = admission;
    }

    @Transactional
    public TransportRepository.Replay request(Authentication caller, String key, String consumer, String reason, List<UUID> ids) {
        Instant now = clock.instant();
        admission.acquire(AdmissionGuard.Mode.SHARED);
        access.requireOperator(caller, now);
        requireEnabled();
        IdempotencyKeys.requireValid(key);
        Assert.isTrue(consumer != null && List.of("notifications", "exports").contains(consumer), "Unsupported consumer");
        Assert.isTrue(reason != null && !reason.isBlank() && reason.length() <= 1000, "Replay reason is required");
        Assert.isTrue(ids != null && !ids.isEmpty() && ids.size() <= 100 && ids.stream().allMatch(Objects::nonNull)
            && ids.stream().distinct().count() == ids.size(), "Select 1 to 100 distinct events");
        List<UUID> events = ids.stream().sorted().toList();
        for (UUID id : events) {
            EventEnvelope event = repository.findEvent(id).orElseThrow(ResourceNotFoundException::new);
            Assert.isTrue(EventIngestionService.rejection(consumer, event, now) == null && ingestion.supports(event), "Event is not replay eligible");
        }
        String hash = hash(consumer + "\n" + reason + "\n" + events);
        var result = repository.requestReplay(caller.getName(), key, hash, consumer, reason, events, now);
        audit.append(new AuditEvent(UUID.randomUUID(), caller.getName(), "EVENT_REPLAY_REQUESTED", "EVENT_REPLAY", null,
            result.id(), null, reason, null, now));
        return result;
    }

    @Transactional
    public TransportRepository.Replay cancel(Authentication caller, UUID id, String reason) {
        admission.acquire(AdmissionGuard.Mode.SHARED);
        access.requireOperator(caller, clock.instant());
        Assert.isTrue(reason != null && !reason.isBlank() && reason.length() <= 1000, "Cancellation reason is required");
        var result = repository.cancel(id, clock.instant());
        audit.append(new AuditEvent(UUID.randomUUID(), caller.getName(), "EVENT_REPLAY_CANCELLED", "EVENT_REPLAY", null,
            id, null, reason, null, clock.instant()));
        return result;
    }

    @Transactional(readOnly = true)
    public TransportRepository.Replay get(Authentication caller, UUID id) {
        admission.acquire(AdmissionGuard.Mode.SHARED);
        access.requireOperator(caller, clock.instant());
        return repository.findReplay(id).orElseThrow(ResourceNotFoundException::new);
    }

    private void requireEnabled() {
        Assert.state(!environment.getProperty("peoplecore.recovery.isolated", Boolean.class, false), "Recovery isolation blocks replay");
        Assert.state(environment.getProperty("peoplecore.events.replay.enabled", Boolean.class, false), "Replay is disabled");
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }
}
