package id.mydev.peoplecore.common.api;

public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException() {
        super("Resource not found in current access scope");
    }
}
