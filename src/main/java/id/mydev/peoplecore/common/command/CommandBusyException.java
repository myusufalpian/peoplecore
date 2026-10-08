package id.mydev.peoplecore.common.command;

public class CommandBusyException extends RuntimeException {
    public CommandBusyException(String message) {
        super(message);
    }

    public CommandBusyException(String message, Throwable cause) {
        super(message, cause);
    }
}
