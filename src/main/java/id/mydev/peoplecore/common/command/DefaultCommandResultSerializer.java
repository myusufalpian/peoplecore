package id.mydev.peoplecore.common.command;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.Objects;
import org.springframework.util.Assert;

@Component
public class DefaultCommandResultSerializer implements CommandResultSerializer {

    private final ObjectMapper objectMapper;

    public DefaultCommandResultSerializer(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public <T> String serialize(T result) {
        if (result == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JacksonException e) {
            throw new IllegalArgumentException(
                    "Unsupported type for command result serialization: " + result.getClass().getName(), e);
        }
    }

    @Override
    public <T> T deserialize(String payload, Class<T> resultType) {
        Objects.requireNonNull(resultType, "resultType must not be null");
        if (Void.class.equals(resultType)) {
            return null;
        }
        Objects.requireNonNull(payload, "Non-void command result payload must not be null");
        try {
            T result = objectMapper.readValue(payload, resultType);
            Assert.state(result != null, "Non-void command result must not deserialize to null");
            return result;
        } catch (JacksonException e) {
            throw new IllegalArgumentException(
                    "Failed to deserialize command result to " + resultType.getName(), e);
        }
    }
}
