package id.mydev.peoplecore.identity.domain.exception;

public class ActivationThrottledException extends RuntimeException {
    private final long retryAfterSeconds;

    public ActivationThrottledException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
