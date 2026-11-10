package id.mydev.peoplecore.reimbursement.application.command;

import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public final class ReimbursementModels {
    private ReimbursementModels() { }

    public record Draft(@NotNull UUID employeePublicId, @NotBlank @Size(max = 128) String categoryCode,
                        @NotNull LocalDate transactionDate, @Positive long amount,
                        @NotBlank @Size(max = 2000) String description, @NotNull @Size(max = 5) List<@NotNull UUID> evidence,
                        Long expectedVersion) { }

    public record Action(@NotNull UUID employeePublicId, @NotNull @PositiveOrZero Long expectedVersion, @Size(max = 2000) String reason, Integer step,
                         UUID reviewerAccountPublicId, LocalDate paidDate, @Size(max = 255) String paymentReference) { }

    public record View(UUID publicId, UUID employeePublicId, String status, int revision, long version,
                       String categoryCode, LocalDate transactionDate, long amount, String description,
                       List<UUID> evidence, String blockedReason) { }

    public record Receipt(UUID publicId, String status, long version) { }

    public record History(UUID publicId, int revision, String action, UUID actorPublicId, String reason,
                          UUID reviewerPublicId, Integer reviewStep, Instant createdAt) { }

    public record HistoryPage(List<History> items, UUID nextCursor) { }

    public record Summary(UUID publicId, UUID employeePublicId, String status, int revision, long version,
                          String categoryCode, LocalDate transactionDate, long amount) { }

    public record ClaimPage(List<Summary> items, UUID nextCursor) { }
}
