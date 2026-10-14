package id.mydev.peoplecore.organization.domain.exception;

public class ProfileRemovalDisabledException extends RuntimeException {
    public ProfileRemovalDisabledException(String message) {
        super(message);
    }
}
