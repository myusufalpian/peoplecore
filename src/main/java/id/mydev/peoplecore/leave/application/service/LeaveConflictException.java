package id.mydev.peoplecore.leave.application.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class LeaveConflictException extends RuntimeException {
    public LeaveConflictException(String reason) { super(reason); }
}
