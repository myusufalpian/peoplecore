package id.mydev.peoplecore.identity.api.controller;

import id.mydev.peoplecore.common.api.ApiData;
import id.mydev.peoplecore.common.command.IdempotencyKeys;
import id.mydev.peoplecore.common.security.CurrentCaller;
import id.mydev.peoplecore.identity.api.dto.EnrollmentDto;
import id.mydev.peoplecore.identity.api.mapper.EnrollmentDtoMapper;
import id.mydev.peoplecore.identity.application.command.EnrollmentCommands;
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
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/enrollments")
public class EnrollmentController {
    private final EnrollmentCommands commands;

    public EnrollmentController(EnrollmentCommands commands) {
        this.commands = commands;
    }

    @PostMapping("/invitations")
    public ResponseEntity<ApiData<EnrollmentDto.InvitationResponse>> issue(
        @Valid @RequestBody EnrollmentDto.IssueInvitationRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        Authentication caller = CurrentCaller.require();
        IdempotencyKeys.requireValid(idempotencyKey);
        var result = commands.issue(caller, idempotencyKey, request.employeeId(),
            request.intendedIdentityRef(), request.expectedIssuer(), request.expectedSubject());
        var body = ApiData.of(EnrollmentDtoMapper.response(result));
        return ResponseEntity.created(URI.create("/api/v1/enrollments/invitations/" + result.result().invitationId()))
            .body(body);
    }

    @PostMapping("/employees/{id}/invitations")
    public ResponseEntity<ApiData<EnrollmentDto.InvitationResponse>> reissue(
        @PathVariable("id") UUID employeeId,
        @Valid @RequestBody EnrollmentDto.ReissueInvitationRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        Authentication caller = CurrentCaller.require();
        IdempotencyKeys.requireValid(idempotencyKey);
        var result = commands.reissue(caller, idempotencyKey, employeeId,
            request.intendedIdentityRef(), request.expectedIssuer(), request.expectedSubject());
        var body = ApiData.of(EnrollmentDtoMapper.response(result));
        return ResponseEntity.created(URI.create("/api/v1/enrollments/invitations/" + result.result().invitationId()))
            .body(body);
    }

    @PostMapping("/activations")
    public ResponseEntity<ApiData<EnrollmentDto.BindingResponse>> activate(
        @Valid @RequestBody EnrollmentDto.ActivateRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        Authentication caller = CurrentCaller.require();
        IdempotencyKeys.requireValid(idempotencyKey);
        var result = commands.activate(caller, idempotencyKey, request.invitationId(), request.secret());
        return ResponseEntity.ok(ApiData.of(EnrollmentDtoMapper.response(result)));
    }

    @PostMapping("/employees/{id}/offboard")
    public ResponseEntity<ApiData<String>> offboard(
        @PathVariable("id") UUID employeeId,
        @Valid @RequestBody EnrollmentDto.OffboardRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        Authentication caller = CurrentCaller.require();
        IdempotencyKeys.requireValid(idempotencyKey);
        commands.offboard(caller, idempotencyKey, employeeId, request.accessEndsAt(),
            request.cutoffTime(), request.cutoffTimezone(), request.reason());
        return ResponseEntity.ok(ApiData.of("OFFBOARDED"));
    }

    @PostMapping("/employees/{id}/rebind")
    public ResponseEntity<ApiData<String>> rebind(
        @PathVariable("id") UUID employeeId,
        @Valid @RequestBody EnrollmentDto.RebindRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        Authentication caller = CurrentCaller.require();
        IdempotencyKeys.requireValid(idempotencyKey);
        commands.rebind(caller, idempotencyKey, employeeId, request.newIssuer(),
            request.newSubject(), request.reason());
        return ResponseEntity.ok(ApiData.of("REBOUND"));
    }

    @PostMapping("/employees/{id}/rehire")
    public ResponseEntity<ApiData<String>> rehire(
        @PathVariable("id") UUID employeeId,
        @Valid @RequestBody EnrollmentDto.RehireRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        Authentication caller = CurrentCaller.require();
        IdempotencyKeys.requireValid(idempotencyKey);
        commands.rehire(caller, idempotencyKey, employeeId, request.reason());
        return ResponseEntity.ok(ApiData.of("REHIRED"));
    }

    @PostMapping("/employees/{id}/access-restore")
    public ResponseEntity<ApiData<String>> restoreAccess(
        @PathVariable("id") UUID employeeId,
        @Valid @RequestBody EnrollmentDto.RestoreAccessRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        Authentication caller = CurrentCaller.require();
        IdempotencyKeys.requireValid(idempotencyKey);
        commands.restoreAccess(caller, idempotencyKey, employeeId, request.roles(),
            request.scope(), request.reason());
        return ResponseEntity.ok(ApiData.of("ACCESS_RESTORED"));
    }
}
