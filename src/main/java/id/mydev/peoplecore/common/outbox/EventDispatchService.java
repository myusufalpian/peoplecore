package id.mydev.peoplecore.common.outbox;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class EventDispatchService {
    private static final Logger log = LoggerFactory.getLogger(EventDispatchService.class);
    private final TransportRepository repository;
    private final EventIngestionService ingestion;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public EventDispatchService(TransportRepository repository, EventIngestionService ingestion,
                                PlatformTransactionManager manager, Clock clock) {
        this.repository = repository;
        this.ingestion = ingestion;
        this.transactions = new TransactionTemplate(manager);
        this.clock = clock;
    }

    public int publish(EventTransport transport) {
        List<TransportRepository.Publication> work = transactions.execute(status -> repository.claim(clock.instant(), 1));
        if (work == null || work.isEmpty()) {
            return 0;
        }
        var publication = work.getFirst();
        EventEnvelope envelope = publication.envelope();
        if (EventRoutingPolicy.metadataOnly(envelope.eventType())) {
            transactions.executeWithoutResult(status -> repository.ignore(envelope.eventId(),publication.generation(),clock.instant()));
            return 0;
        }
        String consumer = EventIngestionService.consumer(envelope);
        String reason = EventIngestionService.rejection(consumer, envelope, clock.instant());
        if (reason != null) {
            String rejection = reason;
            transactions.executeWithoutResult(status -> {
                repository.abandon(envelope.eventId(), publication.generation(), rejection);
                repository.quarantine(consumer, envelope.eventId(), rejection, clock.instant());
            });
            return 0;
        }
        if (!ingestion.supports(envelope)) {
            Instant now=clock.instant();
            transactions.executeWithoutResult(status -> repository.retry(envelope.eventId(),publication.generation(),now,now.plusSeconds(30)));
            return 0;
        }
        try {
            transport.send(consumer, envelope);
            Boolean committed = transactions.execute(status -> repository.complete(envelope.eventId(), publication.generation(), clock.instant()));
            return Boolean.TRUE.equals(committed) ? 1 : 0;
        } catch (RuntimeException failure) {
            long cap = Math.min(300_000L, 1_000L << Math.min(publication.attempts() - 1, 9));
            Instant now = clock.instant();
            transactions.executeWithoutResult(status -> repository.retry(envelope.eventId(), publication.generation(), now,
                now.plusMillis(ThreadLocalRandom.current().nextLong(cap + 1))));
            log.warn("Event publication deferred [eventId={}, type={}]", envelope.eventId(), failure.getClass().getSimpleName());
            return 0;
        }
    }

    public int replay(EventTransport transport) {
        if (repository.oldestPendingSeconds(clock.instant()) > 300) {
            return 0;
        }
        Optional<TransportRepository.ReplayItem> next = transactions.execute(status -> repository.claimReplay(clock.instant()));
        if (next == null || next.isEmpty()) {
            return 0;
        }
        var item = next.get();
        String rejection = EventIngestionService.rejection(item.consumer(), item.publication().envelope(), clock.instant());
        if (rejection != null) {
            transactions.executeWithoutResult(status -> {
                repository.quarantine(item.consumer(), item.publication().envelope().eventId(),
                    rejection, clock.instant());
                repository.completeReplay(item, "QUARANTINED", clock.instant());
            });
            return 0;
        }
        if (!ingestion.supports(item.publication().envelope())) {
            return 0;
        }
        try {
            transport.send(item.consumer(), item.publication().envelope());
            transactions.executeWithoutResult(status -> repository.completeReplay(item, "PUBLISHED", clock.instant()));
            return 1;
        } catch (RuntimeException failure) {
            log.warn("Replay deferred [replayId={}, type={}]", item.replayId(), failure.getClass().getSimpleName());
            return 0;
        }
    }
}
