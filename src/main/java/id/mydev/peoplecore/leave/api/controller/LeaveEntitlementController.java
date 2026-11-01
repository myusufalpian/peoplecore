package id.mydev.peoplecore.leave.api.controller;

import id.mydev.peoplecore.common.api.ApiData;
import id.mydev.peoplecore.common.command.IdempotencyKeys;
import id.mydev.peoplecore.common.security.CurrentCaller;
import id.mydev.peoplecore.leave.api.dto.LeaveEntitlementDto.GrantRequest;
import id.mydev.peoplecore.leave.api.mapper.LeaveEntitlementDtoMapper;
import id.mydev.peoplecore.leave.application.command.LeaveEntitlementCommands;
import id.mydev.peoplecore.leave.application.service.LeaveEntitlementService;
import id.mydev.peoplecore.leave.application.service.LeaveEntitlementService.Balance;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.List;
import id.mydev.peoplecore.leave.application.service.LeaveEntitlementService.LedgerEntry;

@RestController
@RequestMapping("/api/v1/leave/entitlements")
public class LeaveEntitlementController {
    private final LeaveEntitlementCommands commands;
    private final LeaveEntitlementService service;
    private final LeaveEntitlementDtoMapper mapper;

    public LeaveEntitlementController(LeaveEntitlementCommands commands, LeaveEntitlementService service,
                                      LeaveEntitlementDtoMapper mapper) {
        this.commands = commands;
        this.service = service;
        this.mapper = mapper;
    }

    @PostMapping("/opening")
    public ApiData<Balance> opening(@Valid @RequestBody GrantRequest request, @RequestHeader("Idempotency-Key") String key) {
        IdempotencyKeys.requireValid(key);
        return ApiData.of(commands.grant(CurrentCaller.require(),key,mapper.toPayload(request),true));
    }

    @PostMapping("/adjustments")
    public ApiData<Balance> adjustment(@Valid @RequestBody GrantRequest request, @RequestHeader("Idempotency-Key") String key) {
        IdempotencyKeys.requireValid(key);
        return ApiData.of(commands.grant(CurrentCaller.require(),key,mapper.toPayload(request),false));
    }

    @GetMapping("/ledger")
    public ApiData<List<LedgerEntry>> ledger(@RequestParam UUID employeePublicId, @RequestParam int year,
            @RequestParam(required = false) UUID afterEntryId) {
        return ApiData.of(service.ledger(employeePublicId,year,afterEntryId,CurrentCaller.require()));
    }

    @GetMapping
    public ApiData<Balance> balance(@RequestParam UUID employeePublicId, @RequestParam int year) {
        return ApiData.of(service.query(employeePublicId,year,CurrentCaller.require()));
    }
}
