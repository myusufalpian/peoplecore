package id.mydev.peoplecore.identity.domain.exception;

public class BindingConflictException extends RuntimeException {
    public BindingConflictException(String message) {
        super(message);
    }
}
