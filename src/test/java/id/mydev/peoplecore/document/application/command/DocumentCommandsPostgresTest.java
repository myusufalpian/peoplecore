package id.mydev.peoplecore.document.application.command;

import id.mydev.peoplecore.common.command.CommandConflictException;
import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.document.application.service.DocumentUploadService.Request;
import id.mydev.peoplecore.document.application.service.VerifiedDocumentAccess.Purpose;
import id.mydev.peoplecore.document.infrastructure.storage.PrivateDocumentStorage;
import id.mydev.peoplecore.identity.domain.model.HrisRole;
import id.mydev.peoplecore.identity.domain.model.RoleAssignment;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.identity.domain.repository.RoleAssignmentRepository;
import id.mydev.peoplecore.identity.domain.repository.UserAccountRepository;
import id.mydev.peoplecore.support.JwtAuthFixture;
import id.mydev.peoplecore.support.PostgresFixture;
import java.time.Instant;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@Tag("postgres")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "peoplecore.runtime.role=api", "spring.jpa.hibernate.ddl-auto=validate"
})
class DocumentCommandsPostgresTest {
    private static final String ISSUER = "https://document.postgres.test";
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

    @Autowired DocumentCommands commands;
    @Autowired UserAccountRepository accounts;
    @Autowired RoleAssignmentRepository roles;
    @Autowired JdbcTemplate jdbc;
    @Autowired PrivateDocumentStorage storage;

    @TestConfiguration
    static class StorageFixture {
        @Bean
        PrivateDocumentStorage privateDocumentStorage() {
            return mock(PrivateDocumentStorage.class);
        }
    }

    @AfterEach
    void clearCaller() {
        SecurityContextHolder.clearContext();
    }

    @AfterAll
    static void closeDatabase() {
        DATABASE.close();
    }

    @Test
    void createReplayIsIdempotentAndRequiresCurrentOwnerAuthority() {
        Instant before = Instant.now().minusSeconds(60);
        String subject = "document-" + UUID.randomUUID();
        UserAccount account = accounts.save(new UserAccount(UUID.randomUUID(), ISSUER, subject, before));
        RoleAssignment role = roles.save(new RoleAssignment(account.getId(), HrisRole.EMPLOYEE, null, before, before));
        UUID employee = UUID.randomUUID();
        Long employeeId = jdbc.queryForObject("INSERT INTO employees(public_id,employee_number,created_at) VALUES (?,?,now()) RETURNING id",
            Long.class, employee, "DOC-" + UUID.randomUUID());
        jdbc.update("INSERT INTO account_bindings(employee_id,employee_public_id,account_id,oidc_issuer,oidc_subject,created_at) "
            + "VALUES (?,?,?,?,?,now())", employeeId, employee, account.getId(), ISSUER, subject);
        Authentication caller = JwtAuthFixture.token(ISSUER, subject);
        SecurityContextHolder.getContext().setAuthentication(caller);
        String key = "document-" + UUID.randomUUID();
        Request request = new Request(employee, Purpose.CLAIM_EVIDENCE, "receipt.pdf", "application/pdf", 12);

        var first = commands.create(caller, key, request);
        assertEquals(first, commands.create(caller, key, request));
        assertEquals(1, jdbc.queryForObject("SELECT count(id) FROM private_document WHERE public_id=?", Integer.class, first.publicId()));
        assertEquals(1, jdbc.queryForObject("SELECT count(id) FROM command_receipts WHERE idempotency_key=?", Integer.class, key));
        assertThrows(CommandConflictException.class, () -> commands.create(caller, key,
            new Request(employee, Purpose.CLAIM_EVIDENCE, "changed.pdf", "application/pdf", 12)));

        jdbc.update("UPDATE role_assignments SET effective_to=now() WHERE id=?", role.getId());
        assertThrows(ResourceNotFoundException.class, () -> commands.create(caller, key, request));
    }

    @Test
    void uploadReplayKeepsBearerUrlOutOfReceiptsAndQueuesOnlyOneAttempt() throws Exception {
        Instant before = Instant.now().minusSeconds(60);
        String subject = "upload-" + UUID.randomUUID();
        UserAccount account = accounts.save(new UserAccount(UUID.randomUUID(), ISSUER, subject, before));
        roles.save(new RoleAssignment(account.getId(), HrisRole.EMPLOYEE, null, before, before));
        UUID employee = UUID.randomUUID();
        Long employeeId = jdbc.queryForObject("INSERT INTO employees(public_id,employee_number,created_at) VALUES (?,?,now()) RETURNING id",
            Long.class, employee, "UPLOAD-" + UUID.randomUUID());
        jdbc.update("INSERT INTO account_bindings(employee_id,employee_public_id,account_id,oidc_issuer,oidc_subject,created_at) "
            + "VALUES (?,?,?,?,?,now())", employeeId, employee, account.getId(), ISSUER, subject);
        Authentication caller = JwtAuthFixture.token(ISSUER, subject);
        SecurityContextHolder.getContext().setAuthentication(caller);
        var signed = mock(PresignedPutObjectRequest.class);
        when(signed.url()).thenReturn(URI.create("https://storage.example/staging?signature=private-test-token").toURL());
        when(signed.signedHeaders()).thenReturn(Map.of());
        when(signed.expiration()).thenReturn(Instant.now().plusSeconds(300));
        when(storage.presignUpload(any(), any(), any())).thenReturn(signed);
        when(storage.headStaging(any(), anyLong()))
            .thenThrow(S3Exception.builder().statusCode(404).build())
            .thenThrow(SdkClientException.create("private-test-token"))
            .thenReturn(new PrivateDocumentStorage.StagingObject(12, "test-etag"));
        UUID document = commands.create(caller, UUID.randomUUID().toString(),
            new Request(employee, Purpose.CLAIM_EVIDENCE, "receipt.pdf", "application/pdf", 12)).publicId();
        String reserveKey = UUID.randomUUID().toString();
        String finalizeKey = UUID.randomUUID().toString();

        assertEquals(commands.reserve(caller, reserveKey, document).url(), commands.reserve(caller, reserveKey, document).url());
        String missingKey = UUID.randomUUID().toString();
        String unavailableKey = UUID.randomUUID().toString();
        assertThrows(CommandConflictException.class, () -> commands.finalizeUpload(caller, missingKey, document));
        ResponseStatusException unavailable = assertThrows(ResponseStatusException.class,
            () -> commands.finalizeUpload(caller, unavailableKey, document));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, unavailable.getStatusCode());
        assertFalse(unavailable.getReason().contains("private-test-token"));
        assertEquals(0, jdbc.queryForObject("SELECT count(id) FROM command_receipts WHERE idempotency_key IN (?,?)",
            Integer.class, missingKey, unavailableKey));
        var queued = commands.finalizeUpload(caller, finalizeKey, document);
        assertEquals(queued, commands.finalizeUpload(caller, finalizeKey, document));
        assertEquals("VERIFYING", queued.status());
        assertEquals(1, jdbc.queryForObject("SELECT count(a.id) FROM document_verification_attempt a "
            + "JOIN private_document d ON d.id=a.document_id WHERE d.public_id=?", Integer.class, document));
        String receipt = jdbc.queryForObject("SELECT result_payload FROM command_receipts WHERE idempotency_key=?", String.class, reserveKey);
        assertFalse(receipt.contains("private-test-token"));
        String event = jdbc.queryForObject("SELECT payload FROM outbox_events WHERE aggregate_public_id=? "
            + "AND event_type='DOCUMENT_RESERVE_UPLOAD'", String.class, document);
        assertFalse(event.contains("private-test-token"));

        UUID secondDocument = commands.create(caller, UUID.randomUUID().toString(),
            new Request(employee, Purpose.CLAIM_EVIDENCE, "second.pdf", "application/pdf", 12)).publicId();
        commands.reserve(caller, UUID.randomUUID().toString(), secondDocument);
        doAnswer(invocation -> {
            jdbc.update("UPDATE role_assignments SET effective_to=now() WHERE account_id=?", account.getId());
            throw S3Exception.builder().statusCode(404).build();
        }).when(storage).headStaging(any(), anyLong());
        String revokedKey = UUID.randomUUID().toString();
        assertThrows(ResourceNotFoundException.class, () -> commands.finalizeUpload(caller, revokedKey, secondDocument));
        assertEquals(0, jdbc.queryForObject("SELECT count(id) FROM command_receipts WHERE idempotency_key=?", Integer.class, revokedKey));
    }
}
