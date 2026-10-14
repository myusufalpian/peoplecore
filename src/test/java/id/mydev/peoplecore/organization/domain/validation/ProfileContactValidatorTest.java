package id.mydev.peoplecore.organization.domain.validation;

import id.mydev.peoplecore.organization.domain.exception.ProfileRemovalDisabledException;
import id.mydev.peoplecore.organization.domain.exception.ProfileValidationException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileContactValidatorTest {

    @Test
    void phoneAcceptsLocalAndInternationalDisplayFormats() {
        ProfileContactValidator.validateField("personalPhone", "+62 812-3456-789");
        ProfileContactValidator.validateField("personalPhone", "(021) 555.1234");
        ProfileContactValidator.validateField("personalPhone", "0812 345 678");
        ProfileContactValidator.validateField("emergencyContactPhone", "+1 (555) 123-4567");
    }

    @Test
    void phoneRejectsMalformedValues() {
        assertThrows(ProfileValidationException.class,
            () -> ProfileContactValidator.validateField("personalPhone", "12"));
        assertThrows(ProfileValidationException.class,
            () -> ProfileContactValidator.validateField("personalPhone", "abc-def-ghi"));
        assertThrows(ProfileValidationException.class,
            () -> ProfileContactValidator.validateField("personalPhone", "++62812"));
        assertThrows(ProfileValidationException.class,
            () -> ProfileContactValidator.validateField("personalPhone", "12+34 567"));
        assertThrows(ProfileValidationException.class,
            () -> ProfileContactValidator.validateField("personalPhone", "x".repeat(33)));
    }

    @Test
    void emailRequiresStandardFormatWithinBounds() {
        ProfileContactValidator.validateField("personalEmail", "member@example.id");
        ProfileContactValidator.validateField("personalEmail", "First.Last+tag@sub.example.co.id");
        assertThrows(ProfileValidationException.class,
            () -> ProfileContactValidator.validateField("personalEmail", "not-an-email"));
        assertThrows(ProfileValidationException.class,
            () -> ProfileContactValidator.validateField("personalEmail", "a@b"));
        assertThrows(ProfileValidationException.class,
            () -> ProfileContactValidator.validateField("personalEmail", "a".repeat(250) + "@x.id"));
    }

    @Test
    void addressAcceptsMultilineUnicodeText() {
        ProfileContactValidator.validateField("residentialAddress",
            "Jl. Merdeka No. 45\nJakarta Selatan 12345");
        assertThrows(ProfileValidationException.class,
            () -> ProfileContactValidator.validateField("residentialAddress", "x".repeat(1001)));
        assertThrows(ProfileValidationException.class,
            () -> ProfileContactValidator.validateField("residentialAddress", "Line\u0001break"));
    }

    @Test
    void emergencyFieldsRejectControlCharactersAndOverflow() {
        ProfileContactValidator.validateField("emergencyContactName", "Siti Rahayu");
        ProfileContactValidator.validateField("emergencyContactRelationship", "Saudara kandung");
        assertThrows(ProfileValidationException.class,
            () -> ProfileContactValidator.validateField("emergencyContactName", "x".repeat(201)));
        assertThrows(ProfileValidationException.class,
            () -> ProfileContactValidator.validateField("emergencyContactRelationship", "x".repeat(101)));
        assertThrows(ProfileValidationException.class,
            () -> ProfileContactValidator.validateField("emergencyContactRelationship", "a\tb"));
    }

    @Test
    void unknownFieldsRejectedAndBlanksAreRemovalAttempts() {
        Map<String, String> unknown = Map.of("salary", "100");
        assertThrows(IllegalArgumentException.class,
            () -> ProfileContactValidator.validatedChanges(unknown));
        Map<String, String> blank = new LinkedHashMap<>();
        blank.put("personalPhone", "   ");
        assertThrows(ProfileRemovalDisabledException.class,
            () -> ProfileContactValidator.validatedChanges(blank));
        Map<String, String> explicitNull = new LinkedHashMap<>();
        explicitNull.put("personalPhone", null);
        assertThrows(ProfileRemovalDisabledException.class,
            () -> ProfileContactValidator.validatedChanges(explicitNull));
        assertThrows(NullPointerException.class,
            () -> ProfileContactValidator.validatedChanges(null));
    }

    @Test
    void validatedChangesTrimsAndPreservesFieldSet() {
        Map<String, String> submitted = new LinkedHashMap<>();
        submitted.put("personalPhone", "  +62 812 345 678  ");
        Map<String, String> validated = ProfileContactValidator.validatedChanges(submitted);
        assertEquals("+62 812 345 678", validated.get("personalPhone"));
        submitted.put("personalEmail", null);
        assertThrows(ProfileRemovalDisabledException.class,
            () -> ProfileContactValidator.validatedChanges(submitted));
        assertNull(ProfileContactValidator.normalize(null));
        assertThrows(NullPointerException.class,
            () -> ProfileContactValidator.validateField("personalPhone", null));
        assertThrows(ProfileValidationException.class,
            () -> ProfileContactValidator.validateField("unknownField", "value"));
    }
}
