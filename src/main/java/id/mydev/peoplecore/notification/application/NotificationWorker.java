package id.mydev.peoplecore.notification.application;

import id.mydev.peoplecore.notification.domain.EmailProvider;
import id.mydev.peoplecore.notification.domain.NotificationModels.Message;
import java.util.Optional;
import java.time.Clock;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.core.env.Environment;

public class NotificationWorker {
    private final NotificationTransactions transactions;
    private final EmailProvider provider;
    private final Clock clock;
    private final Environment environment;
    public NotificationWorker(NotificationTransactions transactions,EmailProvider provider,Clock clock,Environment environment) {
        this.transactions=transactions; this.provider=provider; this.clock=clock; this.environment=environment;
    }
    public boolean poll() {
        if(environment.getProperty("peoplecore.recovery.isolated",Boolean.class,false)) return false;
        var claimed=transactions.claim();
        if(claimed.isEmpty()) return false;
        var delivery=claimed.get();
        Optional<Message> message;
        try {
            message=transactions.prepare(delivery);
        } catch(RuntimeException exception) {
            transactions.finish(delivery,"PENDING",null,"PREPARATION_UNAVAILABLE",clock.instant().plusSeconds(30));
            return true;
        }
        if(message.isEmpty()) return true;
        if(environment.getProperty("peoplecore.recovery.isolated",Boolean.class,false)) {
            transactions.finish(delivery,"PENDING",null,"RECOVERY_ISOLATED",clock.instant().plusSeconds(30));
            return true;
        }
        try {
            String id=provider.send(message.get());
            transactions.finish(delivery,"ACCEPTED",id,null,clock.instant());
        } catch(EmailProvider.DefinitelyUnsentException exception) {
            boolean retry=exception.retryable()&&!delivery.manualAttempt()&&delivery.attempts()<3;
            long cap=delivery.attempts()==1?1000:5000;
            transactions.finish(delivery,retry?"PENDING":"FAILED",null,"PROVIDER_REJECTED",
                clock.instant().plusMillis(retry?ThreadLocalRandom.current().nextLong(cap+1):0));
        } catch(RuntimeException exception) {
            transactions.finish(delivery,"UNKNOWN",null,"UNCERTAIN_ATTEMPT",clock.instant());
        }
        return true;
    }
}
