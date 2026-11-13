package id.mydev.peoplecore.document.application.command;

import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.common.command.CommandConflictException;
import id.mydev.peoplecore.document.application.service.DocumentHoldService.Hold;
import id.mydev.peoplecore.identity.domain.model.HrisRole;
import id.mydev.peoplecore.identity.domain.model.RoleAssignment;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.identity.domain.repository.RoleAssignmentRepository;
import id.mydev.peoplecore.identity.domain.repository.UserAccountRepository;
import id.mydev.peoplecore.support.JwtAuthFixture;
import id.mydev.peoplecore.support.PostgresFixture;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("postgres")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "peoplecore.runtime.role=api", "spring.jpa.hibernate.ddl-auto=validate"
})
class DocumentHoldCommandsPostgresTest {
    private static final String ISSUER = "https://hold.postgres.test";
    private static final PostgresFixture DATABASE = new PostgresFixture();

    static {
        DATABASE.flyway().migrate();
        DATABASE.grantRuntime();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DATABASE.url);
        registry.add("spring.datasource.username", () -> DATABASE.runtimeUsername);
        registry.add("spring.datasource.password", () -> DATABASE.runtimePassword);
    }

    @Autowired DocumentHoldCommands commands;
    @Autowired UserAccountRepository accounts;
    @Autowired RoleAssignmentRepository roles;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clearCaller() {
        SecurityContextHolder.clearContext();
    }

    @AfterAll
    static void closeDatabase() {
        DATABASE.close();
    }

    @Test
    void holdCommandIsIdempotentAuditedAndReleasableOnlyOnce() {
        Instant before = Instant.now().minusSeconds(60);
        String subject = "holder-" + UUID.randomUUID();
        UserAccount account = accounts.save(new UserAccount(UUID.randomUUID(), ISSUER, subject, before));
        RoleAssignment role = roles.save(new RoleAssignment(account.getId(), HrisRole.HR_ADMIN, null, before, before));
        long employee = jdbc.queryForObject("INSERT INTO employees(public_id,employee_number,created_at) "
            + "VALUES (gen_random_uuid(),?,now()) RETURNING id", Long.class, "HOLD-" + UUID.randomUUID());
        UUID document = UUID.randomUUID();
        jdbc.update("INSERT INTO private_document(public_id,employee_id,purpose,status,file_name,declared_media_type,"
                + "declared_size,current_generation,created_at,updated_at) "
                + "VALUES (?,?,'CLAIM_EVIDENCE','PENDING_UPLOAD','receipt.pdf','application/pdf',1,0,now(),now())",
            document, employee);
        Authentication caller = JwtAuthFixture.token(ISSUER, subject);
        SecurityContextHolder.getContext().setAuthentication(caller);
        String key = "hold-" + UUID.randomUUID();

        Hold placed = commands.place(caller, key, document, "Litigation hold");
        Hold replay = commands.place(caller, key, document, "Litigation hold");

        assertEquals(placed.publicId(), replay.publicId());
        assertEquals(1, count("SELECT count(id) FROM document_hold"));
        assertEquals(1, count("SELECT count(id) FROM command_receipts WHERE idempotency_key='" + key + "'"));
        assertEquals(1, count("SELECT count(id) FROM audit_events WHERE action='DOCUMENT_HOLD_PLACE' "
            + "AND aggregate_public_id='" + placed.publicId() + "'"));

        String releaseKey = "release-" + UUID.randomUUID();
        Hold released = commands.release(caller, releaseKey, document, placed.publicId(), "Matter closed");

        assertNotNull(released.releasedAt());
        assertThrows(CommandConflictException.class,
            () -> commands.release(caller, UUID.randomUUID().toString(), document, placed.publicId(), "Again"));

        jdbc.update("UPDATE role_assignments SET effective_to=now() WHERE id=?", role.getId());
        assertThrows(ResourceNotFoundException.class,
            () -> commands.place(caller, UUID.randomUUID().toString(), document, "After revocation"));
        assertEquals(1, count("SELECT count(id) FROM document_hold"));
    }

    private int count(String sql) {
        Integer count = jdbc.queryForObject(sql, Integer.class);
        return count == null ? 0 : count;
    }
}
