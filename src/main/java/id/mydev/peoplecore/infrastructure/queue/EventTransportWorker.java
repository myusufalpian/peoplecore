package id.mydev.peoplecore.infrastructure.queue;

import id.mydev.peoplecore.common.outbox.EventDispatchService;
import id.mydev.peoplecore.common.outbox.EventEnvelope;
import id.mydev.peoplecore.common.outbox.EventIngestionService;
import id.mydev.peoplecore.common.outbox.EventTransport;
import id.mydev.peoplecore.common.outbox.TransportRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

public class EventTransportWorker implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(EventTransportWorker.class);
    private final ScheduledExecutorService workers = new ScheduledThreadPoolExecutor(9);
    private final EventTransport transport;
    private final EventDispatchService dispatcher;
    private final EventIngestionService ingestion;
    private final TransportRepository repository;
    private final ObjectMapper mapper;
    private final Environment environment;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public EventTransportWorker(EventTransport transport, EventDispatchService dispatcher, EventIngestionService ingestion,
                                 TransportRepository repository, ObjectMapper mapper, Environment environment,
                                 Clock clock, PlatformTransactionManager manager) {
        this.transport = transport;
        this.dispatcher = dispatcher;
        this.ingestion = ingestion;
        this.repository = repository;
        this.mapper = mapper;
        this.environment = environment;
        this.clock = clock;
        this.transactions = new TransactionTemplate(manager);
    }

    public void start() {
        for (int index = 0; index < 2; index++) {
            schedule(() -> dispatcher.publish(transport), 100);
        }
        for (String consumer : new String[] {"notifications", "exports"}) {
            for (int index = 0; index < 2; index++) {
                schedule(() -> poll(consumer), 100);
            }
        }
        schedule(() -> {
            if (environment.getProperty("peoplecore.events.replay.enabled", Boolean.class, false)) {
                dispatcher.replay(transport);
            }
        }, 100);
        schedule(this::monitor, 30_000);
    }

    private void schedule(Runnable work, long delay) {
        workers.scheduleWithFixedDelay(() -> {
            if (isolated()) {
                return;
            }
            try {
                work.run();
            } catch (RuntimeException failure) {
                log.warn("Event worker deferred [type={}]", failure.getClass().getSimpleName());
            }
        }, 0, delay, TimeUnit.MILLISECONDS);
    }

    public void poll(String consumer) {
        if (isolated()) {
            return;
        }
        for (EventTransport.Message message : transport.receive(consumer)) {
            if (isolated()) {
                return;
            }
            EventEnvelope envelope;
            try {
                if (message.body().getBytes(StandardCharsets.UTF_8).length > SqsEventTransport.MAX_BYTES) {
                    throw new IllegalArgumentException("Envelope exceeds limit");
                }
                envelope = mapper.readValue(message.body(), EventEnvelope.class);
            } catch (RuntimeException failure) {
                UUID diagnostic = UUID.nameUUIDFromBytes(message.body().getBytes(StandardCharsets.UTF_8));
                transactions.executeWithoutResult(status -> repository.quarantine(consumer, diagnostic, "INVALID_ENVELOPE", clock.instant()));
                transport.acknowledge(consumer, message.receipt());
                continue;
            }
            ScheduledFuture<?> heartbeat = workers.scheduleWithFixedDelay(() -> {
                try {
                    transport.extend(consumer, message.receipt());
                } catch (RuntimeException failure) {
                    log.warn("Message visibility extension failed [eventId={}, type={}]", envelope.eventId(), failure.getClass().getSimpleName());
                }
            }, 15, 15, TimeUnit.SECONDS);
            try {
                ingestion.ingest(consumer, envelope);
                transport.acknowledge(consumer, message.receipt());
            } finally {
                heartbeat.cancel(false);
            }
        }
    }

    private void monitor() {
        long age = repository.oldestPendingSeconds(clock.instant());
        long quarantined = repository.quarantineCount();
        for (String consumer : new String[] {"notifications", "exports"}) {
            long dead = transport.deadLetterCount(consumer);
            if (age > 300 || dead > 0 || quarantined > 0) {
                log.error("Event transport needs attention [consumer={}, oldestPendingSeconds={}, deadLetters={}, quarantined={}]",
                    consumer, age, dead, quarantined);
            }
        }
    }

    private boolean isolated() {
        return environment.getProperty("peoplecore.recovery.isolated", Boolean.class, false);
    }

    @Override
    public void close() {
        workers.shutdownNow();
    }
}
