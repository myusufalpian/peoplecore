package id.mydev.peoplecore.common.api;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeErrorLoggingTest {
    @Test
    void logsDiagnosticContextWithoutExceptionMessagesOrCausePayload() {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        MDC.put("requestId", "safe-request-id");
        try {
            var failure = new IllegalStateException("PRIVATE_OUTER", new SQLException("Failing row contains PRIVATE_ROW"));
            assertEquals(500, new GlobalExceptionHandler().handleGenericException(failure).getStatusCode().value());
            var event = appender.list.getFirst();
            assertFalse(event.getFormattedMessage().contains("PRIVATE"));
            assertNull(event.getThrowableProxy());
            assertTrue(event.getFormattedMessage().contains("safe-request-id"));
            assertTrue(event.getFormattedMessage().contains("IllegalStateException"));
            assertTrue(event.getFormattedMessage().contains("SafeErrorLoggingTest"));
        } finally {
            MDC.remove("requestId");
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
