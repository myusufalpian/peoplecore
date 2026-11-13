package id.mydev.peoplecore.reimbursement.application.service;

import id.mydev.peoplecore.reimbursement.infrastructure.persistence.JdbcReimbursementWorkflowRepository;
import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.common.command.AdmissionGuard;
import id.mydev.peoplecore.document.application.service.DocumentTarget;
import id.mydev.peoplecore.identity.application.service.AccountAccessService;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.organization.application.service.EmployeeService;
import id.mydev.peoplecore.organization.domain.model.Employee;
import id.mydev.peoplecore.support.PostgresFixture;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("postgres")
class ReimbursementEvidenceContentPostgresTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-10T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void onlyEvidenceLinkedToTheCurrentClaimRevisionIsReadable() {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            JdbcTemplate jdbc = fixture.jdbc;
            UUID employeePublicId = UUID.randomUUID();
            long employeeId = employee(jdbc, employeePublicId);
            Employee employee = new Employee(employeePublicId, "REIM-1", CLOCK.instant());
            ReflectionTestUtils.setField(employee, "id", employeeId);
            Authentication caller = mock(Authentication.class);
            AccountAccessService access = mock(AccountAccessService.class);
            EmployeeService employees = mock(EmployeeService.class);
            UserAccount account = mock(UserAccount.class);
            when(account.getId()).thenReturn(1L);
            when(account.getPublicId()).thenReturn(UUID.randomUUID());
            when(access.requireActive(eq(caller), any())).thenReturn(account);
            when(access.hasScopedRole(any(), any(), any(), any())).thenReturn(false);
            when(employees.requireInternalByPublicId(employeePublicId)).thenReturn(employee);
            when(employees.currentOrgUnit(eq(employeeId), any())).thenReturn(Optional.of("HQ"));
            var workflow = new ReimbursementWorkflowService(new JdbcReimbursementWorkflowRepository(jdbc), employees, access,
                mock(ReimbursementSubmissionService.class), mock(ReimbursementCategoryService.class),
                mock(EntityManager.class), mock(AdmissionGuard.class), CLOCK);
            ClaimFixture claim = claim(jdbc, employeeId, document(jdbc, employeeId));
            UUID unrelated = document(jdbc, employeeId).publicId();

            DocumentTarget target =
                workflow.requireEvidenceContent(claim.claimPublicId(), claim.documentPublicId(), caller);

            assertEquals(claim.documentPublicId(), target.documentPublicId());
            assertEquals(claim.versionPublicId(), target.versionPublicId());
            assertEquals(employeePublicId, target.employeePublicId());
            assertThrows(ResourceNotFoundException.class,
                () -> workflow.requireEvidenceContent(claim.claimPublicId(), unrelated, caller));

            doThrow(new ResourceNotFoundException()).when(access)
                .requireEmployeeRead(any(), eq(employeePublicId), any(), any());
            assertThrows(ResourceNotFoundException.class,
                () -> workflow.requireEvidenceContent(claim.claimPublicId(), claim.documentPublicId(), caller));
        }
    }

    @Test
    void sealedEvidenceCannotBeClaimedForDeletion() {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            JdbcTemplate jdbc = fixture.jdbc;
            UUID employeePublicId = UUID.randomUUID();
            long employeeId = employee(jdbc, employeePublicId);
            Employee employee = new Employee(employeePublicId, "REIM-2", CLOCK.instant());
            ReflectionTestUtils.setField(employee, "id", employeeId);
            Authentication caller = mock(Authentication.class);
            AccountAccessService access = mock(AccountAccessService.class);
            EmployeeService employees = mock(EmployeeService.class);
            UserAccount account = mock(UserAccount.class);
            when(account.getId()).thenReturn(1L);
            when(account.getPublicId()).thenReturn(UUID.randomUUID());
            when(access.requireActive(eq(caller), any())).thenReturn(account);
            when(employees.requireInternalByPublicId(employeePublicId)).thenReturn(employee);
            when(employees.currentOrgUnit(eq(employeeId), any())).thenReturn(Optional.of("HQ"));
            var workflow = new ReimbursementWorkflowService(new JdbcReimbursementWorkflowRepository(jdbc), employees, access,
                mock(ReimbursementSubmissionService.class), mock(ReimbursementCategoryService.class),
                mock(EntityManager.class), mock(AdmissionGuard.class), CLOCK);
            ClaimFixture claim = claim(jdbc, employeeId, document(jdbc, employeeId));

            assertThrows(DataAccessException.class, () -> jdbc.update(
                "UPDATE private_document SET deletion_state='CLAIMED',deletion_requested_at=now(),"
                    + "deletion_restore_deadline=now()+interval '30 days' WHERE public_id=?",
                claim.documentPublicId()));
            assertDoesNotThrow(() ->
                workflow.requireEvidenceContent(claim.claimPublicId(), claim.documentPublicId(), caller));
        }
    }

    private static long employee(JdbcTemplate jdbc, UUID publicId) {
        return jdbc.queryForObject("INSERT INTO employees(public_id,employee_number,created_at) VALUES (?,?,now()) RETURNING id",
            Long.class, publicId, "REIM-" + UUID.randomUUID());
    }

    private static ClaimFixture claim(JdbcTemplate jdbc, long employeeId, DocumentFixture document) {
        long category = jdbc.queryForObject("INSERT INTO reimbursement_category_revision(public_id,category_code,"
                + "effective_from,claim_limit,created_at) VALUES (gen_random_uuid(),'TRAVEL',date '2026-01-01',100,now()) RETURNING id",
            Long.class);
        UUID claimPublicId = UUID.randomUUID();
        long claim = jdbc.queryForObject("INSERT INTO reimbursement_claim(public_id,employee_id,status,revision,version,"
                + "category_code,transaction_date,amount,description,created_at) "
                + "VALUES (?,?,'DRAFT',0,0,'TRAVEL',date '2026-03-01',50,'Trip',now()) RETURNING id",
            Long.class, claimPublicId, employeeId);
        jdbc.update("INSERT INTO reimbursement_revision(claim_id,revision,category_revision_id,transaction_date,amount,"
                + "description,org_unit,submitted_at,sealed) VALUES (?,1,?,date '2026-03-01',50,'Trip','HQ',now(),false)",
            claim, category);
        jdbc.update("INSERT INTO reimbursement_evidence(claim_id,revision,document_version_id) VALUES (?,1,?)",
            claim, document.versionId());
        jdbc.update("UPDATE reimbursement_revision SET sealed=true WHERE claim_id=?", claim);
        jdbc.update("UPDATE reimbursement_claim SET status='PENDING',revision=1,version=1 WHERE id=?", claim);
        return new ClaimFixture(claimPublicId, document.publicId(), document.versionPublicId());
    }

    private static DocumentFixture document(JdbcTemplate jdbc, long employeeId) {
        UUID publicId = UUID.randomUUID();
        UUID versionPublicId = UUID.randomUUID();
        long document = jdbc.queryForObject("INSERT INTO private_document(public_id,employee_id,purpose,status,file_name,"
                + "declared_media_type,declared_size,current_generation,created_at,updated_at) "
                + "VALUES (?,?,'CLAIM_EVIDENCE','VERIFYING','receipt.pdf','application/pdf',1,1,now(),now()) RETURNING id",
            Long.class, publicId, employeeId);
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
                + "VALUES (?,?,?,?,'application/pdf',1,repeat('a',64),'test',now()) RETURNING id",
            Long.class, versionPublicId, document, attempt, token);
        jdbc.update("UPDATE private_document SET status='VERIFIED',current_version_id=? WHERE id=?", version, document);
        return new DocumentFixture(publicId, version, versionPublicId);
    }

    private record DocumentFixture(UUID publicId, long versionId, UUID versionPublicId) { }

    private record ClaimFixture(UUID claimPublicId, UUID documentPublicId, UUID versionPublicId) { }
}
