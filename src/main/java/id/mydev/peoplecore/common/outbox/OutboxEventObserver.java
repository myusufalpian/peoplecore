package id.mydev.peoplecore.common.outbox;

public interface OutboxEventObserver {
    void created(OutboxEvent event);
}
