package id.mydev.peoplecore.common.outbox;

public interface EventIngestionHandler {
    boolean supports(String eventType);
    void ingest(EventEnvelope envelope);
}
