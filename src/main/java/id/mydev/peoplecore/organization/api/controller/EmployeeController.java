package id.mydev.peoplecore.organization.api.controller;

import id.mydev.peoplecore.common.api.ApiData;
import id.mydev.peoplecore.common.command.IdempotencyKeys;
import id.mydev.peoplecore.common.security.CurrentCaller;
import id.mydev.peoplecore.organization.api.dto.EmployeeDto;
import id.mydev.peoplecore.organization.api.mapper.EmployeeDtoMapper;
import id.mydev.peoplecore.organization.application.command.EmployeeCommands;
import id.mydev.peoplecore.organization.application.service.EmployeeService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/employees")
public class EmployeeController {
    private final EmployeeService employees;
    private final EmployeeCommands commands;

    public EmployeeController(EmployeeService employees, EmployeeCommands commands) {
        this.employees = employees;
        this.commands = commands;
    }

    @PostMapping
    public ResponseEntity<ApiData<EmployeeDto.EmployeeResponse>> create(
        @Valid @RequestBody EmployeeDto.CreateEmployeeRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        Authentication caller = CurrentCaller.require();
        IdempotencyKeys.requireValid(idempotencyKey);
        var result = commands.create(caller, idempotencyKey, request.employeeNumber());
        var body = ApiData.of(EmployeeDtoMapper.response(result));
        return ResponseEntity.created(URI.create("/api/v1/employees/" + result.employeeId())).body(body);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiData<EmployeeDto.EmployeeResponse>> update(
        @PathVariable("id") UUID id,
        @Valid @RequestBody EmployeeDto.UpdateEmployeeRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        Authentication caller = CurrentCaller.require();
        IdempotencyKeys.requireValid(idempotencyKey);
        var result = commands.updateNumber(caller, idempotencyKey, id, request.employeeNumber());
        return ResponseEntity.ok(ApiData.of(EmployeeDtoMapper.response(result)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiData<EmployeeDto.EmployeeDetailResponse>> get(@PathVariable("id") UUID id) {
        Authentication caller = CurrentCaller.require();
        var employee = employees.get(id, caller);
        var assignments = employees.assignmentHistory(id, caller);
        return ResponseEntity.ok(ApiData.of(EmployeeDtoMapper.detail(employee, assignments)));
    }

    @PostMapping("/{id}/assignments")
    public ResponseEntity<ApiData<EmployeeDto.AssignmentResponse>> addAssignment(
        @PathVariable("id") UUID id,
        @Valid @RequestBody EmployeeDto.AddAssignmentRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        Authentication caller = CurrentCaller.require();
        IdempotencyKeys.requireValid(idempotencyKey);
        var result = commands.addAssignment(caller, idempotencyKey, id, request.orgUnit(),
            request.managerId(), request.jobLevel(), request.validFrom(), request.validTo());
        var body = ApiData.of(EmployeeDtoMapper.response(result));
        return ResponseEntity.created(URI.create("/api/v1/employees/" + id + "/assignments/" + result.assignmentId()))
            .body(body);
    }

    @PutMapping("/{id}/employment")
    public ResponseEntity<ApiData<EmployeeDto.EmployeeResponse>> updateEmployment(
        @PathVariable("id") UUID id, @Valid @RequestBody EmployeeDto.EmploymentDatesRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        var result = commands.updateEmploymentDates(CurrentCaller.require(),
            IdempotencyKeys.requireValid(idempotencyKey), id, request.startDate(), request.endDate(), request.reason());
        return ResponseEntity.ok(ApiData.of(EmployeeDtoMapper.response(result)));
    }

    @PostMapping("/{id}/assignments/{assignmentId}/revisions")
    public ResponseEntity<ApiData<EmployeeDto.AssignmentResponse>> reviseAssignment(
        @PathVariable("id") UUID id, @PathVariable("assignmentId") UUID assignmentId,
        @Valid @RequestBody EmployeeDto.ReviseAssignmentRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        var result = commands.reviseAssignment(CurrentCaller.require(), IdempotencyKeys.requireValid(idempotencyKey),
            id, assignmentId, request.orgUnit(), request.managerId(), request.jobLevel(),
            request.validFrom(), request.validTo(), request.reason());
        return ResponseEntity.created(URI.create("/api/v1/employees/" + id + "/assignments/" + result.assignmentId()))
            .body(ApiData.of(EmployeeDtoMapper.response(result)));
    }
}
