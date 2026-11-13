package id.mydev.peoplecore.reimbursement.application.service;

import id.mydev.peoplecore.common.command.AdmissionGuard;
import id.mydev.peoplecore.identity.application.service.AccountAccessService;
import id.mydev.peoplecore.identity.domain.model.HrisRole;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.reimbursement.domain.repository.ReimbursementCategoryRepository;
import id.mydev.peoplecore.reimbursement.domain.repository.ReimbursementCategoryRepository.CategoryRecord;
import id.mydev.peoplecore.reimbursement.domain.repository.ReimbursementCategoryRepository.OverlapRecord;
import jakarta.persistence.EntityManager;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class ReimbursementCategoryService {
    private final ReimbursementCategoryRepository categories;
    private final AccountAccessService access;
    private final Clock clock;
    private final AdmissionGuard admission;
    private final EntityManager entities;

    public ReimbursementCategoryService(ReimbursementCategoryRepository categories, AccountAccessService access,
                                        Clock clock, AdmissionGuard admission, EntityManager entities) {
        this.categories = categories;
        this.access = access;
        this.clock = clock;
        this.admission = admission;
        this.entities = entities;
    }

    @Transactional(readOnly = true)
    public CategoryPage applicable(LocalDate date, int limit, UUID cursor, Authentication caller) {
        Objects.requireNonNull(date, "date");
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Limit must be between 1 and 100");
        admission.acquire(AdmissionGuard.Mode.SHARED);
        entities.clear();
        verifyReader(caller);
        List<CategoryView> rows = categories.findApplicable(date, cursor, limit + 1).stream()
            .map(ReimbursementCategoryService::toView)
            .toList();
        entities.clear();
        verifyReader(caller);
        boolean hasMore = rows.size() > limit;
        List<CategoryView> page = List.copyOf(hasMore ? rows.subList(0, limit) : rows);
        return new CategoryPage(page, hasMore ? page.getLast().publicId() : null);
    }

    private void verifyReader(Authentication caller) {
        UserAccount actor = access.requireActive(caller, clock.instant());
        if (!access.hasRole(actor.getId(), HrisRole.HR_ADMIN, clock.instant())
            && !access.hasRole(actor.getId(), HrisRole.FINANCE_PAYROLL, clock.instant())) {
            throw new AccessDeniedException("HR or Finance authority required");
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(UUID publicId, Category category, Authentication caller) {
        Objects.requireNonNull(publicId, "publicId");
        validate(category);
        verifyAuthority(caller);
        categories.lockPublication(category.code());
        List<OverlapRecord> overlaps = categories.lockOverlaps(category.code(), category.from(), category.to());
        if (overlaps.size() > 1) {
            throw new ReimbursementConflictException("CATEGORY_OVERLAP");
        }
        verifyAuthority(caller);
        if (!overlaps.isEmpty()) {
            replace(overlaps.getFirst(), category);
        }
        insert(publicId, category.code(), category.from(), category.to(), category.limit());
    }

    private void replace(OverlapRecord old, Category replacement) {
        if (replacement.from().isBefore(old.from()) || old.to() != null
            && (replacement.to() == null || replacement.to().isAfter(old.to()))) {
            throw new ReimbursementConflictException("CATEGORY_OVERLAP");
        }
        categories.markSuperseded(old.id());
        if (old.from().isBefore(replacement.from())) {
            insert(UUID.randomUUID(), replacement.code(), old.from(), replacement.from(), old.limit());
        }
        if (replacement.to() != null && (old.to() == null || replacement.to().isBefore(old.to()))) {
            insert(UUID.randomUUID(), replacement.code(), replacement.to(), old.to(), old.limit());
        }
    }

    private void insert(UUID id, String code, LocalDate from, LocalDate to, long limit) {
        categories.insert(id, code, from, to, limit, clock.instant());
    }

    private static void validate(Category category) {
        if (category == null || !StringUtils.hasText(category.code()) || category.from() == null
            || category.to() != null && !category.from().isBefore(category.to()) || category.limit() <= 0) {
            throw new IllegalArgumentException("Invalid reimbursement category");
        }
    }

    public void verifyAuthority(Authentication caller) {
        UserAccount actor = access.requireActive(caller, clock.instant());
        if (!access.hasScopedRole(actor.getId(), HrisRole.HR_ADMIN, null, clock.instant())
            && !access.hasScopedRole(actor.getId(), HrisRole.FINANCE_PAYROLL, null, clock.instant())) {
            throw new AccessDeniedException("Organization-wide HR or Finance authority required");
        }
    }

    private static CategoryView toView(CategoryRecord record) {
        return new CategoryView(record.publicId(), record.code(), record.from(), record.to(), record.limit());
    }

    public record Category(@NotBlank @Size(max = 128) String code, @NotNull LocalDate from, LocalDate to, @Positive long limit) { }
    public record CategoryView(UUID publicId, String code, LocalDate from, LocalDate to, long limit) { }
    public record CategoryPage(List<CategoryView> items, UUID nextCursor) { }
}
