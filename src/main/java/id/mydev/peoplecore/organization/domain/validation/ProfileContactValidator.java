package id.mydev.peoplecore.organization.domain.validation;

import id.mydev.peoplecore.organization.domain.exception.ProfileRemovalDisabledException;
import id.mydev.peoplecore.organization.domain.exception.ProfileValidationException;
import id.mydev.peoplecore.organization.domain.model.ProfileRevisionChange;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class ProfileContactValidator {

    public static final int PHONE_MIN_LENGTH = 3;
    public static final int PHONE_MAX_LENGTH = 32;
    public static final int EMAIL_MAX_LENGTH = 254;
    public static final int ADDRESS_MAX_LENGTH = 1000;
    public static final int EMERGENCY_NAME_MAX_LENGTH = 200;
    public static final int EMERGENCY_RELATIONSHIP_MAX_LENGTH = 100;

    private static final Validator VALIDATOR =
        Validation.buildDefaultValidatorFactory().getValidator();

    private record EmailValue(
        @NotBlank @Size(max = EMAIL_MAX_LENGTH) @Email String value) { }

    private ProfileContactValidator() {
    }

    public static String normalize(String value) {
        if (value == null) {
            return null;
        }
        return value.trim();
    }

    public static String requireKept(String fieldName, String rawValue) {
        String value = normalize(rawValue);
        if (value == null || value.isEmpty()) {
            throw new ProfileRemovalDisabledException(
                "Clearing profile field " + fieldName + " is disabled");
        }
        return value;
    }

    public static void validateField(String fieldName, String value) {
        Objects.requireNonNull(fieldName, "fieldName must not be null");
        Objects.requireNonNull(value, "value must not be null");
        switch (fieldName) {
            case ProfileRevisionChange.FIELD_PHONE, ProfileRevisionChange.FIELD_EMERGENCY_PHONE ->
                validatePhone(fieldName, value);
            case ProfileRevisionChange.FIELD_EMAIL -> validateEmail(value);
            case ProfileRevisionChange.FIELD_ADDRESS -> validateText(
                fieldName, value, ADDRESS_MAX_LENGTH, true);
            case ProfileRevisionChange.FIELD_EMERGENCY_NAME -> validateText(
                fieldName, value, EMERGENCY_NAME_MAX_LENGTH, false);
            case ProfileRevisionChange.FIELD_EMERGENCY_RELATIONSHIP -> validateText(
                fieldName, value, EMERGENCY_RELATIONSHIP_MAX_LENGTH, false);
            default -> throw new ProfileValidationException("Unknown profile field: " + fieldName);
        }
    }

    public static Map<String, String> validatedChanges(Map<String, String> submitted) {
        Objects.requireNonNull(submitted, "submitted must not be null");
        Map<String, String> changes = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : submitted.entrySet()) {
            String field = ProfileRevisionChange.requireKnownField(entry.getKey());
            String value = requireKept(field, entry.getValue());
            validateField(field, value);
            changes.put(field, value);
        }
        return changes;
    }

    private static void validatePhone(String fieldName, String value) {
        if (value.length() < PHONE_MIN_LENGTH || value.length() > PHONE_MAX_LENGTH) {
            throw new ProfileValidationException(
                "Field " + fieldName + " must be between 3 and 32 characters");
        }
        int digits = 0;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current >= '0' && current <= '9') {
                digits++;
                continue;
            }
            if (current == '+' && i == 0 && value.indexOf('+', 1) < 0) {
                continue;
            }
            if (current == ' ' || current == '(' || current == ')'
                || current == '.' || current == '-') {
                continue;
            }
            throw new ProfileValidationException(
                "Field " + fieldName + " contains an unsupported character");
        }
        if (digits < PHONE_MIN_LENGTH) {
            throw new ProfileValidationException(
                "Field " + fieldName + " must contain at least three digits");
        }
    }

    private static void validateEmail(String value) {
        if (!VALIDATOR.validate(new EmailValue(value)).isEmpty() || !hasDottedDomain(value)) {
            throw new ProfileValidationException("Personal email must be a valid email address");
        }
        rejectControlCharacters("personalEmail", value, false);
    }

    private static boolean hasDottedDomain(String value) {
        int at = value.lastIndexOf('@');
        return at > 0 && value.indexOf('.', at) > at + 1
            && value.indexOf('.', at) < value.length() - 1;
    }

    private static void validateText(String fieldName, String value, int maxLength, boolean multiline) {
        if (value.length() > maxLength) {
            throw new ProfileValidationException(
                "Field " + fieldName + " exceeds maximum length " + maxLength);
        }
        rejectControlCharacters(fieldName, value, multiline);
    }

    private static void rejectControlCharacters(String fieldName, String value, boolean multiline) {
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (multiline && (current == '\n' || current == '\r')) {
                continue;
            }
            if (current < 0x20 || current == 0x7F) {
                throw new ProfileValidationException(
                    "Field " + fieldName + " contains an unsupported character");
            }
        }
    }
}
