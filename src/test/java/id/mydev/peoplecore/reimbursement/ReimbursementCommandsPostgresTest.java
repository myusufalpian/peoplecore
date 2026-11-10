package id.mydev.peoplecore.reimbursement;

import id.mydev.peoplecore.common.command.CommandConflictException;
import id.mydev.peoplecore.identity.domain.model.AccountBinding;
import id.mydev.peoplecore.identity.domain.model.HrisRole;
import id.mydev.peoplecore.identity.domain.model.RoleAssignment;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.identity.domain.repository.AccountBindingRepository;
import id.mydev.peoplecore.identity.domain.repository.RoleAssignmentRepository;
import id.mydev.peoplecore.identity.domain.repository.UserAccountRepository;
import id.mydev.peoplecore.organization.application.service.EmployeeService;
import id.mydev.peoplecore.reimbursement.application.command.ReimbursementCommands;
import id.mydev.peoplecore.reimbursement.application.command.ReimbursementModels.Draft;
import id.mydev.peoplecore.support.JwtAuthFixture;
import id.mydev.peoplecore.support.PostgresFixture;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;

@Tag("postgres")
@SpringBootTest(properties = {"peoplecore.runtime.role=api", "spring.jpa.hibernate.ddl-auto=validate",
    "peoplecore.organization.allowed-units=ENG"})
class ReimbursementCommandsPostgresTest {
    private static final String ISSUER = "https://reimbursement.test";
    private static final PostgresFixture DATABASE = new PostgresFixture();
    static { DATABASE.flyway().migrate(); DATABASE.grantRuntime(); }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DATABASE.url);
        registry.add("spring.datasource.username", () -> DATABASE.runtimeUsername);
        registry.add("spring.datasource.password", () -> DATABASE.runtimePassword);
    }

    @Autowired ReimbursementCommands commands;
    @Autowired EmployeeService employees;
    @Autowired UserAccountRepository accounts;
    @Autowired RoleAssignmentRepository roles;
    @Autowired AccountBindingRepository bindings;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clearCaller() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void replayIsIdempotentPrivateAndRechecksCutoff() {
        Owner owner = createOwner();
        String key = UUID.randomUUID().toString();
        Draft request = draft(owner.employeePublicId(), "Sensitive description");
        authenticate(owner.caller());
        var result = commands.create(owner.caller(), key, request);
        assertEquals(result, commands.create(owner.caller(), key, request));
        assertThrows(CommandConflictException.class, () -> commands.create(owner.caller(), key,
            draft(owner.employeePublicId(), "Changed")));
        assertEquals(1, jdbc.queryForObject("SELECT count(id) FROM reimbursement_claim WHERE public_id=?", Integer.class, result.publicId()));
        assertEquals(1, jdbc.queryForObject("SELECT count(id) FROM audit_events WHERE aggregate_public_id=? AND action='REIMBURSEMENT_CREATE'", Integer.class, result.publicId()));
        assertEquals(1, jdbc.queryForObject("SELECT count(id) FROM outbox_events WHERE aggregate_public_id=? AND event_type='REIMBURSEMENT_CREATE'", Integer.class, result.publicId()));
        String receipt = jdbc.queryForObject("SELECT result_payload FROM command_receipts WHERE idempotency_key=?", String.class, key);
        String payload = jdbc.queryForObject("SELECT payload FROM outbox_events WHERE aggregate_public_id=?", String.class, result.publicId());
        assertFalse(receipt.contains("Sensitive description"));
        assertFalse(payload.contains("Sensitive description"));
        jdbc.update("UPDATE user_accounts SET access_ends_at=? WHERE id=?", java.sql.Timestamp.from(Instant.now().minusSeconds(5)), owner.accountId());
        assertThrows(AccessDeniedException.class, () -> commands.create(owner.caller(), key, request));
    }

    @Test
    void outboxFailureRollsBackClaimHistoryAuditAndReceipt() {
        Owner owner = createOwner();
        String key = UUID.randomUUID().toString();
        authenticate(owner.caller());
        DATABASE.jdbc.execute("CREATE FUNCTION reject_reimbursement_test_event() RETURNS trigger LANGUAGE plpgsql AS $$ "
            + "BEGIN IF NEW.event_type='REIMBURSEMENT_CREATE' THEN RAISE EXCEPTION 'test outbox failure'; END IF; RETURN NEW; END $$");
        DATABASE.jdbc.execute("CREATE TRIGGER reject_reimbursement_test_event BEFORE INSERT ON outbox_events "
            + "FOR EACH ROW EXECUTE FUNCTION reject_reimbursement_test_event()");
        try {
            assertThrows(DataAccessException.class, () -> commands.create(owner.caller(), key, draft(owner.employeePublicId(), "Trip")));
            assertEquals(0, jdbc.queryForObject("SELECT count(id) FROM command_receipts WHERE idempotency_key=?", Integer.class, key));
            assertEquals(0, jdbc.queryForObject("SELECT count(id) FROM reimbursement_claim WHERE employee_id=(SELECT id FROM employees WHERE public_id=?)", Integer.class, owner.employeePublicId()));
            assertEquals(0, jdbc.queryForObject("SELECT count(h.id) FROM reimbursement_history h JOIN reimbursement_claim c ON c.id=h.claim_id "
                + "JOIN employees e ON e.id=c.employee_id WHERE e.public_id=?", Integer.class, owner.employeePublicId()));
        } finally {
            DATABASE.jdbc.execute("DROP TRIGGER reject_reimbursement_test_event ON outbox_events");
            DATABASE.jdbc.execute("DROP FUNCTION reject_reimbursement_test_event()");
        }
    }

    private Owner createOwner() {
        Instant now = Instant.now().minusSeconds(60);
        UserAccount hr = accounts.save(new UserAccount(UUID.randomUUID(), ISSUER, UUID.randomUUID().toString(), now));
        roles.save(new RoleAssignment(hr.getId(), HrisRole.HR_ADMIN, null, now, now));
        Authentication hrCaller = JwtAuthFixture.token(ISSUER, hr.getOidcSubject());
        UUID employee = employees.create(UUID.randomUUID(), "R-" + UUID.randomUUID(), hrCaller).employeeId();
        UserAccount account = accounts.save(new UserAccount(UUID.randomUUID(), ISSUER, UUID.randomUUID().toString(), now));
        roles.save(new RoleAssignment(account.getId(), HrisRole.EMPLOYEE, null, now, now));
        bindings.save(new AccountBinding(employees.requireInternalByPublicId(employee).getId(), employee, account.getId(),
            ISSUER, account.getOidcSubject(), now));
        return new Owner(employee, account.getId(), JwtAuthFixture.token(ISSUER, account.getOidcSubject()));
    }

    private Draft draft(UUID employee, String description) {
        return new Draft(employee, "TRAVEL", LocalDate.now(), 100, description, List.of(), null);
    }

    private void authenticate(Authentication caller) {
        SecurityContextHolder.getContext().setAuthentication(caller);
    }

    private record Owner(UUID employeePublicId, Long accountId, Authentication caller) { }
}
