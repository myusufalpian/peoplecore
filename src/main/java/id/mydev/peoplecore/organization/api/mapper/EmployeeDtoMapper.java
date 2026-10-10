package id.mydev.peoplecore.organization.api.mapper;

import id.mydev.peoplecore.organization.api.dto.EmployeeDto;
import id.mydev.peoplecore.organization.application.command.EmployeeResults.AssignmentResult;
import id.mydev.peoplecore.organization.application.command.EmployeeResults.EmployeeResult;
import id.mydev.peoplecore.organization.domain.model.Employee;

import java.util.List;

public final class EmployeeDtoMapper {
    private EmployeeDtoMapper() { }

    public static EmployeeDto.EmployeeResponse response(EmployeeResult result) {
        return new EmployeeDto.EmployeeResponse(
            result.employeeId(),
            result.employeeNumber(),
            result.status(),
            result.createdAt(), result.employmentStartDate(), result.employmentEndDate());
    }

    public static EmployeeDto.EmployeeResponse response(Employee employee) {
        return new EmployeeDto.EmployeeResponse(
            employee.getPublicId(),
            employee.getEmployeeNumber(),
            employee.getStatus(),
            employee.getCreatedAt(), employee.getEmploymentStartDate(), employee.getEmploymentEndDate());
    }

    public static EmployeeDto.AssignmentResponse response(AssignmentResult result) {
        return new EmployeeDto.AssignmentResponse(
            result.assignmentId(),
            result.orgUnit(),
            result.jobLevel(),
            result.validFrom(),
            result.validTo(), result.managerId(), result.supersededAt(), result.supersedesId());
    }

    public static EmployeeDto.EmployeeDetailResponse detail(Employee employee, List<AssignmentResult> assignments) {
        return new EmployeeDto.EmployeeDetailResponse(
            response(employee),
            assignments.stream().map(EmployeeDtoMapper::response).toList());
    }
}
