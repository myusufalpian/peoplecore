package id.mydev.peoplecore.common.outbox;

import id.mydev.peoplecore.common.api.ErrorResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = EventReplayController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class EventReplayExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> invalid(IllegalArgumentException failure) {
        return ResponseEntity.badRequest().body(ErrorResponse.of("REPLAY_INVALID", "Permintaan replay tidak valid."));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> unavailable(IllegalStateException failure) {
        return ResponseEntity.status(503).body(ErrorResponse.of("REPLAY_UNAVAILABLE", "Replay sedang tidak tersedia."));
    }
}
