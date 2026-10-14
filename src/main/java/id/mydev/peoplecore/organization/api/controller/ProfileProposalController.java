package id.mydev.peoplecore.organization.api.controller;

import id.mydev.peoplecore.common.api.ApiData;
import id.mydev.peoplecore.common.command.IdempotencyKeys;
import id.mydev.peoplecore.common.security.CurrentCaller;
import id.mydev.peoplecore.organization.api.dto.ProfileDto;
import id.mydev.peoplecore.organization.api.mapper.ProfileDtoMapper;
import id.mydev.peoplecore.organization.application.command.ProfileProposalCommands;
import id.mydev.peoplecore.organization.application.command.ProfileResults.ProposalResult;
import id.mydev.peoplecore.organization.application.service.ProfileProposalService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class ProfileProposalController {

    private final ProfileProposalService proposals;
    private final ProfileProposalCommands commands;

    public ProfileProposalController(ProfileProposalService proposals, ProfileProposalCommands commands) {
        this.proposals = proposals;
        this.commands = commands;
    }

    @PostMapping("/employees/me/profile-proposals")
    public ResponseEntity<ApiData<ProfileDto.ProposalResponse>> create(
        @Valid @RequestBody ProfileDto.CreateProposalRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        Authentication caller = CurrentCaller.require();
        IdempotencyKeys.requireValid(idempotencyKey);
        UUID employeeId = proposals.ownEmployeePublicId(caller);
        var result = commands.create(caller, idempotencyKey, employeeId,
            ProfileDtoMapper.payloads(request.changes()));
        var body = ApiData.of(ProfileDtoMapper.response(result));
        return ResponseEntity.created(URI.create("/api/v1/profile-proposals/" + result.proposalPublicId()))
            .body(body);
    }

    @PatchMapping("/profile-proposals/{id}")
    public ResponseEntity<ApiData<ProfileDto.ProposalResponse>> edit(
        @PathVariable("id") UUID id,
        @Valid @RequestBody ProfileDto.EditProposalRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        Authentication caller = CurrentCaller.require();
        IdempotencyKeys.requireValid(idempotencyKey);
        var result = commands.edit(caller, idempotencyKey, id, request.expectedVersion(),
            ProfileDtoMapper.payloads(request.changes()));
        return ResponseEntity.ok(ApiData.of(ProfileDtoMapper.response(result)));
    }

    @PostMapping("/profile-proposals/{id}/submit")
    public ResponseEntity<ApiData<ProfileDto.ProposalResponse>> submit(
        @PathVariable("id") UUID id,
        @Valid @RequestBody ProfileDto.SubmitProposalRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        Authentication caller = CurrentCaller.require();
        IdempotencyKeys.requireValid(idempotencyKey);
        var result = commands.submit(caller, idempotencyKey, id, request.expectedVersion());
        return ResponseEntity.ok(ApiData.of(ProfileDtoMapper.response(result)));
    }

    @PostMapping("/profile-proposals/{id}/resubmit")
    public ResponseEntity<ApiData<ProfileDto.ProposalResponse>> resubmit(
        @PathVariable("id") UUID id,
        @Valid @RequestBody ProfileDto.ResubmitProposalRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        Authentication caller = CurrentCaller.require();
        IdempotencyKeys.requireValid(idempotencyKey);
        var result = commands.resubmit(caller, idempotencyKey, id, request.expectedVersion(),
            ProfileDtoMapper.payloads(request.changes()));
        return ResponseEntity.ok(ApiData.of(ProfileDtoMapper.response(result)));
    }

    @PostMapping("/profile-proposals/{id}/withdraw")
    public ResponseEntity<ApiData<ProfileDto.ProposalResponse>> withdraw(
        @PathVariable("id") UUID id,
        @Valid @RequestBody ProfileDto.WithdrawProposalRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        Authentication caller = CurrentCaller.require();
        IdempotencyKeys.requireValid(idempotencyKey);
        var result = commands.withdraw(caller, idempotencyKey, id, request.expectedVersion());
        return ResponseEntity.ok(ApiData.of(ProfileDtoMapper.response(result)));
    }

    @PostMapping("/profile-proposals/{id}/approve")
    public ResponseEntity<ApiData<ProfileDto.ProposalResponse>> approve(
        @PathVariable("id") UUID id,
        @Valid @RequestBody ProfileDto.DecideProposalRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return ResponseEntity.ok(ApiData.of(ProfileDtoMapper.response(decide(id, request,
            idempotencyKey, "PROFILE_APPROVE"))));
    }

    @PostMapping("/profile-proposals/{id}/reject")
    public ResponseEntity<ApiData<ProfileDto.ProposalResponse>> reject(
        @PathVariable("id") UUID id,
        @Valid @RequestBody ProfileDto.DecideProposalRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return ResponseEntity.ok(ApiData.of(ProfileDtoMapper.response(decide(id, request,
            idempotencyKey, "PROFILE_REJECT"))));
    }

    @PostMapping("/profile-proposals/{id}/return")
    public ResponseEntity<ApiData<ProfileDto.ProposalResponse>> returnForRevision(
        @PathVariable("id") UUID id,
        @Valid @RequestBody ProfileDto.DecideProposalRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return ResponseEntity.ok(ApiData.of(ProfileDtoMapper.response(decide(id, request,
            idempotencyKey, "PROFILE_RETURN"))));
    }

    @PostMapping("/profile-proposals/{id}/reassign")
    public ResponseEntity<ApiData<ProfileDto.ProposalResponse>> reassign(
        @PathVariable("id") UUID id,
        @Valid @RequestBody ProfileDto.ReassignProposalRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        Authentication caller = CurrentCaller.require();
        IdempotencyKeys.requireValid(idempotencyKey);
        var result = commands.reassign(caller, idempotencyKey, id, request.expectedVersion(),
            request.assignmentId(), request.replacementAccountId(), request.reason());
        return ResponseEntity.ok(ApiData.of(ProfileDtoMapper.response(result)));
    }

    @PostMapping("/profile-proposals/{id}/retry-route")
    public ResponseEntity<ApiData<ProfileDto.ProposalResponse>> retryRoute(
        @PathVariable("id") UUID id,
        @Valid @RequestBody ProfileDto.RetryRouteProposalRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        Authentication caller = CurrentCaller.require();
        IdempotencyKeys.requireValid(idempotencyKey);
        var result = commands.retryRoute(caller, idempotencyKey, id, request.expectedVersion());
        return ResponseEntity.ok(ApiData.of(ProfileDtoMapper.response(result)));
    }

    @GetMapping("/profile-proposals/{id}")
    public ResponseEntity<ApiData<ProfileDto.ProposalDetailResponse>> get(@PathVariable("id") UUID id) {
        Authentication caller = CurrentCaller.require();
        return ResponseEntity.ok(ApiData.of(ProfileDtoMapper.response(proposals.getProposal(id, caller))));
    }

    @GetMapping("/profile-proposals/{id}/history")
    public ResponseEntity<ApiData<ProfileDto.HistoryResponse>> history(
        @PathVariable("id") UUID id,
        @RequestParam(value = "page", defaultValue = "0") int page,
        @RequestParam(value = "size", defaultValue = "20") int size,
        @RequestParam(value = "decisionPage", defaultValue = "0") int decisionPage,
        @RequestParam(value = "evaluationPage", defaultValue = "0") int evaluationPage,
        @RequestParam(value = "assignmentPage", defaultValue = "0") int assignmentPage
    ) {
        Authentication caller = CurrentCaller.require();
        var history = proposals.getHistory(id, caller, page, size, decisionPage, evaluationPage, assignmentPage);
        var body = new ApiData<>(ProfileDtoMapper.response(history),
            Map.of("page", page, "size", size, "totalRevisions", history.totalRevisions(),
                "totalDecisions", history.totalDecisions(), "totalEvaluations",
                history.totalEvaluations(), "totalAssignments", history.totalAssignments(),
                "decisionPage", decisionPage, "evaluationPage", evaluationPage, "assignmentPage", assignmentPage));
        return ResponseEntity.ok(body);
    }

    @GetMapping("/profile-proposals")
    public ResponseEntity<ApiData<ProfileDto.QueueResponse>> queue(
        @RequestParam(value = "page", defaultValue = "0") int page,
        @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        Authentication caller = CurrentCaller.require();
        var queue = proposals.queueProposals(caller, page, size);
        var body = new ApiData<>(ProfileDtoMapper.response(queue),
            Map.of("page", queue.page(), "size", queue.size(), "total", queue.total()));
        return ResponseEntity.ok(body);
    }

    @GetMapping("/employees/me/contact-profile")
    public ResponseEntity<ApiData<ProfileDto.ContactProfileResponse>> myContactProfile() {
        Authentication caller = CurrentCaller.require();
        UUID employeeId = proposals.ownEmployeePublicId(caller);
        return ResponseEntity.ok(ApiData.of(ProfileDtoMapper.response(
            proposals.getContactProfile(employeeId, caller))));
    }

    @PatchMapping("/employees/{id}/contact-profile")
    public ResponseEntity<ApiData<ProfileDto.ContactProfileResponse>> masterCorrect(
        @PathVariable("id") UUID id,
        @Valid @RequestBody ProfileDto.MasterCorrectRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        Authentication caller = CurrentCaller.require();
        IdempotencyKeys.requireValid(idempotencyKey);
        var corrected = commands.masterCorrect(caller, idempotencyKey, id,
            request.expectedPhoneVersion(), request.expectedEmailVersion(),
            request.expectedAddressVersion(), request.expectedEmergencyVersion(),
            ProfileDtoMapper.payloads(request.changes()), request.reason());
        return ResponseEntity.ok(ApiData.of(ProfileDtoMapper.response(
            proposals.getContactProfile(corrected.employeePublicId(), caller))));
    }

    private ProposalResult decide(
        UUID id, ProfileDto.DecideProposalRequest request, String idempotencyKey, String commandType
    ) {
        Authentication caller = CurrentCaller.require();
        IdempotencyKeys.requireValid(idempotencyKey);
        return commands.decide(caller, idempotencyKey, id, request.expectedVersion(),
            request.revisionId(), request.assignmentId(), commandType, request.reason());
    }
}
