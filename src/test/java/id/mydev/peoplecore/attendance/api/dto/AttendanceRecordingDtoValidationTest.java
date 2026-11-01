package id.mydev.peoplecore.attendance.api.dto;

import id.mydev.peoplecore.attendance.api.dto.AttendanceRecordingDto.CorrectionActionRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AttendanceRecordingDtoValidationTest {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void correctionActionRequiresExpectedVersion() {
        var violations = validator.validate(new CorrectionActionRequest(null, null, null, null, null, null, null));

        assertEquals(1, violations.size());
        assertEquals("expectedVersion", violations.iterator().next().getPropertyPath().toString());
    }

    @Test
    void correctionActionRejectsNegativeExpectedVersion() {
        var violations = validator.validate(new CorrectionActionRequest(-1L, null, null, null, null, null, null));

        assertEquals(1, violations.size());
        assertEquals("expectedVersion", violations.iterator().next().getPropertyPath().toString());
    }
}
