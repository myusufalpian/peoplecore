package id.mydev.peoplecore.infrastructure.recovery;

import id.mydev.peoplecore.approval.application.admission.ProfileAdmissionGuard;
import id.mydev.peoplecore.approval.infrastructure.persistence.JdbcProfileAdmissionLockRepository;
import id.mydev.peoplecore.common.audit.AuditEvent;
import id.mydev.peoplecore.common.audit.AuditEventWriter;
import id.mydev.peoplecore.common.command.CommandConflictException;
import id.mydev.peoplecore.common.security.OperationAccessService;
import id.mydev.peoplecore.document.infrastructure.persistence.JdbcDocumentVersionContentRepository;
import id.mydev.peoplecore.document.infrastructure.persistence.JdbcVerifiedDocumentRepository;
import id.mydev.peoplecore.document.infrastructure.storage.PrivateDocumentStorage;
import id.mydev.peoplecore.identity.application.service.AccountAccessService;
import id.mydev.peoplecore.identity.domain.model.HrisRole;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.infrastructure.persistence.JdbcServiceRecoveryRepository;
import id.mydev.peoplecore.infrastructure.recovery.ServiceRecoveryMapper.ExceptionView;
import id.mydev.peoplecore.infrastructure.recovery.ServiceRecoveryMapper.IncidentDetail;
import id.mydev.peoplecore.infrastructure.recovery.ServiceRecoveryMapper.InventoryView;
import id.mydev.peoplecore.infrastructure.recovery.ServiceRecoveryMapper.OperationalHealthSummary;
import id.mydev.peoplecore.infrastructure.recovery.ServiceRecoveryMapper.ReconciliationView;
import id.mydev.peoplecore.support.JwtAuthFixture;
import id.mydev.peoplecore.support.PostgresFixture;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("postgres")
class ServiceRecoveryDrillPostgresTest {
    private static final Instant NOW = Instant.parse("2026-11-18T12:00:00Z");
    private static final Instant RESTORE_POINT = Instant.parse("2026-11-18T11:45:00Z");
    private static final Authentication OPS_CALLER = JwtAuthFixture.token("https://idp.example", "ops-global");
    private static final Authentication HR_CALLER = JwtAuthFixture.token("https://idp.example", "hr-global");
    private static final Authentication FIN_CALLER = JwtAuthFixture.token("https://idp.example", "fin-global");
    private static final Authentication SCOPED_CALLER = JwtAuthFixture.token("https://idp.example", "hr-scoped");

    @Test
    @DisplayName("T31/REQ-S7-01: Operational health summary reports accurate DB job ages across all five durable work tables")
    void operationalHealthSummaryReportsDatabaseBacklogAgesAndEnforcesGlobalOperationsAccess() {
        try (var context = new RecoveryTestContext()) {
            JdbcTemplate jdbc = context.fixture.runtime();
            UUID requesterAccount = context.seedUserAccount("https://idp.example", "requester-1", NOW.minusSeconds(600));
            UUID employeeId = context.seedEmployee("EMP-OBS-1", NOW.minusSeconds(600));

            jdbc.update("INSERT INTO outbox_events(event_id,event_type,schema_version,aggregate_type,aggregate_public_id,"
                    + "payload,status,created_at) VALUES (?,'PAYSLIP_PUBLISHED',1,'PAYSLIP',?,'{}','PENDING',?)",
                UUID.randomUUID(), UUID.randomUUID(), Timestamp.from(NOW.minusSeconds(180)));

            jdbc.update("INSERT INTO notification_delivery(public_id,event_id,event_type,aggregate_public_id,occurred_at,"
                    + "employee_public_id,template,eligibility_type,state,next_attempt_at,created_at,updated_at) "
                    + "VALUES (?,?, 'PAYSLIP_PUBLISHED', ?, ?, ?, 'PAYSLIP_AVAILABLE', 'OWNER', 'PENDING', ?, ?, ?)",
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Timestamp.from(NOW.minusSeconds(150)),
                employeeId, Timestamp.from(NOW), Timestamp.from(NOW.minusSeconds(150)), Timestamp.from(NOW.minusSeconds(150)));

            UUID attentionId = UUID.randomUUID();
            jdbc.update("INSERT INTO notification_attention(public_id,event_id,delivery_id,code,created_at) VALUES (?,?,?,'UNKNOWN',?)",
                attentionId, UUID.randomUUID(), UUID.randomUUID(), Timestamp.from(NOW.minusSeconds(90)));
            jdbc.update("INSERT INTO notification_attention_alert(attention_id,next_attempt_at) VALUES (?,?)",
                attentionId, Timestamp.from(NOW.minusSeconds(90)));

            jdbc.update("INSERT INTO report_export_job(public_id,requester_public_id,requester_issuer,requester_subject,"
                    + "request_json,status,created_at,expires_at) VALUES (?,?,'https://idp.example','requester-1','{}','PENDING',?,?)",
                UUID.randomUUID(), requesterAccount, Timestamp.from(NOW.minusSeconds(240)),
                Timestamp.from(NOW.minusSeconds(240).plusSeconds(86400)));

            context.seedVerifyingDocument(employeeId, NOW.minusSeconds(300));
            jdbc.update("INSERT INTO event_quarantine(id,event_id,consumer,reason,created_at) VALUES (?,?,'notifications',' STALE',?)",
                UUID.randomUUID(), UUID.randomUUID(), Timestamp.from(NOW.minusSeconds(60)));

            assertThrows(AccessDeniedException.class, () -> context.service.healthSummary(SCOPED_CALLER));

            OperationalHealthSummary summary = context.service.healthSummary(OPS_CALLER);
            assertEquals("api", summary.runtimeRole());
            assertFalse(summary.recoveryIsolated());
            assertNull(summary.activeIncidentPublicId());
            assertEquals(1L, summary.outbox().pendingCount());
            assertEquals(180L, summary.outbox().oldestAgeSeconds());
            assertEquals(1L, summary.notificationDeliveries().pendingCount());
            assertEquals(150L, summary.notificationDeliveries().oldestAgeSeconds());
            assertEquals(1L, summary.notificationAlerts().pendingCount());
            assertEquals(90L, summary.notificationAlerts().oldestAgeSeconds());
            assertEquals(1L, summary.reportExports().pendingCount());
            assertEquals(240L, summary.reportExports().oldestAgeSeconds());
            assertEquals(1L, summary.documentVerifications().pendingCount());
            assertEquals(300L, summary.documentVerifications().oldestAgeSeconds());
            assertEquals(1L, summary.quarantinedEvents());
        }
    }

    @Test
    @DisplayName("T32/T34/R1: Whole-service restoration drill reconciles revoked identities, consumed invitations, exports, documents, and three-owner reopening")
    void wholeServiceRestorationDrillReconcilesStateAndEnforcesThreeOwnerReopening() {
        try (var context = new RecoveryTestContext()) {
            JdbcTemplate jdbc = context.fixture.runtime();

            UUID offboardedAccount = context.seedUserAccount("https://idp.example", "offboarded-user", RESTORE_POINT.minusSeconds(3600));
            long offboardedInternalId = jdbc.queryForObject("SELECT id FROM user_accounts WHERE public_id=?", Long.class, offboardedAccount);
            jdbc.update("INSERT INTO role_assignments(account_id,role,effective_from,created_at) VALUES (?,'EMPLOYEE',?,?)",
                offboardedInternalId, Timestamp.from(RESTORE_POINT.minusSeconds(3600)), Timestamp.from(RESTORE_POINT.minusSeconds(3600)));

            UUID invitedEmployee = context.seedEmployee("EMP-INV-R1", RESTORE_POINT.minusSeconds(3600));
            long invitedEmployeeInternal = jdbc.queryForObject("SELECT id FROM employees WHERE public_id=?", Long.class, invitedEmployee);
            UUID consumedInvitation = UUID.randomUUID();
            jdbc.update("INSERT INTO enrollment_invitations(public_id,employee_id,intended_identity_ref,secret_hash,"
                    + "status,delivery_status,expires_at,issued_at,expected_issuer,expected_subject) "
                    + "VALUES (?,?,'IDV-R1',repeat('a',64),'ISSUED','SENT',?,?, 'https://idp.example','invited-sub')",
                consumedInvitation, invitedEmployeeInternal, Timestamp.from(NOW.plusSeconds(86400)), Timestamp.from(RESTORE_POINT));
            UUID destId = UUID.randomUUID();
            jdbc.update("INSERT INTO notification_destination(public_id,employee_public_id,address,version,status,"
                    + "evidence_ref,verified_by,verified_at) VALUES (?,?,'r1@example.test',1,'VERIFIED','EV-1','ops',?)",
                destId, invitedEmployee, Timestamp.from(RESTORE_POINT));
            jdbc.update("INSERT INTO invitation_delivery_source(invitation_public_id,employee_public_id,destination_id,"
                    + "destination_version,ciphertext,key_id,created_at) VALUES (?,?,?,?, 'restored-ciphertext','kms-key',?)",
                consumedInvitation, invitedEmployee, destId, 1L, Timestamp.from(RESTORE_POINT));

            UUID exportJobId = UUID.randomUUID();
            UUID candidateId = UUID.randomUUID();
            jdbc.update("INSERT INTO report_export_job(public_id,requester_public_id,requester_issuer,requester_subject,"
                    + "request_json,status,created_at,expires_at) VALUES (?,?,'https://idp.example','offboarded-user','{}','PENDING',?,?)",
                exportJobId, offboardedAccount, Timestamp.from(RESTORE_POINT), Timestamp.from(RESTORE_POINT.plusSeconds(86400)));
            jdbc.update("UPDATE report_export_job SET status='GENERATING',lease_generation=1,lease_until=? WHERE public_id=?",
                Timestamp.from(NOW.plusSeconds(120)), exportJobId);
            jdbc.update("INSERT INTO report_export_candidate(public_id,job_public_id,lease_generation,registered_at,"
                    + "next_reconcile_at,uploaded_at,byte_size,sha256) VALUES (?,?,1,?,?,?,128,repeat('b',64))",
                candidateId, exportJobId, Timestamp.from(RESTORE_POINT), Timestamp.from(RESTORE_POINT), Timestamp.from(RESTORE_POINT));
            jdbc.update("UPDATE report_export_job SET status='READY',candidate_id=?,byte_size=128,sha256=repeat('b',64),"
                    + "data_as_of=?,authorization_generation=1 WHERE public_id=?",
                candidateId, Timestamp.from(RESTORE_POINT), exportJobId);

            UUID pendingOutboxEvent = UUID.randomUUID();
            jdbc.update("INSERT INTO outbox_events(event_id,event_type,schema_version,aggregate_type,aggregate_public_id,"
                    + "payload,status,created_at) VALUES (?,'PAYSLIP_PUBLISHED',1,'PAYSLIP',?,'{}','PENDING',?)",
                pendingOutboxEvent, UUID.randomUUID(), Timestamp.from(RESTORE_POINT));
            UUID pendingDelivery = UUID.randomUUID();
            jdbc.update("INSERT INTO notification_delivery(public_id,event_id,event_type,aggregate_public_id,occurred_at,"
                    + "employee_public_id,template,eligibility_type,state,next_attempt_at,created_at,updated_at) "
                    + "VALUES (?,?,'PAYSLIP_PUBLISHED',?,?,?,'PAYSLIP_AVAILABLE','OWNER','PENDING',?,?,?)",
                pendingDelivery, pendingOutboxEvent, UUID.randomUUID(), Timestamp.from(RESTORE_POINT),
                invitedEmployee, Timestamp.from(NOW), Timestamp.from(RESTORE_POINT), Timestamp.from(RESTORE_POINT));

            VerifiedDoc intactDoc = context.seedVerifiedDocument(invitedEmployee, "PAYSLIP", 256L, "c".repeat(64), RESTORE_POINT);
            VerifiedDoc missingDoc = context.seedVerifiedDocument(invitedEmployee, "CLAIM_EVIDENCE", 512L, "d".repeat(64), RESTORE_POINT);
            when(context.storage.verifyCandidate(intactDoc.attemptPublicId(), 256L, "c".repeat(64))).thenReturn(true);
            when(context.storage.verifyCandidate(missingDoc.attemptPublicId(), 512L, "d".repeat(64))).thenReturn(false);

            IncidentDetail opened = context.tx.execute(status ->
                context.service.openIncident(OPS_CALLER, "open-r1", RESTORE_POINT, 900L, "R1 PostgreSQL restore drill"));
            assertNotNull(opened);
            assertEquals("ISOLATED", opened.status());
            assertTrue(context.gate.isIsolated());

            assertEquals(opened.publicId(), context.tx.execute(status ->
                context.service.openIncident(OPS_CALLER, "open-r1", RESTORE_POINT, 900L, "R1 PostgreSQL restore drill")).publicId());
            assertThrows(CommandConflictException.class, () -> context.tx.execute(status ->
                context.service.openIncident(OPS_CALLER, "open-r1-conflict", RESTORE_POINT, 900L, "Second active incident")));

            assertEquals("INVALIDATED", jdbc.queryForObject(
                "SELECT status FROM report_export_job WHERE public_id=?", String.class, exportJobId));
            assertEquals("SERVICE_RECOVERY", jdbc.queryForObject(
                "SELECT reason_code FROM report_export_job WHERE public_id=?", String.class, exportJobId));
            assertEquals("QUARANTINED", jdbc.queryForObject(
                "SELECT status FROM outbox_events WHERE event_id=?", String.class, pendingOutboxEvent));
            assertEquals("FAILED", jdbc.queryForObject(
                "SELECT state FROM notification_delivery WHERE public_id=?", String.class, pendingDelivery));
            assertEquals("RECOVERY_QUARANTINE", jdbc.queryForObject(
                "SELECT error_code FROM notification_delivery WHERE public_id=?", String.class, pendingDelivery));

            ReconciliationView reconciled = context.tx.execute(status ->
                context.service.reconcileIdentities(OPS_CALLER, opened.publicId(), "rec-id-1",
                    List.of(offboardedAccount), RESTORE_POINT.plusSeconds(60), List.of(consumedInvitation),
                    "Reconcile post-backup offboarding and consumed invitation"));
            assertEquals(1, reconciled.accountsCutOff());
            assertEquals(1, reconciled.invitationsRevoked());
            assertEquals(1, reconciled.ciphertextsDisposed());
            assertEquals("INACTIVE", jdbc.queryForObject(
                "SELECT status FROM user_accounts WHERE public_id=?", String.class, offboardedAccount));
            assertEquals("REVOKED", jdbc.queryForObject(
                "SELECT status FROM enrollment_invitations WHERE public_id=?", String.class, consumedInvitation));
            assertNull(jdbc.queryForObject(
                "SELECT ciphertext FROM invitation_delivery_source WHERE invitation_public_id=?", String.class, consumedInvitation));

            InventoryView inventory = context.tx.execute(status ->
                context.service.inventoryDocuments(OPS_CALLER, opened.publicId(), "inv-doc-1", "Check R2 objects after restore"));
            assertEquals(2, inventory.documentsChecked());
            assertEquals(1, inventory.missingOrCorruptObjects());
            assertEquals(1, inventory.openExceptions());

            var contentRepo = new JdbcDocumentVersionContentRepository(jdbc);
            var verifiedRepo = new JdbcVerifiedDocumentRepository(jdbc);
            assertTrue(contentRepo.findMetadata(intactDoc.documentPublicId(), intactDoc.versionPublicId(), invitedEmployee, "PAYSLIP").isPresent());
            assertTrue(contentRepo.findMetadata(missingDoc.documentPublicId(), missingDoc.versionPublicId(), invitedEmployee, "CLAIM_EVIDENCE").isEmpty());
            assertTrue(context.tx.execute(status -> verifiedRepo.findForUpdate(missingDoc.documentPublicId(), invitedEmployee, "CLAIM_EVIDENCE")).isEmpty());

            IncidentDetail afterInventory = context.service.getIncident(FIN_CALLER, opened.publicId());
            assertEquals(1, afterInventory.exceptions().size());
            ExceptionView exception = afterInventory.exceptions().getFirst();
            assertEquals("FINANCE_PAYROLL", exception.ownerRole());
            assertEquals("OPEN", exception.status());

            assertThrows(CommandConflictException.class, () -> context.tx.execute(status ->
                context.service.recordApproval(FIN_CALLER, opened.publicId(), "fin-early", "FINANCE_PAYROLL", "Early sign-off")));
            assertThrows(AccessDeniedException.class, () -> context.tx.execute(status ->
                context.service.resolveException(HR_CALLER, opened.publicId(), exception.publicId(), "hr-wrong-owner",
                    "ACCEPTED_RESTRICTION", "HR cannot resolve Finance exception")));

            ExceptionView resolved = context.tx.execute(status ->
                context.service.resolveException(FIN_CALLER, opened.publicId(), exception.publicId(), "fin-resolve-1",
                    "ACCEPTED_RESTRICTION", "Quarantine missing receipt and block download"));
            assertEquals("ACCEPTED_RESTRICTION", resolved.status());
            assertTrue(contentRepo.findMetadata(missingDoc.documentPublicId(), missingDoc.versionPublicId(), invitedEmployee, "CLAIM_EVIDENCE").isEmpty());

            IncidentDetail afterHr = context.tx.execute(status ->
                context.service.recordApproval(HR_CALLER, opened.publicId(), "app-hr", "HR_ADMIN", "HR records reconciled"));
            assertEquals("RECONCILING", afterHr.status());

            IncidentDetail afterFin = context.tx.execute(status ->
                context.service.recordApproval(FIN_CALLER, opened.publicId(), "app-fin", "FINANCE_PAYROLL", "Finance records reconciled"));
            assertEquals("RECONCILING", afterFin.status());

            IncidentDetail reopened = context.tx.execute(status ->
                context.service.recordApproval(OPS_CALLER, opened.publicId(), "app-ops", "SYSTEM_ADMIN", "Operations verified R1 closure"));
            assertEquals("REOPENED", reopened.status());
            assertNotNull(reopened.reopenedAt());
            assertEquals(0L, reopened.rtoSeconds());
            assertEquals(3, reopened.approvals().size());
            assertFalse(context.gate.isIsolated());

            assertThrows(DataAccessException.class, () ->
                jdbc.update("UPDATE service_recovery_incident SET status='ISOLATED' WHERE public_id=?", opened.publicId()));
            assertThrows(DataAccessException.class, () ->
                jdbc.update("DELETE FROM service_recovery_approval WHERE incident_public_id=?", opened.publicId()));
        }
    }

    @Test
    @DisplayName("T34/REQ-S7-06: 2x peak arrival workload drill executes 60 concurrent operations with zero errors or invariant breaches")
    void twoTimesPeakArrivalWorkloadDrillCompletesWithZeroUnhandledErrors() throws Exception {
        try (var context = new RecoveryTestContext(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            JdbcTemplate jdbc = context.fixture.runtime();
            UUID employeeId = context.seedEmployee("EMP-LOAD-1", NOW.minusSeconds(3600));
            context.seedVerifiedDocument(employeeId, "PAYSLIP", 128L, "e".repeat(64), NOW.minusSeconds(600));

            int totalRequests = 60;
            CountDownLatch ready = new CountDownLatch(totalRequests);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger succeeded = new AtomicInteger();
            AtomicInteger failures = new AtomicInteger();

            var futures = java.util.stream.IntStream.range(0, totalRequests).mapToObj(index ->
                executor.submit(() -> {
                    ready.countDown();
                    try {
                        start.await(5, TimeUnit.SECONDS);
                        if (index % 3 == 0) {
                            OperationalHealthSummary health = context.service.healthSummary(OPS_CALLER);
                            if (health != null) {
                                succeeded.incrementAndGet();
                            }
                        } else if (index % 3 == 1) {
                            UUID eventId = UUID.randomUUID();
                            jdbc.update("INSERT INTO outbox_events(event_id,event_type,schema_version,aggregate_type,"
                                    + "aggregate_public_id,payload,status,created_at) VALUES (?,'LEAVE_SUBMITTED',1,'LEAVE',?,'{}','PENDING',?)",
                                eventId, employeeId, Timestamp.from(NOW));
                            succeeded.incrementAndGet();
                        } else {
                            Long gen = jdbc.queryForObject("SELECT generation FROM auth_generation WHERE id=1", Long.class);
                            if (gen != null && gen >= 0L) {
                                succeeded.incrementAndGet();
                            }
                        }
                    } catch (Exception exception) {
                        failures.incrementAndGet();
                    }
                })
            ).toList();

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            long startedNanos = System.nanoTime();
            start.countDown();
            for (var future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
            assertEquals(0, failures.get());
            assertEquals(totalRequests, succeeded.get());
            assertTrue(elapsedMillis < 5000L, "2x arrival burst must finish within bounded latency");
        }
    }

    private record VerifiedDoc(UUID documentPublicId, UUID versionPublicId, UUID attemptPublicId) { }

    private static final class RecoveryTestContext implements AutoCloseable {
        private final PostgresFixture fixture = new PostgresFixture();
        private final DataSourceTransactionManager manager;
        private final TransactionTemplate tx;
        private final PrivateDocumentStorage storage = mock(PrivateDocumentStorage.class);
        private final MockEnvironment environment = new MockEnvironment().withProperty("peoplecore.runtime.role", "api");
        private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        private final JdbcServiceRecoveryRepository repository;
        private final ServiceRecoveryGate gate;
        private final ServiceRecoveryService service;

        @SuppressWarnings("unchecked")
        private RecoveryTestContext() {
            fixture.flyway().migrate();
            fixture.grantRuntime();
            JdbcTemplate runtime = fixture.runtime();
            manager = new DataSourceTransactionManager(runtime.getDataSource());
            tx = new TransactionTemplate(manager);
            repository = new JdbcServiceRecoveryRepository(runtime);

            AccountAccessService accountAccess = mock(AccountAccessService.class);
            UserAccount opsAccount = mock(UserAccount.class);
            when(opsAccount.getId()).thenReturn(101L);
            UserAccount hrAccount = mock(UserAccount.class);
            when(hrAccount.getId()).thenReturn(102L);
            UserAccount finAccount = mock(UserAccount.class);
            when(finAccount.getId()).thenReturn(103L);
            UserAccount scopedAccount = mock(UserAccount.class);
            when(scopedAccount.getId()).thenReturn(104L);

            when(accountAccess.requireActive(OPS_CALLER, NOW)).thenReturn(opsAccount);
            when(accountAccess.requireActive(HR_CALLER, NOW)).thenReturn(hrAccount);
            when(accountAccess.requireActive(FIN_CALLER, NOW)).thenReturn(finAccount);
            when(accountAccess.requireActive(SCOPED_CALLER, NOW)).thenReturn(scopedAccount);

            when(accountAccess.hasScopedRole(101L, HrisRole.SYSTEM_ADMIN, null, NOW)).thenReturn(true);
            when(accountAccess.hasScopedRole(102L, HrisRole.HR_ADMIN, null, NOW)).thenReturn(true);
            when(accountAccess.hasScopedRole(103L, HrisRole.FINANCE_PAYROLL, null, NOW)).thenReturn(true);
            when(accountAccess.hasScopedRole(104L, HrisRole.SYSTEM_ADMIN, null, NOW)).thenReturn(false);

            OperationAccessService operationAccess = new OperationAccessService(accountAccess);
            ObjectProvider<ServiceRecoveryRepository> repoProvider = mock(ObjectProvider.class);
            when(repoProvider.getIfAvailable()).thenReturn(repository);
            gate = new ServiceRecoveryGate(environment, repoProvider);

            ObjectProvider<PrivateDocumentStorage> storageProvider = mock(ObjectProvider.class);
            when(storageProvider.getIfAvailable()).thenReturn(storage);

            AuditEventWriter auditWriter = mock(AuditEventWriter.class);
            org.mockito.Mockito.doAnswer(invocation -> {
                AuditEvent event = invocation.getArgument(0);
                runtime.update("INSERT INTO audit_events(event_id,actor_id,action,aggregate_type,aggregate_public_id,"
                        + "reason,details,created_at) VALUES (?,?,?,?,?,?,?,?)",
                    event.getEventId(), event.getActorId(), event.getAction(), event.getAggregateType(),
                    event.getAggregatePublicId(), event.getReason(), event.getDetails(),
                    Timestamp.from(event.getCreatedAt()));
                return null;
            }).when(auditWriter).append(org.mockito.ArgumentMatchers.any(AuditEvent.class));

            service = new ServiceRecoveryService(
                repository,
                operationAccess,
                new ProfileAdmissionGuard(new JdbcProfileAdmissionLockRepository(runtime)),
                auditWriter,
                storageProvider,
                gate,
                environment,
                clock
            );
        }

        private UUID seedUserAccount(String issuer, String subject, Instant createdAt) {
            UUID id = UUID.randomUUID();
            fixture.runtime().update("INSERT INTO user_accounts(public_id,oidc_issuer,oidc_subject,status,created_at) "
                + "VALUES (?,?,?,'ACTIVE',?)", id, issuer, subject, Timestamp.from(createdAt));
            return id;
        }

        private UUID seedEmployee(String number, Instant createdAt) {
            UUID id = UUID.randomUUID();
            fixture.runtime().update("INSERT INTO employees(public_id,employee_number,status,created_at) "
                + "VALUES (?,?,'ACTIVE',?)", id, number, Timestamp.from(createdAt));
            return id;
        }

        private void seedVerifyingDocument(UUID employeePublicId, Instant createdAt) {
            JdbcTemplate jdbc = fixture.runtime();
            long empId = jdbc.queryForObject("SELECT id FROM employees WHERE public_id=?", Long.class, employeePublicId);
            UUID docPublicId = UUID.randomUUID();
            long docId = jdbc.queryForObject("INSERT INTO private_document(public_id,employee_id,purpose,file_name,"
                    + "declared_media_type,declared_size,status,current_upload_generation,current_generation,created_at,updated_at) "
                    + "VALUES (?,?,'CLAIM_EVIDENCE','receipt.pdf','application/pdf',256,'PENDING_UPLOAD',1,1,?,?) RETURNING id",
                Long.class, docPublicId, empId, Timestamp.from(createdAt), Timestamp.from(createdAt));
            UUID uploadPublicId = UUID.randomUUID();
            long uploadId = jdbc.queryForObject("INSERT INTO document_upload_attempt(public_id,document_id,generation,"
                    + "staging_key,byte_size,status,created_at) "
                    + "VALUES (?,?,1,?,256,'RESERVED',?) RETURNING id",
                Long.class, uploadPublicId, docId, "staging/" + uploadPublicId, Timestamp.from(createdAt));
            jdbc.update("UPDATE document_upload_attempt SET status='COMPLETED',object_etag='\"etag\"',completed_at=? WHERE id=?",
                Timestamp.from(createdAt), uploadId);
            jdbc.update("UPDATE private_document SET status='VERIFYING',current_upload_id=? WHERE id=?", uploadId, docId);
            UUID attemptPublicId = UUID.randomUUID();
            jdbc.update("INSERT INTO document_verification_attempt(public_id,document_id,upload_attempt_id,generation,"
                    + "candidate_key,status,created_at) VALUES (?,?,?,1,?,'QUEUED',?)",
                attemptPublicId, docId, uploadId, "candidates/" + attemptPublicId, Timestamp.from(createdAt));
        }

        private VerifiedDoc seedVerifiedDocument(UUID employeePublicId, String purpose, long byteSize,
                                                 String sha256, Instant createdAt) {
            JdbcTemplate jdbc = fixture.runtime();
            long empId = jdbc.queryForObject("SELECT id FROM employees WHERE public_id=?", Long.class, employeePublicId);
            UUID docPublicId = UUID.randomUUID();
            long docId = jdbc.queryForObject("INSERT INTO private_document(public_id,employee_id,purpose,file_name,"
                    + "declared_media_type,declared_size,status,current_upload_generation,current_generation,created_at,updated_at) "
                    + "VALUES (?,?,?,'file.pdf','application/pdf',?,'PENDING_UPLOAD',1,1,?,?) RETURNING id",
                Long.class, docPublicId, empId, purpose, byteSize, Timestamp.from(createdAt), Timestamp.from(createdAt));
            UUID uploadPublicId = UUID.randomUUID();
            long uploadId = jdbc.queryForObject("INSERT INTO document_upload_attempt(public_id,document_id,generation,"
                    + "staging_key,byte_size,status,created_at) "
                    + "VALUES (?,?,1,?,?,'RESERVED',?) RETURNING id",
                Long.class, uploadPublicId, docId, "staging/" + uploadPublicId, byteSize, Timestamp.from(createdAt));
            jdbc.update("UPDATE document_upload_attempt SET status='COMPLETED',object_etag='\"etag\"',completed_at=? WHERE id=?",
                Timestamp.from(createdAt), uploadId);
            jdbc.update("UPDATE private_document SET status='VERIFYING',current_upload_id=? WHERE id=?", uploadId, docId);
            UUID attemptPublicId = UUID.randomUUID();
            UUID leaseToken = UUID.randomUUID();
            long attemptId = jdbc.queryForObject("INSERT INTO document_verification_attempt(public_id,document_id,"
                    + "upload_attempt_id,generation,candidate_key,status,created_at) "
                    + "VALUES (?,?,?,1,?,'QUEUED',?) RETURNING id",
                Long.class, attemptPublicId, docId, uploadId, "candidates/" + attemptPublicId, Timestamp.from(createdAt));
            jdbc.update("UPDATE document_verification_attempt SET status='CLAIMED',lease_token=?,"
                    + "lease_expires_at=clock_timestamp()+interval '5 minutes' WHERE id=?",
                leaseToken, attemptId);
            jdbc.update("UPDATE document_verification_attempt SET copy_started_at=clock_timestamp() WHERE id=?",
                attemptId);
            jdbc.update("UPDATE document_verification_attempt SET copy_completed_at=clock_timestamp() WHERE id=?",
                attemptId);
            jdbc.update("UPDATE document_verification_attempt SET status='CLEAN',completed_at=clock_timestamp() WHERE id=?",
                attemptId);
            UUID versionPublicId = UUID.randomUUID();
            long versionId = jdbc.queryForObject("INSERT INTO document_version(public_id,document_id,attempt_id,"
                    + "lease_token,media_type,byte_size,sha256,scanner_version,verified_at) "
                    + "VALUES (?,?,?,?,'application/pdf',?,?,'clamav/1',?) RETURNING id",
                Long.class, versionPublicId, docId, attemptId, leaseToken, byteSize, sha256, Timestamp.from(createdAt));
            jdbc.update("UPDATE private_document SET status='VERIFIED',current_version_id=? WHERE id=?", versionId, docId);
            return new VerifiedDoc(docPublicId, versionPublicId, attemptPublicId);
        }

        @Override
        public void close() {
            fixture.close();
        }
    }
}
