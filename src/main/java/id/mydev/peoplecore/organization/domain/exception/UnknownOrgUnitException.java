package id.mydev.peoplecore.organization.domain.exception;

public class UnknownOrgUnitException extends RuntimeException {
    public UnknownOrgUnitException(String message) {
        super(message);
    }
}
