package id.mydev.peoplecore.reimbursement.application.service;

import id.mydev.peoplecore.identity.application.service.AccountAccessService;
import id.mydev.peoplecore.identity.domain.model.HrisRole;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.common.command.AdmissionGuard;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class ReimbursementCategoryService {
    private final JdbcTemplate jdbc;
    private final AccountAccessService access;
    private final Clock clock;
    private final AdmissionGuard admission;
    private final EntityManager entities;

    public ReimbursementCategoryService(JdbcTemplate jdbc, AccountAccessService access, Clock clock,
                                        AdmissionGuard admission, EntityManager entities) {
        this.jdbc = jdbc;
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
        List<CategoryView> rows = jdbc.query("SELECT public_id,category_code,effective_from,effective_to,claim_limit "
                + "FROM reimbursement_category_revision WHERE NOT superseded AND effective_from<=? "
                + "AND (effective_to IS NULL OR effective_to>?) AND (?::uuid IS NULL OR public_id<?::uuid) "
                + "ORDER BY public_id DESC LIMIT ?",
            (rs, row) -> new CategoryView(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, LocalDate.class),
                rs.getObject(4, LocalDate.class), rs.getLong(5)), date, date, cursor, cursor, limit + 1);
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
        String lockName = "reimbursement-category:" + category.code();
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", rs -> { }, lockName);
        List<Existing> overlaps = jdbc.query("SELECT id,effective_from,effective_to,claim_limit FROM reimbursement_category_revision "
                + "WHERE category_code=? AND NOT superseded AND (effective_to IS NULL OR effective_to>?) "
                + "AND (?::date IS NULL OR effective_from<?) ORDER BY id FOR UPDATE",
            (rs, row) -> new Existing(rs.getLong(1), rs.getObject(2, LocalDate.class), rs.getObject(3, LocalDate.class), rs.getLong(4)),
            category.code(), category.from(), category.to(), category.to());
        if (overlaps.size() > 1) {
            throw new ReimbursementConflictException("CATEGORY_OVERLAP");
        }
        verifyAuthority(caller);
        if (!overlaps.isEmpty()) {
            replace(overlaps.getFirst(), category);
        }
        insert(publicId, category.code(), category.from(), category.to(), category.limit());
    }

    private void replace(Existing old, Category replacement) {
        if (replacement.from().isBefore(old.from()) || old.to() != null
            && (replacement.to() == null || replacement.to().isAfter(old.to()))) {
            throw new ReimbursementConflictException("CATEGORY_OVERLAP");
        }
        jdbc.update("UPDATE reimbursement_category_revision SET superseded=TRUE WHERE id=?", old.id());
        if (old.from().isBefore(replacement.from())) {
            insert(UUID.randomUUID(), replacement.code(), old.from(), replacement.from(), old.limit());
        }
        if (replacement.to() != null && (old.to() == null || replacement.to().isBefore(old.to()))) {
            insert(UUID.randomUUID(), replacement.code(), replacement.to(), old.to(), old.limit());
        }
    }

    private void insert(UUID id, String code, LocalDate from, LocalDate to, long limit) {
        jdbc.update("INSERT INTO reimbursement_category_revision(public_id,category_code,effective_from,effective_to,claim_limit,created_at) VALUES (?,?,?,?,?,?)",
            id, code, from, to, limit, Timestamp.from(clock.instant()));
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

    public record Category(@NotBlank @Size(max = 128) String code, @NotNull LocalDate from, LocalDate to, @Positive long limit) { }
    public record CategoryView(UUID publicId, String code, LocalDate from, LocalDate to, long limit) { }
    public record CategoryPage(List<CategoryView> items, UUID nextCursor) { }
    private record Existing(long id, LocalDate from, LocalDate to, long limit) { }
}
