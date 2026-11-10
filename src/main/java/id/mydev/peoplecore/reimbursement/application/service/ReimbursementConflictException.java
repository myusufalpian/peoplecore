package id.mydev.peoplecore.reimbursement.application.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class ReimbursementConflictException extends RuntimeException {
    public ReimbursementConflictException(String reason) {
        super(reason);
    }
}
