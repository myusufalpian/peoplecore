package id.mydev.peoplecore.common.api;

public class PayloadTooLargeException extends RuntimeException {
    public PayloadTooLargeException() {
        super("Request payload exceeds the configured limit");
    }
}
