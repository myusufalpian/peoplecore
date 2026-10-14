package id.mydev.peoplecore.approval.api.mapper;

import id.mydev.peoplecore.approval.api.dto.ApprovalDto;
import id.mydev.peoplecore.approval.application.command.ApprovalPayloads.RulePayload;
import id.mydev.peoplecore.approval.application.command.ApprovalResults.ActivationResult;
import id.mydev.peoplecore.approval.application.command.ApprovalResults.RoutePreview;
import id.mydev.peoplecore.approval.application.command.ApprovalResults.RuleSetResult;

public final class ApprovalDtoMapper {
    private ApprovalDtoMapper() { }

    public static RulePayload payload(ApprovalDto.RuleRequest request) {
        String orgUnit = request.orgUnit() == null || request.orgUnit().isBlank() ? null : request.orgUnit();
        String jobLevel = request.jobLevel() == null || request.jobLevel().isBlank() ? null : request.jobLevel();
        return new RulePayload(request.ruleId(), request.priority(), orgUnit, jobLevel,
            request.reviewerAccountId());
    }

    public static ApprovalDto.RuleSetResponse response(RuleSetResult result) {
        return new ApprovalDto.RuleSetResponse(result.ruleSetPublicId(), result.status(),
            result.ruleCount(), result.publishedAt());
    }

    public static ApprovalDto.ActivationResponse response(ActivationResult result) {
        return new ApprovalDto.ActivationResponse(result.ruleSetPublicId(), result.activatedAt());
    }

    public static ApprovalDto.RoutePreviewResponse response(RoutePreview preview) {
        return new ApprovalDto.RoutePreviewResponse(preview.matched(), preview.ruleSetPublicId(),
            preview.ruleId(), preview.reviewerAccountPublicId(), preview.blockedReason(),
            preview.orgUnit(), preview.jobLevel());
    }
}
