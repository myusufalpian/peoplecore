package id.mydev.peoplecore.notification.infrastructure.config;

import id.mydev.peoplecore.notification.application.NotificationWorker;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NotificationWorkerLifecycle implements AutoCloseable {
    private static final Logger LOG=LoggerFactory.getLogger(NotificationWorkerLifecycle.class);
    private final NotificationWorker worker;
    private final ScheduledExecutorService executor=Executors.newScheduledThreadPool(2);
    public NotificationWorkerLifecycle(NotificationWorker worker) { this.worker=worker; }
    public void start() {
        for(int index=0;index<2;index++) executor.scheduleWithFixedDelay(this::poll,0,1,TimeUnit.SECONDS);
    }
    private void poll() {
        try { worker.poll(); }
        catch(RuntimeException exception) { LOG.warn("notification_worker_failed errorClass={}",exception.getClass().getSimpleName()); }
    }
    @Override public void close() { executor.shutdownNow(); }
}
