package id.mydev.peoplecore.payslip.application;

import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

public final class PayslipModels {
    private PayslipModels() { }

    public record Item(UUID employeePublicId, YearMonth period, UUID documentPublicId) { }
    public record Draft(List<Item> items) { }
    public record Result(UUID batchPublicId, String status, long revision) { }
    public record Detail(UUID batchPublicId, String status, long revision, List<Item> items) { }
    public record Published(UUID publicId, YearMonth period) { }
    public record PublishedVersion(UUID employeePublicId, UUID documentPublicId, UUID versionPublicId) { }
    public record Page(List<Published> items, UUID nextCursor) { }
    public record Mutation(UUID batchPublicId, Draft draft, Long expectedRevision) { }
}
