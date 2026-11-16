package id.mydev.peoplecore.notification.api;

import id.mydev.peoplecore.common.api.ErrorResponse;
import id.mydev.peoplecore.notification.domain.InvitationReissueRequiredException;
import id.mydev.peoplecore.identity.api.controller.NotificationDestinationController;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes={NotificationSupportController.class,NotificationDestinationController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class NotificationExceptionAdvice {
    @ExceptionHandler(InvitationReissueRequiredException.class)
    public ResponseEntity<ErrorResponse> identityReissueRequired(InvitationReissueRequiredException exception) {
        return ResponseEntity.badRequest().body(ErrorResponse.of("INVITATION_REISSUE_REQUIRED",
            "Use the authorized identity invitation reissue operation."));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> invalidRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(ErrorResponse.of("VALIDATION_ERROR","The notification request is invalid."));
    }
}
