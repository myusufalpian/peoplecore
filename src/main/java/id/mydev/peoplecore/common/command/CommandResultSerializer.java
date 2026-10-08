package id.mydev.peoplecore.common.command;

public interface CommandResultSerializer {
    <T> String serialize(T result);
    <T> T deserialize(String payload, Class<T> resultType);
}
