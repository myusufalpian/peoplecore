package id.mydev.peoplecore.leave.application.command;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public final class LeaveWorkflowModels {
    private LeaveWorkflowModels() { }
    public record Draft(@NotNull UUID employeePublicId, @NotNull LocalDate fromDate,
                        @NotNull LocalDate toDate, @NotNull Portion portion,
                        @NotBlank @Size(max=2000) String reason) { }
    public enum Portion { FULL, FIRST_HALF, SECOND_HALF }
    public record Action(@NotNull UUID employeePublicId, @NotNull @Min(0) Long expectedVersion,
                         @Size(max=2000) String reason, UUID reviewerAccountPublicId,
                         @Min(1) @Max(2) Integer step, boolean fallback) { }
    public record Rule(@NotBlank @Size(max=128) String ruleId, int priority,
                       @NotBlank @Size(max=128) String orgUnit,
                       @NotNull UUID hrAccountPublicId, @NotNull UUID cancellationAccountPublicId) { }
    public record Publish(@NotEmpty @Size(max=100) List<@Valid Rule> rules) { }
    public record Activation(@NotNull UUID ruleSetPublicId) { }
    public record Configuration(UUID publicId, List<Rule> rules) { }
    public record RequestView(UUID publicId, UUID employeePublicId, String status, int revision,
                              long version, LocalDate fromDate, LocalDate toDate, Portion portion,
                              String reason, String blockedReason) { }
    public record CancellationView(UUID publicId, UUID requestPublicId, String status,
                                   long version, String reason, String blockedReason) { }
    public record History(int revision, int step, UUID reviewerAccountPublicId,
                          String outcome, String reason, Instant createdAt) { }
    public record PortionView(LocalDate workDate, int half, Instant startAt, Instant endAt) { }
    public record Route(UUID configurationPublicId, UUID managerAccountPublicId,
                        UUID hrAccountPublicId, UUID cancellationAccountPublicId,
                        String blockedReason) { }
    public record TimelineEntry(UUID publicId, String action, int revision,
                                UUID cancellationPublicId, JsonNode payload, Instant createdAt) { }
    public record TimelinePage(List<TimelineEntry> items, UUID nextCursor) { }
    public record RequestPage(List<RequestView> items, UUID nextCursor) { }
    public record Summary(UUID publicId, UUID requestPublicId, LocalDate workDate,
                          int half, Instant startAt, Instant endAt, String status) { }
    public record SummaryPage(List<Summary> items, UUID nextCursor) { }
}
