package id.mydev.peoplecore.organization.application.mapper;

import id.mydev.peoplecore.organization.application.command.EmployeeResults.AssignmentResult;
import id.mydev.peoplecore.organization.application.command.EmployeeResults.AssignmentState;
import id.mydev.peoplecore.organization.application.command.EmployeeResults.EmployeeResult;
import id.mydev.peoplecore.organization.application.command.EmployeeResults.EmployeeState;
import id.mydev.peoplecore.organization.domain.model.Employee;
import id.mydev.peoplecore.organization.domain.model.EmployeeAssignment;

import id.mydev.peoplecore.organization.domain.repository.AssignmentHistoryRow;

import java.util.UUID;

public final class EmployeeResultMapper {
    private EmployeeResultMapper() { }

    public static EmployeeState state(Employee employee) {
        return new EmployeeState(employee.getEmployeeNumber(), employee.getStatus(),
            employee.getEmploymentStartDate(), employee.getEmploymentEndDate());
    }

    public static EmployeeResult result(Employee employee, EmployeeState before) {
        return new EmployeeResult(employee.getPublicId(), employee.getEmployeeNumber(), employee.getStatus(),
            employee.getCreatedAt(), employee.getEmploymentStartDate(), employee.getEmploymentEndDate(), before);
    }

    public static AssignmentResult result(AssignmentHistoryRow row, UUID employeeId) {
        return new AssignmentResult(row.assignmentId(), employeeId, row.orgUnit(), row.jobLevel(),
            row.validFrom(), row.validTo(), row.managerId(), row.supersededAt(), row.supersedesId(), null, null);
    }

    public static AssignmentState state(EmployeeAssignment assignment, UUID managerId) {
        return new AssignmentState(assignment.getPublicId(), assignment.getOrgUnit(), managerId,
            assignment.getJobLevel(), assignment.getValidFrom(), assignment.getValidTo());
    }

    public static AssignmentResult result(EmployeeAssignment assignment, UUID employeeId, UUID managerId,
                                          AssignmentState before, UUID retainedAssignmentId) {
        return new AssignmentResult(assignment.getPublicId(), employeeId, assignment.getOrgUnit(),
            assignment.getJobLevel(), assignment.getValidFrom(), assignment.getValidTo(), managerId,
            assignment.getSupersededAt(), assignment.getSupersedesId(), before, retainedAssignmentId);
    }
}
