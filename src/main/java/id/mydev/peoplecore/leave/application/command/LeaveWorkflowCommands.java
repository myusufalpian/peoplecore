package id.mydev.peoplecore.leave.application.command;

import id.mydev.peoplecore.common.command.CommandExecutionService;
import id.mydev.peoplecore.common.command.CommandExecutionService.AuditDescriptor;
import id.mydev.peoplecore.common.command.CommandExecutionService.CommandContext;
import id.mydev.peoplecore.common.command.CommandExecutionService.OutboxDescriptor;
import id.mydev.peoplecore.common.command.CommandIds;
import id.mydev.peoplecore.common.command.CommandRetry;
import id.mydev.peoplecore.common.command.IdempotencyKeys;
import id.mydev.peoplecore.leave.application.command.LeaveWorkflowModels.Action;
import id.mydev.peoplecore.leave.application.command.LeaveWorkflowModels.CancellationView;
import id.mydev.peoplecore.leave.application.command.LeaveWorkflowModels.Configuration;
import id.mydev.peoplecore.leave.application.command.LeaveWorkflowModels.Draft;
import id.mydev.peoplecore.leave.application.command.LeaveWorkflowModels.Publish;
import id.mydev.peoplecore.leave.application.command.LeaveWorkflowModels.RequestView;
import id.mydev.peoplecore.leave.application.service.LeaveRoutingService;
import id.mydev.peoplecore.leave.application.service.LeaveWorkflowService;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class LeaveWorkflowCommands {
    private static final Duration RECEIPT_TTL=Duration.ofDays(7);
    private final LeaveWorkflowService workflow;
    private final LeaveRoutingService routing;
    private final CommandExecutionService commands;
    private final ObjectMapper mapper;
    public LeaveWorkflowCommands(LeaveWorkflowService workflow,LeaveRoutingService routing,
                                 CommandExecutionService commands,ObjectMapper mapper) {
        this.workflow=workflow; this.routing=routing; this.commands=commands; this.mapper=mapper;
    }
    public record DraftCommand(UUID employeePublicId,UUID requestPublicId,Draft draft,Long expectedVersion) { }
    public record ActionCommand(UUID employeePublicId,UUID requestPublicId,UUID cancellationPublicId,Action action) { }
    public record ConfigurationCommand(UUID configurationPublicId,Publish publication) { }
    private record Trail(UUID publicId,String status) { }

    public RequestView create(Authentication caller,String key,Draft draft) {
        String type="LEAVE_REQUEST_CREATE";
        UUID id=CommandIds.derive(type,caller.getName(),key);
        return execute(caller,key,type,new DraftCommand(draft.employeePublicId(),id,draft,null),
            ()->workflow.create(id,draft,caller),RequestView.class,id);
    }
    public RequestView edit(Authentication caller,String key,UUID id,Draft draft,long version) {
        return execute(caller,key,"LEAVE_REQUEST_EDIT",new DraftCommand(draft.employeePublicId(),id,draft,version),
            ()->workflow.edit(id,draft,version,caller),RequestView.class,id);
    }
    public RequestView act(Authentication caller,String key,UUID id,String action,Action payload) {
        return execute(caller,key,"LEAVE_REQUEST_"+action,new ActionCommand(payload.employeePublicId(),id,null,payload),
            ()->workflow.act(id,action,payload,caller),RequestView.class,id);
    }
    public CancellationView createCancellation(Authentication caller,String key,UUID request,Action payload) {
        String type="LEAVE_CANCELLATION_CREATE";
        UUID id=CommandIds.derive(type,caller.getName(),key);
        return execute(caller,key,type,new ActionCommand(payload.employeePublicId(),request,id,payload),
            ()->workflow.createCancellation(request,id,payload,caller),CancellationView.class,id);
    }
    public CancellationView actCancellation(Authentication caller,String key,UUID request,UUID cancellation,String action,Action payload) {
        return execute(caller,key,"LEAVE_CANCELLATION_"+action,new ActionCommand(payload.employeePublicId(),request,cancellation,payload),
            ()->workflow.actCancellation(request,cancellation,action,payload,caller),CancellationView.class,cancellation);
    }
    public Configuration publish(Authentication caller,String key,Publish payload) {
        String type="LEAVE_RULE_PUBLISH";
        UUID id=CommandIds.derive(type,caller.getName(),key);
        return execute(caller,key,type,new ConfigurationCommand(id,payload),()->routing.publish(id,payload,caller),Configuration.class,id);
    }
    public Configuration activate(Authentication caller,String key,UUID id) {
        return execute(caller,key,"LEAVE_RULE_ACTIVATE",new ConfigurationCommand(id,null),()->routing.activate(id,caller),Configuration.class,id);
    }
    private <T> T execute(Authentication caller,String key,String type,Object payload,Supplier<T> action,Class<T> resultType,UUID id) {
        IdempotencyKeys.requireValid(key);
        CommandContext context=new CommandContext(caller.getName(),type,key,mapper.writeValueAsString(payload),RECEIPT_TTL);
        return CommandRetry.withRetry(()->commands.executeCommand(context,()->withHistory(action,type),resultType,
            result-> {
                String trail=mapper.writeValueAsString(new Trail(id,trailStatus(result,type)));
                return new AuditDescriptor(type,"LEAVE_WORKFLOW",null,id,null,"Leave workflow transition",trail);
            },
            result-> {
                String trail=mapper.writeValueAsString(new Trail(id,trailStatus(result,type)));
                return new OutboxDescriptor(type,1,"LEAVE_WORKFLOW",null,id,trail);
            }));
    }
    private String trailStatus(Object result,String type) {
        if(result instanceof RequestView request) return request.status();
        if(result instanceof CancellationView cancellation) return cancellation.status();
        if(result instanceof Configuration) return type.endsWith("PUBLISH") ? "PUBLISHED" : "ACTIVE";
        return type;
    }
    private <T> T withHistory(Supplier<T> action,String type) {
        T result=action.get();
        if(result instanceof RequestView request) workflow.recordHistory(request.publicId(),null,type);
        if(result instanceof CancellationView cancellation) workflow.recordHistory(cancellation.requestPublicId(),cancellation.publicId(),type);
        return result;
    }
}
