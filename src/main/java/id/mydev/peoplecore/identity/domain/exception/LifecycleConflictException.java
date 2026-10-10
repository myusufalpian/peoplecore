package id.mydev.peoplecore.identity.domain.exception;

public class LifecycleConflictException extends RuntimeException {
    public LifecycleConflictException(String message) {
        super(message);
    }
}
