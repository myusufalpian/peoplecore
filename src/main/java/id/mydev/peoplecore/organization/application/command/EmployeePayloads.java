package id.mydev.peoplecore.organization.application.command;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class EmployeePayloads {
    private EmployeePayloads() { }

    public record CreateEmployeePayload(UUID employeeId, String employeeNumber) { }

    public record UpdateEmployeePayload(UUID employeeId, String employeeNumber) { }

    public record EmploymentDatesPayload(UUID employeeId, LocalDate startDate, LocalDate endDate, String reason) { }

    public record ReviseAssignmentPayload(UUID employeeId, UUID assignmentId, UUID replacementId,
                                          String orgUnit, UUID managerId, String jobLevel,
                                          Instant validFrom, Instant validTo, String reason) { }

    public record AddAssignmentPayload(UUID employeeId, UUID assignmentId, String orgUnit,
                                       UUID managerId, String jobLevel,
                                       Instant validFrom, Instant validTo) { }
}
