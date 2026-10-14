package id.mydev.peoplecore.organization.domain.exception;

public class ProfileStaleVersionException extends RuntimeException {
    public ProfileStaleVersionException(String message) {
        super(message);
    }
}
