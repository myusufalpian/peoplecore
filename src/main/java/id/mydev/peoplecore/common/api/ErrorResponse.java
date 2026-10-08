package id.mydev.peoplecore.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
    String key,
    String message,
    Instant timestamp,
    List<ValidationErrorDetail> details
) {
    public record ValidationErrorDetail(
        String field,
        String message
    ) {}

    public static ErrorResponse of(String key, String message) {
        return new ErrorResponse(key, message, Instant.now(), null);
    }

    public static ErrorResponse withDetails(String key, String message, List<ValidationErrorDetail> details) {
        return new ErrorResponse(key, message, Instant.now(), details != null && !details.isEmpty() ? details : null);
    }
}
