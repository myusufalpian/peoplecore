package id.mydev.peoplecore.common.outbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventIngestionService {
    public static final Duration REPLAY_HORIZON = Duration.ofDays(14);
    private final TransportRepository repository;
    private final List<EventIngestionHandler> handlers;
    private final Clock clock;

    public EventIngestionService(TransportRepository repository, List<EventIngestionHandler> handlers, Clock clock) {
        this.repository = repository;
        this.handlers = List.copyOf(handlers);
        this.clock = clock;
    }

    @Transactional(timeout = 30)
    public void ingest(String consumer, EventEnvelope event) {
        Instant now = clock.instant();
        String rejection = rejection(consumer, event, now);
        if (rejection != null) {
            repository.quarantine(consumer, event.eventId(), rejection, now);
            return;
        }
        List<EventIngestionHandler> matching = handlers.stream().filter(handler -> handler.supports(event.eventType())).toList();
        if (matching.isEmpty()) {
            throw new IllegalStateException("Event handler unavailable");
        }
        if (repository.admit(consumer, event, now)) {
            matching.forEach(handler -> handler.ingest(event));
        }
    }

    public boolean supports(EventEnvelope event) {
        return handlers.stream().anyMatch(handler -> handler.supports(event.eventType()));
    }

    public static String consumer(EventEnvelope event) {
        return "EXPORT_REQUESTED".equals(event.eventType()) ? "exports" : "notifications";
    }

    public static String rejection(String consumer, EventEnvelope event, Instant now) {
        if (!consumer(event).equals(consumer)) {
            return "WRONG_CONSUMER";
        }
        if (event.occurredAt().isAfter(now.plusSeconds(60))) {
            return "FUTURE_EVENT";
        }
        if (event.occurredAt().isBefore(now.minus(REPLAY_HORIZON))) {
            return "REPLAY_HORIZON_EXCEEDED";
        }
        if (!EventRoutingPolicy.knownAsync(event.eventType())) {
            return "UNSUPPORTED_EVENT";
        }
        return null;
    }
}
