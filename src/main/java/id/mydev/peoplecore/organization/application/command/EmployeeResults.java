package id.mydev.peoplecore.organization.application.command;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class EmployeeResults {
    private EmployeeResults() { }

    public record EmployeeResult(
        UUID employeeId,
        String employeeNumber,
        String status,
        Instant createdAt,
        LocalDate employmentStartDate,
        LocalDate employmentEndDate,
        EmployeeState before
    ) {
        public EmployeeResult(UUID employeeId, String employeeNumber, String status, Instant createdAt) {
            this(employeeId, employeeNumber, status, createdAt, null, null, null);
        }
    }

    public record EmployeeState(String employeeNumber, String status,
                                LocalDate employmentStartDate, LocalDate employmentEndDate) { }

    public record AssignmentResult(
        UUID assignmentId,
        UUID employeeId,
        String orgUnit,
        String jobLevel,
        Instant validFrom,
        Instant validTo,
        UUID managerId,
        Instant supersededAt,
        UUID supersedesId,
        AssignmentState before,
        UUID retainedAssignmentId
    ) {
        public AssignmentResult(UUID assignmentId, UUID employeeId, String orgUnit, String jobLevel,
                                Instant validFrom, Instant validTo) {
            this(assignmentId, employeeId, orgUnit, jobLevel, validFrom, validTo, null, null, null, null, null);
        }
    }

    public record AssignmentState(UUID assignmentId, String orgUnit, UUID managerId, String jobLevel,
                                  Instant validFrom, Instant validTo) { }
}
