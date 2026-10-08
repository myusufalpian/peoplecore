package id.mydev.peoplecore.common.api;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApiResponseTest {
    @Test
    void validatesPaginationAndComputesEmptyAndPartialPages() {
        assertEquals(0, PageMetadata.of(0, 10, 0).totalPages());
        assertEquals(3, PageMetadata.of(1, 10, 25).totalPages());
        assertEquals(2, PageMetadata.of(0, 10, 20).totalPages());
        assertThrows(InvalidPaginationException.class, () -> PageMetadata.of(-1, 10, 0));
        assertThrows(InvalidPaginationException.class, () -> PageMetadata.of(0, 0, 0));
        assertThrows(InvalidPaginationException.class, () -> PageMetadata.of(0, 101, 0));
        assertThrows(InvalidPaginationException.class, () -> PageMetadata.of(0, 10, -1));
        assertThrows(InvalidPaginationException.class, () -> new PageMetadata(0, 10, 0, -1));
    }

    @Test
    void errorDetailsAreOnlySerializedWhenApplicable() {
        var mapper = new ObjectMapper();
        assertFalse(mapper.valueToTree(ErrorResponse.of("ERROR", "Safe message")).has("details"));
        assertFalse(mapper.valueToTree(ErrorResponse.withDetails("ERROR", "Safe message", List.of())).has("details"));
        assertFalse(mapper.valueToTree(ErrorResponse.withDetails("ERROR", "Safe message", null)).has("details"));
        var response = ErrorResponse.withDetails("VALIDATION_ERROR", "Validation failed", List.of(new ErrorResponse.ValidationErrorDetail("name", "Required")));
        assertEquals("name", mapper.valueToTree(response).path("details").get(0).path("field").asString());
    }
}
