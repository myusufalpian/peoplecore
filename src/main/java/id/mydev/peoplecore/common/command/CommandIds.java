package id.mydev.peoplecore.common.command;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

public final class CommandIds {
    private CommandIds() { }

    public static UUID derive(String commandType, String actorId, String idempotencyKey) {
        Objects.requireNonNull(commandType, "commandType must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        String seed = "v1:" + commandType + ":" + actorId.length() + ":" + actorId
            + idempotencyKey.length() + ":" + idempotencyKey;
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }
}
