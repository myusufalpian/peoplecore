package id.mydev.peoplecore.organization.domain.exception;

public class EmployeeNumberConflictException extends RuntimeException {
    public EmployeeNumberConflictException(String message) {
        super(message);
    }
}
