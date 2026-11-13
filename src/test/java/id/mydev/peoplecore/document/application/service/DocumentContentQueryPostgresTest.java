package id.mydev.peoplecore.document.application.service;

import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.document.infrastructure.persistence.JdbcDocumentContentRepository;
import id.mydev.peoplecore.document.application.service.DocumentTarget;
import id.mydev.peoplecore.identity.application.service.AccountAccessService;
import id.mydev.peoplecore.identity.domain.model.HrisRole;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.support.PostgresFixture;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("postgres")
class DocumentContentQueryPostgresTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-10T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void onlyTheOwnerCurrentVerifiedClaimEvidenceIsStreamable() {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            JdbcTemplate jdbc = fixture.jdbc;
            Authentication caller = mock(Authentication.class);
            AccountAccessService access = mock(AccountAccessService.class);
            UserAccount account = mock(UserAccount.class);
            when(account.getId()).thenReturn(1L);
            when(access.requireActive(eq(caller), any())).thenReturn(account);
            when(access.activeRoles(eq(1L), any())).thenReturn(List.of(HrisRole.EMPLOYEE));
            UUID owner = UUID.randomUUID();
            UUID stranger = UUID.randomUUID();
            long employee = employee(jdbc, owner);
            employee(jdbc, stranger);
            UUID evidence = verifiedDocument(jdbc, employee, "CLAIM_EVIDENCE");
            UUID payslip = verifiedDocument(jdbc, employee, "PAYSLIP");
            var query = new DocumentContentQuery(new JdbcDocumentContentRepository(jdbc), access, CLOCK);

            when(access.ownEmployeePublicId(account)).thenReturn(Optional.of(owner));
            DocumentTarget target = query.requireOwnedClaimEvidence(evidence, caller);
            assertEquals(evidence, target.documentPublicId());
            assertEquals("application/pdf", target.mediaType());

            assertThrows(ResourceNotFoundException.class, () -> query.requireOwnedClaimEvidence(payslip, caller));
            when(access.ownEmployeePublicId(account)).thenReturn(Optional.of(stranger));
            assertThrows(ResourceNotFoundException.class, () -> query.requireOwnedClaimEvidence(evidence, caller));
            when(access.ownEmployeePublicId(account)).thenReturn(Optional.empty());
            assertThrows(ResourceNotFoundException.class, () -> query.requireOwnedClaimEvidence(evidence, caller));

            when(access.ownEmployeePublicId(account)).thenReturn(Optional.of(owner));
            jdbc.update("UPDATE private_document SET deletion_state='CLAIMED',deletion_requested_at=now(),deletion_restore_deadline=now()+interval '30 days' WHERE public_id=?", evidence);
            assertThrows(ResourceNotFoundException.class, () -> query.requireOwnedClaimEvidence(evidence, caller));
            jdbc.update("UPDATE private_document SET deletion_state='LIVE',status='REJECTED' WHERE public_id=?", evidence);
            assertThrows(ResourceNotFoundException.class, () -> query.requireOwnedClaimEvidence(evidence, caller));
        }
    }

    private static long employee(JdbcTemplate jdbc, UUID publicId) {
        return jdbc.queryForObject("INSERT INTO employees(public_id,employee_number,created_at) VALUES (?,?,now()) RETURNING id",
            Long.class, publicId, "CONTENT-" + UUID.randomUUID());
    }

    private static UUID verifiedDocument(JdbcTemplate jdbc, long employee, String purpose) {
        UUID publicId = UUID.randomUUID();
        long document = jdbc.queryForObject("INSERT INTO private_document(public_id,employee_id,purpose,status,file_name,"
                + "declared_media_type,declared_size,current_generation,created_at,updated_at) "
                + "VALUES (?,?,?,'VERIFYING','receipt.pdf','application/pdf',1,1,now(),now()) RETURNING id",
            Long.class, publicId, employee, purpose);
        UUID uploadPublicId = UUID.randomUUID();
        long upload = jdbc.queryForObject("INSERT INTO document_upload_attempt(public_id,document_id,generation,staging_key,"
                + "byte_size,status,created_at) VALUES (?,?,1,?,1,'RESERVED',now()) RETURNING id",
            Long.class, uploadPublicId, document, "staging/" + uploadPublicId);
        jdbc.update("UPDATE document_upload_attempt SET status='COMPLETED',object_etag='etag',completed_at=now() WHERE id=?", upload);
        jdbc.update("UPDATE private_document SET current_upload_id=?,current_upload_generation=1 WHERE id=?", upload, document);
        UUID attemptPublicId = UUID.randomUUID();
        long attempt = jdbc.queryForObject("INSERT INTO document_verification_attempt(public_id,document_id,upload_attempt_id,"
                + "generation,candidate_key,status,created_at) VALUES (?,?,?,1,?,'QUEUED',now()) RETURNING id", Long.class,
            attemptPublicId, document, upload, "candidates/" + attemptPublicId);
        UUID token = UUID.randomUUID();
        jdbc.update("UPDATE document_verification_attempt SET status='CLAIMED',lease_token=?,"
            + "lease_expires_at=now()+interval '5 minutes' WHERE id=?", token, attempt);
        jdbc.update("UPDATE document_verification_attempt SET copy_started_at=clock_timestamp() WHERE id=?", attempt);
        jdbc.update("UPDATE document_verification_attempt SET copy_completed_at=clock_timestamp() WHERE id=?", attempt);
        jdbc.update("UPDATE document_verification_attempt SET status='CLEAN',completed_at=now() WHERE id=?", attempt);
        long version = jdbc.queryForObject("INSERT INTO document_version(public_id,document_id,attempt_id,lease_token,"
                + "media_type,byte_size,sha256,scanner_version,verified_at) "
                + "VALUES (gen_random_uuid(),?,?,?,'application/pdf',1,repeat('a',64),'test',now()) RETURNING id",
            Long.class, document, attempt, token);
        jdbc.update("UPDATE private_document SET status='VERIFIED',current_version_id=? WHERE id=?", version, document);
        return publicId;
    }
}
