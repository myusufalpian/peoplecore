package id.mydev.peoplecore.organization.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class EmployeeDto {
    private EmployeeDto() { }

    public record CreateEmployeeRequest(
        @NotBlank @Size(max = 64) String employeeNumber
    ) { }

    public record UpdateEmployeeRequest(
        @NotBlank @Size(max = 64) String employeeNumber
    ) { }

    public record EmploymentDatesRequest(
        @NotNull LocalDate startDate,
        LocalDate endDate,
        @NotBlank @Size(max = 500) String reason
    ) { }

    public record ReviseAssignmentRequest(
        @NotBlank @Size(max = 128) String orgUnit,
        UUID managerId,
        @NotBlank @Size(max = 64) String jobLevel,
        @NotNull Instant validFrom,
        Instant validTo,
        @NotBlank @Size(max = 500) String reason
    ) { }

    public record EmployeeResponse(
        UUID id,
        String employeeNumber,
        String status,
        Instant createdAt,
        LocalDate employmentStartDate,
        LocalDate employmentEndDate
    ) { }

    public record AddAssignmentRequest(
        @NotBlank @Size(max = 128) String orgUnit,
        UUID managerId,
        @NotBlank @Size(max = 64) String jobLevel,
        @NotNull Instant validFrom,
        Instant validTo
    ) { }

    public record AssignmentResponse(
        UUID id,
        String orgUnit,
        String jobLevel,
        Instant validFrom,
        Instant validTo,
        UUID managerId,
        Instant supersededAt,
        UUID supersedesId
    ) { }

    public record EmployeeDetailResponse(
        EmployeeResponse employee,
        java.util.List<AssignmentResponse> assignments
    ) { }
}
