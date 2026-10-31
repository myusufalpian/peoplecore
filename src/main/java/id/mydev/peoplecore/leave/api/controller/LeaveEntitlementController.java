package id.mydev.peoplecore.leave.api.controller;

import id.mydev.peoplecore.common.api.ApiData;
import id.mydev.peoplecore.common.command.IdempotencyKeys;
import id.mydev.peoplecore.common.security.CurrentCaller;
import id.mydev.peoplecore.leave.application.command.LeaveEntitlementCommands;
import id.mydev.peoplecore.leave.application.service.LeaveEntitlementService;
import id.mydev.peoplecore.leave.application.service.LeaveEntitlementService.Balance;
import id.mydev.peoplecore.leave.application.service.LeaveEntitlementService.GrantPayload;
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

    public LeaveEntitlementController(LeaveEntitlementCommands commands, LeaveEntitlementService service) {
        this.commands = commands;
        this.service = service;
    }

    @PostMapping("/opening")
    public ApiData<Balance> opening(@RequestBody GrantPayload payload, @RequestHeader("Idempotency-Key") String key) {
        IdempotencyKeys.requireValid(key);
        return ApiData.of(commands.grant(CurrentCaller.require(),key,payload,true));
    }

    @PostMapping("/adjustments")
    public ApiData<Balance> adjustment(@RequestBody GrantPayload payload, @RequestHeader("Idempotency-Key") String key) {
        IdempotencyKeys.requireValid(key);
        return ApiData.of(commands.grant(CurrentCaller.require(),key,payload,false));
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
