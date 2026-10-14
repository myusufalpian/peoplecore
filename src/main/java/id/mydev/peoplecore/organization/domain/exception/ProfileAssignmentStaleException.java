package id.mydev.peoplecore.organization.domain.exception;

public class ProfileAssignmentStaleException extends RuntimeException {
    public ProfileAssignmentStaleException(String message) {
        super(message);
    }
}
