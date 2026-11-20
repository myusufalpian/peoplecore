package id.mydev.peoplecore.infrastructure.recovery;

import id.mydev.peoplecore.common.api.ErrorResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {ServiceRecoveryController.class, OperationsObservabilityController.class})
public class ServiceRecoveryExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> invalidRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest()
            .body(ErrorResponse.of("RECOVERY_REQUEST_INVALID", "Service recovery request is invalid"));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> statusException(ResponseStatusException exception) {
        if (exception.getStatusCode().value() == 503) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(ErrorResponse.of("RECOVERY_STORAGE_UNAVAILABLE", "Document storage is temporarily unavailable"));
        }
        return ResponseEntity.status(exception.getStatusCode())
            .body(ErrorResponse.of("RECOVERY_REQUEST_FAILED", "Service recovery request cannot be completed"));
    }
}
