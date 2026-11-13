package id.mydev.peoplecore.common.command;

import org.springframework.beans.factory.annotation.Value;
import id.mydev.peoplecore.common.api.PayloadLimits;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.Assert;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.ByteBuffer;
import java.util.HexFormat;
import java.util.Map;

@Component
public class CommandExecutionCoordinator {
    private final CommandLockRepository locks;
    private final ObjectMapper objectMapper;
    private final int lockTimeoutMs;
    private final JsonFactory inputFactory = JsonFactory.builder()
        .streamReadConstraints(PayloadLimits.JSON_CONSTRAINTS)
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    public CommandExecutionCoordinator(CommandLockRepository locks, ObjectMapper objectMapper,
                                      @Value("${peoplecore.command.lock-timeout-ms:2000}") int lockTimeoutMs) {
        Assert.isTrue(lockTimeoutMs > 0, "Command lock timeout must be positive");
        this.locks = locks;
        this.objectMapper = objectMapper;
        this.lockTimeoutMs = lockTimeoutMs;
    }

    public void lock(String actorId, String commandType, String key) {
        Assert.state(TransactionSynchronizationManager.isActualTransactionActive(), "Command coordination requires a transaction");
        String identity = actorId.length() + ":" + actorId + commandType.length() + ":" + commandType + key.length() + ":" + key;
        long lockKey = ByteBuffer.wrap(HexFormat.of().parseHex(CommandExecutionService.computeSha256(identity))).getLong();
        locks.lock(lockKey, lockTimeoutMs);
    }

    public String requestHash(String payload) {
        PayloadLimits.validate(payload);
        JsonNode value;
        try (var parser = inputFactory.createParser(ObjectReadContext.empty(), payload)) {
            value = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(parser);
        }
        Assert.isTrue(value != null && !value.isMissingNode(), "Command payload must contain JSON");
        return CommandExecutionService.computeSha256("v1:" + objectMapper.writeValueAsString(canonical(value)));
    }

    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode object = objectMapper.createObjectNode();
            value.properties().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> object.set(entry.getKey(), canonical(entry.getValue())));
            return object;
        }
        if (value.isArray()) {
            ArrayNode array = objectMapper.createArrayNode();
            value.forEach(element -> array.add(canonical(element)));
            return array;
        }
        return value;
    }
}
