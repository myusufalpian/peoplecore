package id.mydev.peoplecore.approval.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ApprovalDto {
    private ApprovalDto() { }

    public record RuleRequest(
        @NotBlank @Size(max = 64) String ruleId,
        int priority,
        @Size(max = 128) String orgUnit,
        @Size(max = 64) String jobLevel,
        @NotNull UUID reviewerAccountId
    ) { }

    public record PublishRuleSetRequest(
        @NotEmpty List<@Valid RuleRequest> rules
    ) { }

    public record PreviewRouteRequest(
        @Size(max = 128) String orgUnit,
        @Size(max = 64) String jobLevel,
        UUID employeeId
    ) { }

    public record RuleSetResponse(
        UUID id,
        String status,
        int ruleCount,
        Instant publishedAt
    ) { }

    public record ActivationResponse(
        UUID ruleSetId,
        Instant activatedAt
    ) { }

    public record RoutePreviewResponse(
        boolean matched,
        UUID ruleSetId,
        String ruleId,
        UUID reviewerAccountId,
        String blockedReason,
        String orgUnit,
        String jobLevel
    ) { }
}
