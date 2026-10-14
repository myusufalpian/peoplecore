package id.mydev.peoplecore.approval.api.controller;

import id.mydev.peoplecore.approval.api.dto.ApprovalDto;
import id.mydev.peoplecore.approval.api.mapper.ApprovalDtoMapper;
import id.mydev.peoplecore.approval.application.command.ApprovalPayloads.RulePayload;
import id.mydev.peoplecore.approval.application.command.ApprovalRuleCommands;
import id.mydev.peoplecore.approval.application.command.ApprovalResults.ActivationResult;
import id.mydev.peoplecore.approval.application.command.ApprovalResults.RuleSetResult;
import id.mydev.peoplecore.approval.application.service.ApprovalRuleService;
import id.mydev.peoplecore.common.api.ApiData;
import id.mydev.peoplecore.common.command.CommandIds;
import id.mydev.peoplecore.common.command.IdempotencyKeys;
import id.mydev.peoplecore.common.security.CurrentCaller;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/approval")
public class ApprovalRuleController {

    private final ApprovalRuleService rules;
    private final ApprovalRuleCommands commands;

    public ApprovalRuleController(ApprovalRuleService rules, ApprovalRuleCommands commands) {
        this.rules = rules;
        this.commands = commands;
    }

    @PostMapping("/profile-rule-sets")
    public ResponseEntity<ApiData<ApprovalDto.RuleSetResponse>> publish(
        @Valid @RequestBody ApprovalDto.PublishRuleSetRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        Authentication caller = CurrentCaller.require();
        IdempotencyKeys.requireValid(idempotencyKey);
        UUID ruleSetId = CommandIds.derive("PROFILE_RULE_PUBLISH", caller.getName(), idempotencyKey);
        List<RulePayload> inputs = new ArrayList<>(request.rules().size());
        for (ApprovalDto.RuleRequest rule : request.rules()) {
            inputs.add(ApprovalDtoMapper.payload(rule));
        }
        RuleSetResult result = commands.publish(caller, idempotencyKey, ruleSetId, inputs);
        var body = ApiData.of(ApprovalDtoMapper.response(result));
        return ResponseEntity.created(URI.create("/api/v1/approval/profile-rule-sets/" + result.ruleSetPublicId()))
            .body(body);
    }

    @PostMapping("/profile-rule-sets/{id}/activate")
    public ResponseEntity<ApiData<ApprovalDto.ActivationResponse>> activate(
        @PathVariable("id") UUID id,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        Authentication caller = CurrentCaller.require();
        IdempotencyKeys.requireValid(idempotencyKey);
        ActivationResult result = commands.activate(caller, idempotencyKey, id);
        return ResponseEntity.ok(ApiData.of(ApprovalDtoMapper.response(result)));
    }

    @PostMapping("/profile-routes/preview")
    public ResponseEntity<ApiData<ApprovalDto.RoutePreviewResponse>> preview(
        @Valid @RequestBody ApprovalDto.PreviewRouteRequest request
    ) {
        Authentication caller = CurrentCaller.require();
        var preview = rules.previewRoute(request.orgUnit(), request.jobLevel(),
            request.employeeId(), caller);
        return ResponseEntity.ok(ApiData.of(ApprovalDtoMapper.response(preview)));
    }
}
