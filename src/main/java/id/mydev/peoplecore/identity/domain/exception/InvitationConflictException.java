package id.mydev.peoplecore.identity.domain.exception;

public class InvitationConflictException extends RuntimeException {
    public InvitationConflictException(String message) {
        super(message);
    }
}
