package id.mydev.peoplecore.common.command;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CommandExecutionCoordinatorTest {
    private final CommandExecutionCoordinator coordinator = new CommandExecutionCoordinator(new JdbcTemplate(), new ObjectMapper(), 100);

    @Test
    void hashesCanonicalJsonWithoutChangingArrayOrderOrNullSemantics() {
        assertEquals(coordinator.requestHash("{\"b\":2, \"a\":{\"d\":4,\"c\":3}}"),
            coordinator.requestHash("{\"a\":{\"c\":3,\"d\":4},\"b\":2}"));
        assertNotEquals(coordinator.requestHash("[1,2]"), coordinator.requestHash("[2,1]"));
        assertNotEquals(coordinator.requestHash("{}"), coordinator.requestHash("{\"a\":null}"));
    }

    @Test
    void preservesDecimalPrecisionInCanonicalHash() {
        assertNotEquals(coordinator.requestHash("{\"amount\":0.10000000000000001}"),
            coordinator.requestHash("{\"amount\":0.10000000000000000}"));
        assertEquals(coordinator.requestHash("{\"amount\":0.10000000000000001}"),
            coordinator.requestHash("{ \"amount\" : 1.0000000000000001e-1 }"));
    }

    @Test
    void rejectsOversizedPayloadAndExcessiveJsonStructure() {
        assertThrows(id.mydev.peoplecore.common.api.PayloadTooLargeException.class,
            () -> coordinator.requestHash(" ".repeat(id.mydev.peoplecore.common.api.PayloadLimits.MAX_BODY_BYTES + 1)));
        assertThrows(tools.jackson.core.exc.StreamConstraintsException.class,
            () -> coordinator.requestHash("[".repeat(65) + "0" + "]".repeat(65)));
        assertThrows(tools.jackson.core.exc.StreamConstraintsException.class,
            () -> coordinator.requestHash("\"" + "x".repeat(65_537) + "\""));
        assertThrows(tools.jackson.core.exc.StreamConstraintsException.class,
            () -> coordinator.requestHash("[" + "0,".repeat(100_001) + "0]"));
    }

    @Test
    void rejectsMissingTransactionAndInvalidCommandParameters() {
        assertThrows(IllegalStateException.class, () -> coordinator.lock("actor", "type", "key"));
        assertThrows(IllegalArgumentException.class, () -> new CommandExecutionCoordinator(new JdbcTemplate(), new ObjectMapper(), 0));
        assertThrows(IllegalArgumentException.class, () -> coordinator.requestHash(""));
        assertThrows(tools.jackson.core.JacksonException.class, () -> coordinator.requestHash("{\"a\":1,\"a\":2}"));
        assertThrows(tools.jackson.core.JacksonException.class, () -> coordinator.requestHash("{}{}"));
        assertThrows(IllegalArgumentException.class, () -> new CommandExecutionService.CommandContext(" ", "TYPE", "KEY", "{}", null));
        assertThrows(IllegalArgumentException.class, () -> new CommandExecutionService.CommandContext("actor", "TYPE", "KEY", "{}", Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new CommandExecutionService.CommandContext("actor", "TYPE", "x".repeat(129), "{}", null));
        assertThrows(IllegalArgumentException.class, () -> new CommandExecutionService.CommandExecutionPlan<>(java.util.List::of, java.util.List.class, null, null));
    }
}
