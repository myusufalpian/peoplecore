package id.mydev.peoplecore.common.api;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PayloadLimitsTest {
    @Test
    void rejectsMultibyteInputExceedingByteLimitDespiteShortCharacterCount() {
        assertThrows(PayloadTooLargeException.class, () -> PayloadLimits.validate("字".repeat(400_000)));
    }
}
