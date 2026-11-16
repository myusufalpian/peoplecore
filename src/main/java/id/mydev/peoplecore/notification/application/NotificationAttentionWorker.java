package id.mydev.peoplecore.notification.application;

import id.mydev.peoplecore.notification.domain.NotificationAttentionSink;
import id.mydev.peoplecore.notification.domain.repository.NotificationAttentionRepository;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

public class NotificationAttentionWorker {
    private static final Logger LOG=LoggerFactory.getLogger(NotificationAttentionWorker.class);
    private final NotificationAttentionRepository repository;
    private final NotificationAttentionSink sink;
    private final Clock clock;
    private final TransactionTemplate transactions;
    public NotificationAttentionWorker(NotificationAttentionRepository repository,NotificationAttentionSink sink,
            Clock clock,PlatformTransactionManager manager) {
        this.repository=repository; this.sink=sink; this.clock=clock;
        transactions=new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setTimeout(2);
    }
    public boolean poll() {
        var claimed=transactions.execute(status->repository.claim(clock.instant()));
        if(claimed==null || claimed.isEmpty()) return false;
        var claim=claimed.get();
        try {
            sink.send(claim.attention());
            transactions.executeWithoutResult(status->repository.complete(claim,clock.instant()));
        } catch(RuntimeException failure) {
            transactions.executeWithoutResult(status->repository.defer(claim,clock.instant()));
            LOG.warn("Notification alert deferred attention={} errorType={}",claim.attention().id(),failure.getClass().getSimpleName());
        }
        return true;
    }
}
