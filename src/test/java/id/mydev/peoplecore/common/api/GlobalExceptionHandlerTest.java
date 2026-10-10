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

    @Test
    void staleRevisionsConflictWithoutLeakingInternalMessage() {
        var response = new GlobalExceptionHandler().handleOptimisticLock(
            new org.springframework.orm.ObjectOptimisticLockingFailureException("Stale", new IllegalStateException("v")));
        assertEquals(409, response.getStatusCode().value());
        assertEquals("STALE_REVISION", response.getBody().key());
    }

    @Test
    void lockTimeoutsReturnRetryableStatus() {
        var response = new GlobalExceptionHandler().handlePessimisticLock(
            new org.springframework.dao.PessimisticLockingFailureException("Locked", new IllegalStateException("row")));
        assertEquals(503, response.getStatusCode().value());
        assertEquals("LOCK_TIMEOUT", response.getBody().key());
        assertEquals("1", response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
    }
}
