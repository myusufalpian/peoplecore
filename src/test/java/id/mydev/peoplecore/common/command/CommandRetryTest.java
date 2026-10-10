package id.mydev.peoplecore.common.command;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CommandRetryTest {

    @Test
    void retriesTransientConflictsAndReturnsOutcome() {
        AtomicInteger attempts = new AtomicInteger();
        String result = CommandRetry.withRetry(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw new ObjectOptimisticLockingFailureException("transient", new IllegalStateException("stale"));
            }
            return "committed";
        });
        assertEquals("committed", result);
        assertEquals(3, attempts.get());
    }

    @Test
    void givesUpAfterBoundedAttempts() {
        AtomicInteger attempts = new AtomicInteger();
        assertThrows(CannotAcquireLockException.class, () -> CommandRetry.withRetry(() -> {
            attempts.incrementAndGet();
            throw new CannotAcquireLockException("locked");
        }));
        assertEquals(3, attempts.get());
    }

    @Test
    void nonTransientFailuresAreNotRetried() {
        AtomicInteger attempts = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> CommandRetry.withRetry(() -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("permanent");
        }));
        assertEquals(1, attempts.get());
    }
}
