package id.mydev.peoplecore.common.command;

import org.springframework.beans.factory.annotation.Value;
import id.mydev.peoplecore.common.api.PayloadLimits;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.ObjectReadContext;
import org.springframework.dao.DataAccessException;
import java.sql.SQLException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final int lockTimeoutMs;
    private final JsonFactory inputFactory = JsonFactory.builder()
        .streamReadConstraints(PayloadLimits.JSON_CONSTRAINTS)
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    public CommandExecutionCoordinator(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                                      @Value("${peoplecore.command.lock-timeout-ms:2000}") int lockTimeoutMs) {
        Assert.isTrue(lockTimeoutMs > 0, "Command lock timeout must be positive");
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.lockTimeoutMs = lockTimeoutMs;
    }

    public void lock(String actorId, String commandType, String key) {
        Assert.state(TransactionSynchronizationManager.isActualTransactionActive(), "Command coordination requires a transaction");
        String identity = actorId.length() + ":" + actorId + commandType.length() + ":" + commandType + key.length() + ":" + key;
        long lockKey = ByteBuffer.wrap(HexFormat.of().parseHex(CommandExecutionService.computeSha256(identity))).getLong();
        String previousTimeout = jdbcTemplate.queryForObject("show lock_timeout", String.class);
        jdbcTemplate.queryForObject("select set_config('lock_timeout', ?, true)", String.class, lockTimeoutMs + "ms");
        try {
            jdbcTemplate.query("select pg_advisory_xact_lock(?)", rs -> { }, lockKey);
        } catch (DataAccessException ex) {
            if (ex.getMostSpecificCause() instanceof SQLException sql && "55P03".equals(sql.getSQLState())) {
                throw new CommandBusyException("Command lock wait timed out", ex);
            }
            throw ex;
        }
        jdbcTemplate.queryForObject("select set_config('lock_timeout', ?, true)", String.class, previousTimeout);
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
