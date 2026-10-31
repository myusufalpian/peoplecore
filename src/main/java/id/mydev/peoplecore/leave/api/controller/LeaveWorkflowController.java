package id.mydev.peoplecore.leave.api.controller;

import id.mydev.peoplecore.common.api.ApiData;

import id.mydev.peoplecore.leave.application.command.LeaveWorkflowCommands;
import id.mydev.peoplecore.leave.application.command.LeaveWorkflowModels.Action;
import id.mydev.peoplecore.leave.application.command.LeaveWorkflowModels.Activation;
import id.mydev.peoplecore.leave.application.command.LeaveWorkflowModels.CancellationView;
import id.mydev.peoplecore.leave.application.command.LeaveWorkflowModels.Configuration;
import id.mydev.peoplecore.leave.application.command.LeaveWorkflowModels.Draft;
import id.mydev.peoplecore.leave.application.command.LeaveWorkflowModels.PortionView;
import id.mydev.peoplecore.leave.application.command.LeaveWorkflowModels.Publish;
import id.mydev.peoplecore.leave.application.command.LeaveWorkflowModels.RequestView;
import id.mydev.peoplecore.leave.application.command.LeaveWorkflowModels.Route;
import id.mydev.peoplecore.leave.application.command.LeaveWorkflowModels.RequestPage;
import id.mydev.peoplecore.leave.application.command.LeaveWorkflowModels.TimelinePage;
import id.mydev.peoplecore.leave.application.command.LeaveWorkflowModels.SummaryPage;
import id.mydev.peoplecore.leave.application.service.LeaveWorkflowService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.time.LocalDate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class LeaveWorkflowController {
    private static final Set<String> REQUEST_ACTIONS=Set.of("SUBMIT","RESUBMIT","DISCARD","WITHDRAW","APPROVE","REJECT","RETURN","REASSIGN","RETRY_ROUTE");
    private static final Set<String> CANCELLATION_ACTIONS=Set.of("WITHDRAW","APPROVE","REJECT","REASSIGN","RETRY_ROUTE");
    private final LeaveWorkflowCommands commands;
    private final LeaveWorkflowService workflow;
    public LeaveWorkflowController(LeaveWorkflowCommands commands,LeaveWorkflowService workflow) { this.commands=commands; this.workflow=workflow; }
    @PostMapping("/leave/requests")
    public ApiData<RequestView> create(@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Draft body,Authentication caller) { return ApiData.of(commands.create(caller,key,body)); }
    @PostMapping("/leave/requests/{id}/edit")
    public ApiData<RequestView> edit(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestParam long expectedVersion,@Valid @RequestBody Draft body,Authentication caller) { return ApiData.of(commands.edit(caller,key,id,body,expectedVersion)); }
    @PostMapping("/leave/requests/{id}/{action}")
    public ApiData<RequestView> act(@PathVariable UUID id,@PathVariable String action,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Action body,Authentication caller) { return ApiData.of(commands.act(caller,key,id,action(action,REQUEST_ACTIONS),body)); }
    @GetMapping("/leave/requests/{id}")
    public ApiData<RequestView> get(@PathVariable UUID id,Authentication caller) { return ApiData.of(workflow.get(id,caller)); }
    @GetMapping("/leave/requests")
    public ApiData<RequestPage> list(@RequestParam UUID employeePublicId,@RequestParam(required=false) UUID after,@RequestParam(defaultValue="50") int limit,Authentication caller) { return ApiData.of(workflow.listPage(employeePublicId,after,limit,caller)); }
    @GetMapping("/leave/requests/{id}/history")
    public ApiData<TimelinePage> history(@PathVariable UUID id,@RequestParam(required=false) UUID after,@RequestParam(defaultValue="50") int limit,Authentication caller) { return ApiData.of(workflow.timeline(id,null,after,limit,caller)); }
    @GetMapping("/leave/requests/{id}/portions")
    public ApiData<List<PortionView>> portions(@PathVariable UUID id,Authentication caller) { return ApiData.of(workflow.portions(id,caller)); }
    @PostMapping("/leave/requests/{id}/cancellations")
    public ApiData<CancellationView> cancellation(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Action body,Authentication caller) { return ApiData.of(commands.createCancellation(caller,key,id,body)); }
    @PostMapping("/leave/requests/{id}/cancellations/{cancellationId}/{action}")
    public ApiData<CancellationView> cancellationAction(@PathVariable UUID id,@PathVariable UUID cancellationId,@PathVariable String action,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Action body,Authentication caller) { return ApiData.of(commands.actCancellation(caller,key,id,cancellationId,action(action,CANCELLATION_ACTIONS),body)); }
    @GetMapping("/leave/requests/{id}/cancellations/{cancellationId}")
    public ApiData<CancellationView> cancellation(@PathVariable UUID id,@PathVariable UUID cancellationId,Authentication caller) { return ApiData.of(workflow.getCancellation(id,cancellationId,caller)); }
    @GetMapping("/leave/requests/{id}/cancellations/{cancellationId}/history")
    public ApiData<TimelinePage> cancellationHistory(@PathVariable UUID id,@PathVariable UUID cancellationId,@RequestParam(required=false) UUID after,@RequestParam(defaultValue="50") int limit,Authentication caller) { return ApiData.of(workflow.timeline(id,cancellationId,after,limit,caller)); }
    @PostMapping("/approval/leave-rule-sets")
    public ApiData<Configuration> publish(@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Publish body,Authentication caller) { return ApiData.of(commands.publish(caller,key,body)); }
    @PostMapping("/approval/leave-rule-sets/activate")
    public ApiData<Configuration> activate(@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Activation body,Authentication caller) { return ApiData.of(commands.activate(caller,key,body.ruleSetPublicId())); }
    @GetMapping("/approval/leave-rule-sets/route-preview")
    public ApiData<Route> preview(@RequestParam UUID employeePublicId,Authentication caller) { return ApiData.of(workflow.preview(employeePublicId,caller)); }
    @GetMapping("/leave/portions")
    public ApiData<SummaryPage> summary(@RequestParam UUID employeePublicId,@RequestParam LocalDate from,@RequestParam LocalDate to,@RequestParam(required=false) UUID after,@RequestParam(defaultValue="50") int limit,Authentication caller) { return ApiData.of(workflow.summary(employeePublicId,from,to,after,limit,caller)); }
    private String action(String value,Set<String> allowed) {
        String normalized=value.toUpperCase(Locale.ROOT).replace('-','_');
        if(!allowed.contains(normalized)) throw new IllegalArgumentException("Unknown leave action"); return normalized;
    }
}
