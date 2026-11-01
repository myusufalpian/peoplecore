package id.mydev.peoplecore.leave.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public final class LeaveEntitlementDto {
    private LeaveEntitlementDto() { }

    public record GrantRequest(
        @NotNull UUID employeePublicId,
        @Min(1900) @Max(9998) int year,
        int halfUnits,
        @NotBlank @Size(max = 200) String businessReference,
        @NotBlank @Size(max = 200) String source,
        @NotBlank @Size(max = 1000) String reason) { }
}
