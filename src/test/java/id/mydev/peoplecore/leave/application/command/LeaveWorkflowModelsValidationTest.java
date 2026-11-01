package id.mydev.peoplecore.leave.application.command;

import id.mydev.peoplecore.leave.api.dto.LeaveEntitlementDto.GrantRequest;
import id.mydev.peoplecore.leave.application.command.LeaveWorkflowModels.Action;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeaveWorkflowModelsValidationTest {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void actionRequiresEmployeeAndExpectedVersion() {
        Action action = new Action(null, null, null, null, null, false);

        assertEquals(Set.of("employeePublicId", "expectedVersion"), paths(action));
    }

    @Test
    void actionRejectsNegativeExpectedVersion() {
        Action action = new Action(java.util.UUID.randomUUID(), -1L, null, null, null, false);

        assertEquals(Set.of("expectedVersion"), paths(action));
    }

    @Test
    void entitlementGrantRequiresBoundedIdentityAndText() {
        GrantRequest request = new GrantRequest(null, 1800, 0, " ", " ", " ");

        assertTrue(paths(request).containsAll(Set.of("employeePublicId", "year", "businessReference", "source", "reason")));
    }

    private Set<String> paths(Object value) {
        return validator.validate(value).stream()
            .map(violation -> violation.getPropertyPath().toString())
            .collect(Collectors.toSet());
    }
}
