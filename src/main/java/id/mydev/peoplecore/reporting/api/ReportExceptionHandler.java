package id.mydev.peoplecore.reporting.api;

import id.mydev.peoplecore.common.api.ErrorResponse;
import id.mydev.peoplecore.common.reporting.ReportLimitException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes={ReportController.class,AuditSearchController.class})
public class ReportExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> invalid(IllegalArgumentException failure) {
        return ResponseEntity.badRequest().body(ErrorResponse.of("INVALID_REPORT_FILTER",failure.getMessage()));
    }
    @ExceptionHandler({TransactionTimedOutException.class,QueryTimeoutException.class})
    public ResponseEntity<ErrorResponse> deadline(RuntimeException failure) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(ErrorResponse.of("REPORT_LIMIT_EXCEEDED","Report execution deadline exceeded; narrow the selected filters"));
    }
    @ExceptionHandler(ReportLimitException.class)
    public ResponseEntity<ErrorResponse> limit(ReportLimitException failure) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(ErrorResponse.of("REPORT_LIMIT_EXCEEDED",failure.getMessage()));
    }
}
