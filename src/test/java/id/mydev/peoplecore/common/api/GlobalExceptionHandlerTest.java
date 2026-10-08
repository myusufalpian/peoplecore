package id.mydev.peoplecore.common.api;

import id.mydev.peoplecore.common.command.CommandBusyException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerTest {
    @Test
    void busyCommandsReturnRetryableStatusWithoutLeakingInternalMessage() {
        var response = new GlobalExceptionHandler().handleCommandBusy(new CommandBusyException("Internal lock detail"));
        assertEquals(503, response.getStatusCode().value());
        assertEquals("COMMAND_BUSY", response.getBody().key());
        assertEquals("1", response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
    }
}
