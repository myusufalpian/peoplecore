package id.mydev.peoplecore.common.command;

public final class IdempotencyKeys {
    static final int MAX_LENGTH = 128;

    private IdempotencyKeys() { }

    public static String requireValid(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()
            || idempotencyKey.length() > MAX_LENGTH || idempotencyKey.indexOf('\0') >= 0) {
            throw new InvalidIdempotencyKeyException("Idempotency key must be 1-128 visible characters");
        }
        return idempotencyKey;
    }
}
