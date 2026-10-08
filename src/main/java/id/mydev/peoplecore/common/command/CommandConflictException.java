package id.mydev.peoplecore.common.command;

public class CommandConflictException extends RuntimeException {
    public CommandConflictException(String message) {
        super(message);
    }
}
