package id.mydev.peoplecore.leave.application.command;

import id.mydev.peoplecore.common.command.CommandExecutionService;
import id.mydev.peoplecore.common.command.CommandExecutionService.AuditDescriptor;
import id.mydev.peoplecore.common.command.CommandExecutionService.OutboxDescriptor;
import id.mydev.peoplecore.common.command.CommandRetry;
import id.mydev.peoplecore.leave.application.service.LeaveEntitlementService;
import id.mydev.peoplecore.leave.application.service.LeaveEntitlementService.Balance;
import id.mydev.peoplecore.leave.application.service.LeaveEntitlementService.GrantPayload;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

@Component
public class LeaveEntitlementCommands {
    public static final String OPENING = "LEAVE_OPENING";
    public static final String ADJUSTMENT = "LEAVE_ADJUSTMENT";
    private final CommandExecutionService commands;
    private final LeaveEntitlementService service;
    private final ObjectMapper mapper;

    public LeaveEntitlementCommands(CommandExecutionService commands, LeaveEntitlementService service, ObjectMapper mapper) {
        this.commands = commands;
        this.service = service;
        this.mapper = mapper;
    }

    public Balance grant(Authentication caller, String key, GrantPayload payload, boolean opening) {
        String type = opening ? OPENING : ADJUSTMENT;
        String body = mapper.writeValueAsString(payload);
        var context = new CommandExecutionService.CommandContext(caller.getName(),type,key,body,Duration.ofDays(7));
        return CommandRetry.withRetry(() -> commands.executeCommand(context,
            () -> opening ? service.opening(payload,caller) : service.adjust(payload,caller), Balance.class,
            result -> new AuditDescriptor(type,"LEAVE_ENTITLEMENT",null,result.employeePublicId(),null,"Entitlement ledger entry recorded",mapper.writeValueAsString(result)),
            result -> new OutboxDescriptor(type,1,"LEAVE_ENTITLEMENT",null,result.employeePublicId(),mapper.writeValueAsString(result))));
    }
}
