package id.mydev.peoplecore.common.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IdempotencyKeysTest {

    @Test
    void acceptsWellFormedKeys() {
        assertEquals("key-1", IdempotencyKeys.requireValid("key-1"));
        assertEquals("k".repeat(128), IdempotencyKeys.requireValid("k".repeat(128)));
    }

    @Test
    void rejectsBlankOversizedAndNullKeys() {
        assertThrows(InvalidIdempotencyKeyException.class, () -> IdempotencyKeys.requireValid(null));
        assertThrows(InvalidIdempotencyKeyException.class, () -> IdempotencyKeys.requireValid(""));
        assertThrows(InvalidIdempotencyKeyException.class, () -> IdempotencyKeys.requireValid("   "));
        assertThrows(InvalidIdempotencyKeyException.class, () -> IdempotencyKeys.requireValid("k".repeat(129)));
        assertThrows(InvalidIdempotencyKeyException.class, () -> IdempotencyKeys.requireValid("a\0b"));
    }
}
