package id.mydev.peoplecore.common.api;

import id.mydev.peoplecore.common.command.CommandConflictException;
import id.mydev.peoplecore.common.command.CommandBusyException;
import id.mydev.peoplecore.common.command.InvalidIdempotencyKeyException;
import id.mydev.peoplecore.identity.domain.exception.ActivationThrottledException;
import id.mydev.peoplecore.identity.domain.exception.BindingConflictException;
import id.mydev.peoplecore.identity.domain.exception.InvalidRoleException;
import id.mydev.peoplecore.identity.domain.exception.InvitationConflictException;
import id.mydev.peoplecore.identity.domain.exception.InvitationExpiredException;
import id.mydev.peoplecore.identity.domain.exception.LifecycleConflictException;
import id.mydev.peoplecore.organization.domain.exception.AssignmentOverlapException;
import id.mydev.peoplecore.organization.domain.exception.AssignmentValidationException;
import id.mydev.peoplecore.organization.domain.exception.EmployeeNumberConflictException;
import id.mydev.peoplecore.organization.domain.exception.UnknownOrgUnitException;
import org.springframework.http.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import java.util.Arrays;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.ArrayList;
import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(CommandConflictException.class)
    public ResponseEntity<ErrorResponse> handleCommandConflict(CommandConflictException ex) {
        ErrorResponse response = ErrorResponse.of(
            "COMMAND_CONFLICT",
            "Kunci permintaan sudah digunakan untuk payload berbeda."
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        ErrorResponse response = ErrorResponse.of(
            "ACCESS_DENIED",
            "Akses ke sumber daya ditolak."
        );
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthenticationException(AuthenticationException ex) {
        ErrorResponse response = ErrorResponse.of(
            "UNAUTHORIZED",
            "Kredensial tidak valid atau tidak ditemukan."
        );
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"Peoplecore\"").body(response);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentTypeMismatch(MethodArgumentTypeMismatchException ex) {
        ErrorResponse response = ErrorResponse.of(
            "INVALID_PARAMETER_TYPE",
            "Tipe nilai untuk parameter '%s' tidak valid.".formatted(ex.getName())
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleResourceNotFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of("RESOURCE_NOT_FOUND", "Resource yang diminta tidak ditemukan."));
    }

    @ExceptionHandler(InvalidPaginationException.class)
    public ResponseEntity<ErrorResponse> handleInvalidPagination(InvalidPaginationException ex) {
        return ResponseEntity.badRequest().body(ErrorResponse.of("INVALID_PAGINATION", "Parameter pagination tidak valid."));
    }

    @ExceptionHandler(InvalidIdempotencyKeyException.class)
    public ResponseEntity<ErrorResponse> handleInvalidIdempotencyKey(InvalidIdempotencyKeyException ex) {
        return ResponseEntity.badRequest()
            .body(ErrorResponse.of("INVALID_IDEMPOTENCY_KEY", "Kunci idempotensi tidak valid."));
    }

    @ExceptionHandler(CommandBusyException.class)
    public ResponseEntity<ErrorResponse> handleCommandBusy(CommandBusyException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "1")
            .body(ErrorResponse.of("COMMAND_BUSY", "Permintaan sedang diproses. Silakan coba lagi."));
    }

    @ExceptionHandler(AssignmentOverlapException.class)
    public ResponseEntity<ErrorResponse> handleAssignmentOverlap(AssignmentOverlapException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse.of("ASSIGNMENT_OVERLAP", "Periode penugasan bertabrakan dengan penugasan aktif."));
    }

    @ExceptionHandler(EmployeeNumberConflictException.class)
    public ResponseEntity<ErrorResponse> handleEmployeeNumberConflict(EmployeeNumberConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse.of("EMPLOYEE_CONFLICT", "Nomor karyawan sudah digunakan."));
    }

    @ExceptionHandler(InvitationExpiredException.class)
    public ResponseEntity<ErrorResponse> handleInvitationExpired(InvitationExpiredException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse.of("INVITATION_EXPIRED", "Undangan sudah kedaluwarsa."));
    }

    @ExceptionHandler(InvitationConflictException.class)
    public ResponseEntity<ErrorResponse> handleInvitationConflict(InvitationConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse.of("INVITATION_CONFLICT", "Undangan tidak dapat digunakan."));
    }

    @ExceptionHandler(ActivationThrottledException.class)
    public ResponseEntity<ErrorResponse> handleActivationThrottled(ActivationThrottledException ex) {
        return ResponseEntity.status(429).header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
            .body(ErrorResponse.of("ACTIVATION_THROTTLED", "Upaya aktivasi dibatasi sementara, coba lagi nanti."));
    }

    @ExceptionHandler(BindingConflictException.class)
    public ResponseEntity<ErrorResponse> handleBindingConflict(BindingConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse.of("BINDING_CONFLICT", "Akun sudah terikat dengan karyawan lain."));
    }

    @ExceptionHandler(LifecycleConflictException.class)
    public ResponseEntity<ErrorResponse> handleLifecycleConflict(LifecycleConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse.of("LIFECYCLE_CONFLICT", "Status akses sudah berakhir."));
    }

    @ExceptionHandler(AssignmentValidationException.class)
    public ResponseEntity<ErrorResponse> handleAssignmentValidation(AssignmentValidationException ex) {
        return ResponseEntity.badRequest()
            .body(ErrorResponse.of("ASSIGNMENT_INVALID", "Data penugasan tidak valid."));
    }

    @ExceptionHandler(UnknownOrgUnitException.class)
    public ResponseEntity<ErrorResponse> handleUnknownOrgUnit(UnknownOrgUnitException ex) {
        return ResponseEntity.badRequest()
            .body(ErrorResponse.of("ORG_UNIT_UNKNOWN", "Unit organisasi tidak dikenal."));
    }

    @ExceptionHandler(InvalidRoleException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRole(InvalidRoleException ex) {
        return ResponseEntity.badRequest()
            .body(ErrorResponse.of("INVALID_ROLE", "Role yang diminta tidak valid."));
    }

    @ExceptionHandler(org.springframework.orm.ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(org.springframework.orm.ObjectOptimisticLockingFailureException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse.of("STALE_REVISION", "Data telah berubah, silakan muat ulang dan coba lagi."));
    }

    @ExceptionHandler(org.springframework.dao.PessimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handlePessimisticLock(org.springframework.dao.PessimisticLockingFailureException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "1")
            .body(ErrorResponse.of("LOCK_TIMEOUT", "Resource sedang dikunci, silakan coba lagi."));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
        MethodArgumentNotValidException ex,
        HttpHeaders headers,
        HttpStatusCode status,
        WebRequest request
    ) {
        List<ErrorResponse.ValidationErrorDetail> details = new ArrayList<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            details.add(new ErrorResponse.ValidationErrorDetail(
                fieldError.getField(),
                fieldError.getDefaultMessage() != null ? fieldError.getDefaultMessage() : "Nilai tidak valid"
            ));
        }

        ErrorResponse response = ErrorResponse.withDetails(
            "VALIDATION_ERROR",
            "Validasi masukan gagal.",
            details
        );
        return new ResponseEntity<>(response, headers, status);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
        HttpMessageNotReadableException ex,
        HttpHeaders headers,
        HttpStatusCode status,
        WebRequest request
    ) {
        ErrorResponse response = ErrorResponse.of(
            "MALFORMED_JSON",
            "Format data permintaan tidak valid."
        );
        return new ResponseEntity<>(response, headers, status);
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(
        MissingServletRequestParameterException ex,
        HttpHeaders headers,
        HttpStatusCode status,
        WebRequest request
    ) {
        ErrorResponse response = ErrorResponse.of(
            "MISSING_PARAMETER",
            "Parameter wajib '%s' tidak ditemukan.".formatted(ex.getParameterName())
        );
        return new ResponseEntity<>(response, headers, status);
    }

    @Override
    protected ResponseEntity<Object> handleHttpRequestMethodNotSupported(
        HttpRequestMethodNotSupportedException ex,
        HttpHeaders headers,
        HttpStatusCode status,
        WebRequest request
    ) {
        ErrorResponse response = ErrorResponse.of(
            "METHOD_NOT_ALLOWED",
            "Metode HTTP '%s' tidak didukung untuk endpoint ini.".formatted(ex.getMethod())
        );
        return new ResponseEntity<>(response, headers, status);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMediaTypeNotSupported(
        HttpMediaTypeNotSupportedException ex,
        HttpHeaders headers,
        HttpStatusCode status,
        WebRequest request
    ) {
        ErrorResponse response = ErrorResponse.of(
            "UNSUPPORTED_MEDIA_TYPE",
            "Tipe media permintaan tidak didukung."
        );
        return new ResponseEntity<>(response, headers, status);
    }

    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(
        NoResourceFoundException ex,
        HttpHeaders headers,
        HttpStatusCode status,
        WebRequest request
    ) {
        ErrorResponse response = ErrorResponse.of(
            "RESOURCE_NOT_FOUND",
            "Resource yang diminta tidak ditemukan."
        );
        return new ResponseEntity<>(response, headers, status);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex) {
        logSafeFailure(ex);
        ErrorResponse response = ErrorResponse.of(
            "INTERNAL_SERVER_ERROR",
            "Terjadi kegagalan internal pada server."
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                              HttpStatusCode status, WebRequest request) {
        if (status.is5xxServerError()) {
            logSafeFailure(ex);
        }
        return super.handleExceptionInternal(ex, body, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers,
                                                           HttpStatusCode status, WebRequest request) {
        Object response = body instanceof ErrorResponse ? body : frameworkError(status);
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.putAll(headers);
        responseHeaders.setContentType(MediaType.APPLICATION_JSON);
        return new ResponseEntity<>(response, responseHeaders, status);
    }

    private static void logSafeFailure(Exception ex) {
        log.error("API failure [requestId={}, type={}, frames={}]", MDC.get("requestId"),
            ex.getClass().getName(), Arrays.toString(ex.getStackTrace()));
    }

    @ExceptionHandler(PayloadTooLargeException.class)
    public ResponseEntity<ErrorResponse> handlePayloadTooLarge(PayloadTooLargeException ex) {
        return ResponseEntity.status(413).body(ErrorResponse.of("PAYLOAD_TOO_LARGE", "Ukuran permintaan melebihi batas."));
    }

    private static ErrorResponse frameworkError(HttpStatusCode status) {
        HttpStatus known = HttpStatus.resolve(status.value());
        String key = known != null ? known.name() : "HTTP_ERROR";
        String message = status.is5xxServerError() ? "Terjadi kegagalan internal pada server." : "Permintaan tidak dapat diproses.";
        if (status.value() == 400) {
            key = "VALIDATION_ERROR";
            message = "Validasi masukan gagal.";
        }
        return ErrorResponse.of(key, message);
    }
}
