package id.mydev.peoplecore.organization.domain.exception;

public class ProfileInvalidStateException extends RuntimeException {
    public ProfileInvalidStateException(String message) {
        super(message);
    }
}
