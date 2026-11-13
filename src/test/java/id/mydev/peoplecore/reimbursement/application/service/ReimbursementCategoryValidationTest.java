package id.mydev.peoplecore.reimbursement.application.service;

import id.mydev.peoplecore.common.command.AdmissionGuard;
import id.mydev.peoplecore.identity.application.service.AccountAccessService;
import id.mydev.peoplecore.reimbursement.application.service.ReimbursementCategoryService.Category;
import id.mydev.peoplecore.reimbursement.infrastructure.persistence.JdbcReimbursementCategoryRepository;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ReimbursementCategoryValidationTest {
    @Test
    void rejectsInvalidPolicyIntervalsAndLimitsBeforePersistence() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AccountAccessService access = mock(AccountAccessService.class);
        var service = new ReimbursementCategoryService(new JdbcReimbursementCategoryRepository(jdbc), access, Clock.systemUTC(),
            mock(AdmissionGuard.class), mock(EntityManager.class));
        Authentication caller = mock(Authentication.class);
        LocalDate date = LocalDate.of(2026, 11, 1);
        assertThrows(IllegalArgumentException.class, () -> service.publish(UUID.randomUUID(), null, caller));
        assertThrows(IllegalArgumentException.class, () -> service.publish(UUID.randomUUID(), new Category(" ", date, null, 10), caller));
        assertThrows(IllegalArgumentException.class, () -> service.publish(UUID.randomUUID(), new Category("TRAVEL", null, null, 10), caller));
        assertThrows(IllegalArgumentException.class, () -> service.publish(UUID.randomUUID(), new Category("TRAVEL", date, date, 10), caller));
        assertThrows(IllegalArgumentException.class, () -> service.publish(UUID.randomUUID(), new Category("TRAVEL", date, date.minusDays(1), 10), caller));
        assertThrows(IllegalArgumentException.class, () -> service.publish(UUID.randomUUID(), new Category("TRAVEL", date, null, 0), caller));
        verifyNoInteractions(jdbc, access);
    }

    @Test
    void rejectsUnboundedOrUndatedCategoryQueriesBeforeDatabaseAccess() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var service = new ReimbursementCategoryService(new JdbcReimbursementCategoryRepository(jdbc),
            mock(AccountAccessService.class), Clock.systemUTC(),
            mock(AdmissionGuard.class), mock(EntityManager.class));
        Authentication caller = mock(Authentication.class);
        LocalDate date = LocalDate.of(2026, 11, 1);
        assertThrows(NullPointerException.class, () -> service.applicable(null, 1, null, caller));
        assertThrows(IllegalArgumentException.class, () -> service.applicable(date, 0, null, caller));
        assertThrows(IllegalArgumentException.class, () -> service.applicable(date, 101, null, caller));
        verifyNoInteractions(jdbc);
    }
}
