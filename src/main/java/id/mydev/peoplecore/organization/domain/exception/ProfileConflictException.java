package id.mydev.peoplecore.organization.domain.exception;

public class ProfileConflictException extends RuntimeException {
    public ProfileConflictException(String message) {
        super(message);
    }
}
