package id.mydev.peoplecore.reportexport.api;

import id.mydev.peoplecore.common.api.ErrorResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes=ReportExportController.class)
public class ReportExportExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> invalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(ErrorResponse.of("INVALID_EXPORT_REQUEST","Report export request is invalid"));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> status(ResponseStatusException exception) {
        if(exception.getStatusCode().value()==409) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("EXPORT_QUOTA_EXCEEDED","Two unfinished exports are already pending"));
        }
        if(exception.getStatusCode().value()==503) return unavailable();
        return ResponseEntity.status(exception.getStatusCode())
            .body(ErrorResponse.of("EXPORT_REQUEST_FAILED","Report export request cannot be completed"));
    }

    @ExceptionHandler({TransactionTimedOutException.class,QueryTimeoutException.class})
    public ResponseEntity<ErrorResponse> timeout(RuntimeException exception) { return unavailable(); }

    private static ResponseEntity<ErrorResponse> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER,"1")
            .body(ErrorResponse.of("EXPORT_UNAVAILABLE","Report export service is temporarily unavailable"));
    }
}
