package id.mydev.peoplecore.common.outbox;

import java.util.List;

public interface EventTransport {
    record Message(String receipt, String body) { }
    void send(String consumer, EventEnvelope event);
    List<Message> receive(String consumer);
    void acknowledge(String consumer, String receipt);
    void extend(String consumer, String receipt);
    long deadLetterCount(String consumer);
}
