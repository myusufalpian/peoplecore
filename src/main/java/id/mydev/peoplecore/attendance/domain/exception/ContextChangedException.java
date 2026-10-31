package id.mydev.peoplecore.attendance.domain.exception;

public class ContextChangedException extends RuntimeException {

    public ContextChangedException(String message) {
        super(message);
    }
}
