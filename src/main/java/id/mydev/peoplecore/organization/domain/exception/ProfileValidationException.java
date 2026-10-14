package id.mydev.peoplecore.organization.domain.exception;

public class ProfileValidationException extends RuntimeException {
    public ProfileValidationException(String message) {
        super(message);
    }
}
