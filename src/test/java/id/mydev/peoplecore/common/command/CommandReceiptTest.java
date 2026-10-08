package id.mydev.peoplecore.common.command;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CommandReceiptTest {

    @Test
    void createsCommandReceiptWithValidFields() {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(3600);

        CommandReceipt receipt = new CommandReceipt(
            "user-1",
            "SUBMIT_LEAVE",
            "idem-key-1",
            "hash-123",
            "SUCCESS",
            "result-id-1",
            now,
            expiresAt
        );

        assertNull(receipt.getId());
        assertEquals("user-1", receipt.getActorId());
        assertEquals("SUBMIT_LEAVE", receipt.getCommandType());
        assertEquals("idem-key-1", receipt.getIdempotencyKey());
        assertEquals("hash-123", receipt.getRequestHash());
        assertEquals("SUCCESS", receipt.getResultStatus());
        assertEquals("result-id-1", receipt.getResultPayload());
        assertEquals(now, receipt.getCreatedAt());
        assertEquals(expiresAt, receipt.getExpiresAt());
    }

    @Test
    void rejectsNullRequiredFields() {
        Instant now = Instant.now();
        assertThrows(NullPointerException.class, () -> new CommandReceipt(
            null, "TYPE", "KEY", "HASH", "STATUS", "PAYLOAD", now, now
        ));
        assertThrows(NullPointerException.class, () -> new CommandReceipt(
            "ACTOR", null, "KEY", "HASH", "STATUS", "PAYLOAD", now, now
        ));
        assertThrows(NullPointerException.class, () -> new CommandReceipt(
            "ACTOR", "TYPE", null, "HASH", "STATUS", "PAYLOAD", now, now
        ));
        assertThrows(NullPointerException.class, () -> new CommandReceipt(
            "ACTOR", "TYPE", "KEY", null, "STATUS", "PAYLOAD", now, now
        ));
        assertThrows(NullPointerException.class, () -> new CommandReceipt(
            "ACTOR", "TYPE", "KEY", "HASH", null, "PAYLOAD", now, now
        ));
        assertThrows(NullPointerException.class, () -> new CommandReceipt(
            "ACTOR", "TYPE", "KEY", "HASH", "STATUS", "PAYLOAD", null, now
        ));
        assertThrows(NullPointerException.class, () -> new CommandReceipt(
            "ACTOR", "TYPE", "KEY", "HASH", "STATUS", "PAYLOAD", now, null
        ));
    }
}
