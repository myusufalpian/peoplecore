package id.mydev.peoplecore.infrastructure.recovery;

import id.mydev.peoplecore.common.api.GlobalExceptionHandler;
import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.common.audit.AuditEventWriter;
import id.mydev.peoplecore.common.command.AdmissionGuard;
import id.mydev.peoplecore.common.command.CommandConflictException;
import id.mydev.peoplecore.common.command.InvalidIdempotencyKeyException;
import id.mydev.peoplecore.common.security.OperationAccessService;
import id.mydev.peoplecore.document.infrastructure.storage.PrivateDocumentStorage;
import id.mydev.peoplecore.infrastructure.recovery.ServiceRecoveryController.InventoryDocumentsRequest;
import id.mydev.peoplecore.infrastructure.recovery.ServiceRecoveryController.OpenIncidentRequest;
import id.mydev.peoplecore.infrastructure.recovery.ServiceRecoveryController.ReconcileIdentitiesRequest;
import id.mydev.peoplecore.infrastructure.recovery.ServiceRecoveryController.RecordApprovalRequest;
import id.mydev.peoplecore.infrastructure.recovery.ServiceRecoveryController.ResolveExceptionRequest;
import id.mydev.peoplecore.infrastructure.recovery.ServiceRecoveryMapper.IncidentDetail;
import id.mydev.peoplecore.infrastructure.recovery.ServiceRecoveryMapper.OperationalHealthSummary;
import id.mydev.peoplecore.infrastructure.recovery.ServiceRecoveryRepository.Approval;
import id.mydev.peoplecore.infrastructure.recovery.ServiceRecoveryRepository.Incident;
import id.mydev.peoplecore.infrastructure.recovery.ServiceRecoveryRepository.OperationalBacklog;
import id.mydev.peoplecore.infrastructure.recovery.ServiceRecoveryRepository.QueueBacklog;
import id.mydev.peoplecore.infrastructure.recovery.ServiceRecoveryRepository.ReconciliationSummary;
import id.mydev.peoplecore.infrastructure.recovery.ServiceRecoveryRepository.RecoveryException;
import id.mydev.peoplecore.infrastructure.recovery.ServiceRecoveryRepository.VerifiedDocumentCandidate;
import id.mydev.peoplecore.support.JwtAuthFixture;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ServiceRecoveryServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-18T10:00:00Z");
    private static final Instant RESTORE_POINT = Instant.parse("2026-11-18T09:45:00Z");
    private static final UUID INCIDENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID EXCEPTION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID DOC_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID VERSION_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID ATTEMPT_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final Authentication CALLER = JwtAuthFixture.token("https://idp.example", "ops-admin");

    private final ServiceRecoveryRepository repository = mock(ServiceRecoveryRepository.class);
    private final OperationAccessService access = mock(OperationAccessService.class);
    private final AdmissionGuard admission = mock(AdmissionGuard.class);
    private final AuditEventWriter audit = mock(AuditEventWriter.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<PrivateDocumentStorage> storageProvider = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<ServiceRecoveryRepository> repositoryProvider = mock(ObjectProvider.class);
    private final MockEnvironment environment = new MockEnvironment().withProperty("peoplecore.runtime.role", "api");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private ServiceRecoveryGate gate() {
        when(repositoryProvider.getIfAvailable()).thenReturn(repository);
        return new ServiceRecoveryGate(environment, repositoryProvider);
    }

    private ServiceRecoveryService service() {
        return new ServiceRecoveryService(repository, access, admission, audit, storageProvider, gate(), environment, clock);
    }

    @Test
    @DisplayName("ServiceRecoveryGate combines environment property and active database incident")
    void gateCombinesEnvironmentAndDatabaseIncident() {
        ServiceRecoveryGate gate = gate();
        when(repository.hasActiveIncident()).thenReturn(false);
        assertFalse(gate.isIsolated());
        gate.requireNormalOperation();

        when(repository.hasActiveIncident()).thenReturn(true);
        assertTrue(gate.isIsolated());
        ResponseStatusException dbIsolated = assertThrows(ResponseStatusException.class, gate::requireNormalOperation);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, dbIsolated.getStatusCode());

        when(repository.hasActiveIncident()).thenReturn(false);
        environment.setProperty("peoplecore.recovery.isolated", "true");
        assertTrue(gate.isIsolated());
        assertThrows(ResponseStatusException.class, gate::requireNormalOperation);
    }

    @Test
    @DisplayName("Operational health summary maps backlog counts, oldest ages, and recovery status")
    void healthSummaryReturnsBacklogMetrics() {
        OperationalBacklog backlog = new OperationalBacklog(
            new QueueBacklog(3L, 120L),
            new QueueBacklog(2L, 95L),
            new QueueBacklog(1L, 45L),
            new QueueBacklog(4L, 180L),
            new QueueBacklog(5L, 60L),
            2L,
            INCIDENT_ID,
            "ISOLATED",
            1L
        );
        when(repository.hasActiveIncident()).thenReturn(true);
        when(repository.queryBacklog(NOW)).thenReturn(backlog);

        OperationalHealthSummary summary = service().healthSummary(CALLER);
        assertEquals("api", summary.runtimeRole());
        assertTrue(summary.recoveryIsolated());
        assertEquals(INCIDENT_ID, summary.activeIncidentPublicId());
        assertEquals("ISOLATED", summary.activeIncidentStatus());
        assertEquals(1L, summary.openRecoveryExceptions());
        assertEquals(2L, summary.quarantinedEvents());
        assertEquals(3L, summary.outbox().pendingCount());
        assertEquals(120L, summary.outbox().oldestAgeSeconds());
        assertEquals(2L, summary.notificationDeliveries().pendingCount());
        assertEquals(1L, summary.notificationAlerts().pendingCount());
        assertEquals(4L, summary.reportExports().pendingCount());
        assertEquals(5L, summary.documentVerifications().pendingCount());
        verify(access).requireOperator(CALLER, NOW);
    }

    @Test
    @DisplayName("Validation rejects future restore points, negative loss windows, blank reasons, and invalid roles")
    void validationRejectsInvalidRecoveryInputs() {
        ServiceRecoveryService recovery = service();
        assertThrows(InvalidIdempotencyKeyException.class, () ->
            recovery.openIncident(CALLER, " ", RESTORE_POINT, 60L, "Restore drill"));
        assertThrows(IllegalArgumentException.class, () ->
            recovery.openIncident(CALLER, "key-1", NOW.plusSeconds(10), 60L, "Future restore point"));
        assertThrows(IllegalArgumentException.class, () ->
            recovery.openIncident(CALLER, "key-2", RESTORE_POINT, -1L, "Negative loss window"));
        assertThrows(IllegalArgumentException.class, () ->
            recovery.openIncident(CALLER, "key-3", RESTORE_POINT, 60L, " "));
        assertThrows(IllegalArgumentException.class, () ->
            recovery.resolveException(CALLER, INCIDENT_ID, EXCEPTION_ID, "key-4", "INVALID", "Reason"));
        assertThrows(IllegalArgumentException.class, () ->
            recovery.recordApproval(CALLER, INCIDENT_ID, "key-5", "EMPLOYEE", "Reason"));
    }

    @Test
    @DisplayName("Document inventory fails closed with 503 when storage is unavailable and records exceptions for missing objects")
    void documentInventoryFailsClosedWithoutStorageAndRecordsMissingObjects() {
        ServiceRecoveryService recovery = service();
        when(storageProvider.getIfAvailable()).thenReturn(null);
        ResponseStatusException noStorage = assertThrows(ResponseStatusException.class, () ->
            recovery.inventoryDocuments(CALLER, INCIDENT_ID, "inv-key-1", "Check storage"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, noStorage.getStatusCode());

        PrivateDocumentStorage storage = mock(PrivateDocumentStorage.class);
        when(storageProvider.getIfAvailable()).thenReturn(storage);
        Incident active = new Incident(INCIDENT_ID, "ISOLATED", RESTORE_POINT, 300L, "ops", "Drill", NOW, null, null, null);
        when(repository.findIncident(INCIDENT_ID, true)).thenReturn(Optional.of(active));
        when(repository.operationHash(CALLER.getName(), "inv-key-2")).thenReturn(Optional.empty());
        VerifiedDocumentCandidate candidate = new VerifiedDocumentCandidate(
            DOC_ID, VERSION_ID, ATTEMPT_ID, "PAYSLIP", 1024L, "a".repeat(64));
        when(repository.verifiedDocumentsForInventory()).thenReturn(List.of(candidate));
        when(storage.verifyCandidate(ATTEMPT_ID, 1024L, "a".repeat(64))).thenReturn(false);
        when(repository.openExceptionCount(INCIDENT_ID)).thenReturn(1);

        var view = recovery.inventoryDocuments(CALLER, INCIDENT_ID, "inv-key-2", "Verify storage objects");
        assertEquals(1, view.documentsChecked());
        assertEquals(1, view.missingOrCorruptObjects());
        assertEquals(1, view.openExceptions());
        verify(repository).markIncidentReconciling(INCIDENT_ID, NOW);
    }

    @Test
    @DisplayName("Approval rejects open role exceptions and reopens when all three roles approve")
    void approvalEnforcesExceptionClosureAndThreeRoleConsensus() {
        ServiceRecoveryService recovery = service();
        Incident active = new Incident(INCIDENT_ID, "RECONCILING", RESTORE_POINT, 300L, "ops", "Drill", NOW, NOW, null, null);
        when(repository.findIncident(INCIDENT_ID, true)).thenReturn(Optional.of(active));
        when(repository.operationHash(CALLER.getName(), "app-1")).thenReturn(Optional.empty());
        when(repository.openExceptionCountForRole(INCIDENT_ID, "FINANCE_PAYROLL")).thenReturn(1);

        assertThrows(CommandConflictException.class, () ->
            recovery.recordApproval(CALLER, INCIDENT_ID, "app-1", "FINANCE_PAYROLL", "Cannot approve yet"));

        when(repository.openExceptionCountForRole(INCIDENT_ID, "FINANCE_PAYROLL")).thenReturn(0);
        when(repository.openExceptionCount(INCIDENT_ID)).thenReturn(0);
        when(repository.listApprovals(INCIDENT_ID)).thenReturn(List.of(
            new Approval(INCIDENT_ID, "HR_ADMIN", "hr", "HR ok", NOW),
            new Approval(INCIDENT_ID, "FINANCE_PAYROLL", "fin", "Fin ok", NOW),
            new Approval(INCIDENT_ID, "SYSTEM_ADMIN", "ops", "Ops ok", NOW)
        ));
        Incident reopened = new Incident(INCIDENT_ID, "REOPENED", RESTORE_POINT, 300L, "ops", "Drill", NOW, NOW, NOW, 120L);
        when(repository.reopenIncident(INCIDENT_ID, NOW)).thenReturn(reopened);
        when(repository.listExceptions(INCIDENT_ID)).thenReturn(List.of());

        IncidentDetail detail = recovery.recordApproval(CALLER, INCIDENT_ID, "app-2", "FINANCE_PAYROLL", "All reconciled");
        assertEquals("REOPENED", detail.status());
        assertEquals(120L, detail.rtoSeconds());
    }

    @Test
    @DisplayName("openIncident, reconcileIdentities, resolveException, and idempotent replay cover all service branches")
    void openReconcileAndResolveCoverHappyReplayAndConflictPaths() {
        ServiceRecoveryService recovery = service();
        Incident created = new Incident(INCIDENT_ID, "ISOLATED", RESTORE_POINT, 300L, "ops", "Drill", NOW, null, null, null);
        when(repository.openIncident(org.mockito.ArgumentMatchers.any(UUID.class), org.mockito.ArgumentMatchers.eq(RESTORE_POINT),
            org.mockito.ArgumentMatchers.eq(300L), org.mockito.ArgumentMatchers.eq(CALLER.getName()),
            org.mockito.ArgumentMatchers.eq("Drill"), org.mockito.ArgumentMatchers.eq(NOW))).thenReturn(created);
        when(repository.findIncident(INCIDENT_ID, false)).thenReturn(Optional.of(created));
        when(repository.findIncident(INCIDENT_ID, true)).thenReturn(Optional.of(created));
        when(repository.listExceptions(INCIDENT_ID)).thenReturn(List.of());
        when(repository.listApprovals(INCIDENT_ID)).thenReturn(List.of());

        IncidentDetail opened = recovery.openIncident(CALLER, "open-k1", RESTORE_POINT, 300L, " Drill ");
        assertEquals(INCIDENT_ID, opened.publicId());

        when(repository.operationHash(CALLER.getName(), "open-k1"))
            .thenReturn(Optional.of(id.mydev.peoplecore.common.command.CommandExecutionService.computeSha256(
                "OPEN|" + RESTORE_POINT + "|300|Drill")));
        when(repository.findIncident(org.mockito.ArgumentMatchers.any(UUID.class), org.mockito.ArgumentMatchers.eq(false)))
            .thenReturn(Optional.of(created));
        IncidentDetail replayedOpen = recovery.openIncident(CALLER, "open-k1", RESTORE_POINT, 300L, "Drill");
        assertEquals(INCIDENT_ID, replayedOpen.publicId());

        assertThrows(CommandConflictException.class, () ->
            recovery.openIncident(CALLER, "open-k1", RESTORE_POINT, 999L, "Different payload"));

        UUID accId = UUID.fromString("77777777-7777-7777-7777-777777777777");
        UUID invId = UUID.fromString("88888888-8888-8888-8888-888888888888");
        when(repository.reconcileIdentities(INCIDENT_ID, List.of(accId), NOW, List.of(invId), NOW))
            .thenReturn(new ReconciliationSummary(INCIDENT_ID, 1, 1, 1, 5L));
        var recView = recovery.reconcileIdentities(CALLER, INCIDENT_ID, "rec-k1", List.of(accId), null, List.of(invId), "Reconcile");
        assertEquals(1, recView.accountsCutOff());
        assertEquals(5L, recView.authorizationGeneration());

        when(repository.reconcileIdentities(INCIDENT_ID, List.of(), RESTORE_POINT, List.of(), NOW))
            .thenReturn(new ReconciliationSummary(INCIDENT_ID, 0, 0, 0, 5L));
        var recEmpty = recovery.reconcileIdentities(CALLER, INCIDENT_ID, "rec-k2", null, RESTORE_POINT, List.of(), "Reconcile empty");
        assertNotNull(recEmpty);

        String recHash = id.mydev.peoplecore.common.command.CommandExecutionService.computeSha256(
            "RECONCILE_ID|" + INCIDENT_ID + "|[]|" + RESTORE_POINT + "|[]|Reconcile empty");
        when(repository.operationHash(CALLER.getName(), "rec-k2")).thenReturn(Optional.of(recHash));
        assertEquals(0, recovery.reconcileIdentities(CALLER, INCIDENT_ID, "rec-k2", List.of(), RESTORE_POINT, null, "Reconcile empty").accountsCutOff());

        assertThrows(IllegalArgumentException.class, () ->
            recovery.reconcileIdentities(CALLER, INCIDENT_ID, "rec-null", java.util.Collections.singletonList(null), NOW, List.of(), "Null id"));

        RecoveryException openEx = new RecoveryException(EXCEPTION_ID, INCIDENT_ID, "FINANCE", "FINANCE_PAYROLL",
            "PRIVATE_DOCUMENT", DOC_ID, "MISSING", "OPEN", "Missing object", null, null, NOW, null);
        RecoveryException resolvedEx = new RecoveryException(EXCEPTION_ID, INCIDENT_ID, "FINANCE", "FINANCE_PAYROLL",
            "PRIVATE_DOCUMENT", DOC_ID, "MISSING", "ACCEPTED_RESTRICTION", "Missing object", CALLER.getName(), "Accepted", NOW, NOW);
        when(repository.findException(INCIDENT_ID, EXCEPTION_ID, true)).thenReturn(Optional.of(openEx));
        when(repository.findException(INCIDENT_ID, EXCEPTION_ID, false)).thenReturn(Optional.of(resolvedEx));

        var exView = recovery.resolveException(CALLER, INCIDENT_ID, EXCEPTION_ID, "res-k1", "ACCEPTED_RESTRICTION", "Accepted");
        assertEquals("ACCEPTED_RESTRICTION", exView.status());

        String resHash = id.mydev.peoplecore.common.command.CommandExecutionService.computeSha256(
            "RESOLVE_EX|" + INCIDENT_ID + "|" + EXCEPTION_ID + "|ACCEPTED_RESTRICTION|Accepted");
        when(repository.operationHash(CALLER.getName(), "res-k1")).thenReturn(Optional.of(resHash));
        assertEquals("ACCEPTED_RESTRICTION",
            recovery.resolveException(CALLER, INCIDENT_ID, EXCEPTION_ID, "res-k1", "ACCEPTED_RESTRICTION", "Accepted").status());

        when(repository.findException(INCIDENT_ID, EXCEPTION_ID, true)).thenReturn(Optional.of(resolvedEx));
        assertThrows(CommandConflictException.class, () ->
            recovery.resolveException(CALLER, INCIDENT_ID, EXCEPTION_ID, "res-k2", "RESOLVED", "Already resolved"));

        PrivateDocumentStorage storage = mock(PrivateDocumentStorage.class);
        when(storageProvider.getIfAvailable()).thenReturn(storage);
        VerifiedDocumentCandidate intactHrDoc = new VerifiedDocumentCandidate(
            DOC_ID, VERSION_ID, ATTEMPT_ID, "OTHER_PURPOSE", 128L, "b".repeat(64));
        when(repository.verifiedDocumentsForInventory()).thenReturn(List.of(intactHrDoc));
        when(storage.verifyCandidate(ATTEMPT_ID, 128L, "b".repeat(64))).thenReturn(false);
        recovery.inventoryDocuments(CALLER, INCIDENT_ID, "inv-hr-doc", "HR doc check");

        String invHash = id.mydev.peoplecore.common.command.CommandExecutionService.computeSha256(
            "INVENTORY_DOCS|" + INCIDENT_ID + "|HR doc check");
        when(repository.operationHash(CALLER.getName(), "inv-hr-doc")).thenReturn(Optional.of(invHash));
        assertEquals(1, recovery.inventoryDocuments(CALLER, INCIDENT_ID, "inv-hr-doc", "HR doc check").documentsChecked());

        when(storage.verifyCandidate(ATTEMPT_ID, 128L, "b".repeat(64))).thenReturn(true);
        assertEquals(0, recovery.inventoryDocuments(CALLER, INCIDENT_ID, "inv-intact-doc", "Intact doc").missingOrCorruptObjects());

        String appHash = id.mydev.peoplecore.common.command.CommandExecutionService.computeSha256(
            "APPROVE|" + INCIDENT_ID + "|SYSTEM_ADMIN|Ops signoff");
        when(repository.operationHash(CALLER.getName(), "app-replay")).thenReturn(Optional.of(appHash));
        assertNotNull(recovery.recordApproval(CALLER, INCIDENT_ID, "app-replay", "SYSTEM_ADMIN", "Ops signoff"));

        when(repository.openExceptionCountForRole(INCIDENT_ID, "SYSTEM_ADMIN")).thenReturn(0);
        when(repository.openExceptionCount(INCIDENT_ID)).thenReturn(1);
        assertThrows(CommandConflictException.class, () ->
            recovery.recordApproval(CALLER, INCIDENT_ID, "app-ops-blocked", "SYSTEM_ADMIN", "Blocked by open HR/Finance exception"));

        Incident reopened = new Incident(INCIDENT_ID, "REOPENED", RESTORE_POINT, 300L, "ops", "Drill", NOW, NOW, NOW, 60L);
        when(repository.findIncident(INCIDENT_ID, true)).thenReturn(Optional.of(reopened));
        assertThrows(CommandConflictException.class, () ->
            recovery.recordApproval(CALLER, INCIDENT_ID, "app-after-reopen", "HR_ADMIN", "Already reopened"));
    }

    @Test
    @DisplayName("Controllers return no-store headers and map validation/unavailable errors cleanly")
    void controllersAndExceptionAdviceHandleRequests() throws Exception {
        ServiceRecoveryService mockedService = mock(ServiceRecoveryService.class);
        OperationsObservabilityController obsController = new OperationsObservabilityController(mockedService);
        ServiceRecoveryController recController = new ServiceRecoveryController(mockedService);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(obsController, recController)
            .setControllerAdvice(new ServiceRecoveryExceptionHandler(), new GlobalExceptionHandler())
            .build();

        SecurityContextHolder.getContext().setAuthentication(CALLER);
        try {
            OperationalHealthSummary summary = new OperationalHealthSummary(
                "api", false, null, null, 0L, 0L,
                new ServiceRecoveryMapper.BacklogMetric(0L, 0L),
                new ServiceRecoveryMapper.BacklogMetric(0L, 0L),
                new ServiceRecoveryMapper.BacklogMetric(0L, 0L),
                new ServiceRecoveryMapper.BacklogMetric(0L, 0L),
                new ServiceRecoveryMapper.BacklogMetric(0L, 0L),
                NOW
            );
            when(mockedService.healthSummary(CALLER)).thenReturn(summary);
            mvc.perform(get("/api/v1/operations/health-summary"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.runtimeRole").value("api"));

            IncidentDetail detail = new IncidentDetail(
                INCIDENT_ID, "ISOLATED", RESTORE_POINT, 300L, "ops", "Restore", NOW, null, null, null, List.of(), List.of());
            when(mockedService.openIncident(CALLER, "k1", RESTORE_POINT, 300L, "Restore")).thenReturn(detail);
            mvc.perform(post("/api/v1/operations/recovery/incidents")
                    .header("Idempotency-Key", "k1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"restorePointAt\":\"2026-11-18T09:45:00Z\",\"observedLossWindowSeconds\":300,\"reason\":\"Restore\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/operations/recovery/incidents/" + INCIDENT_ID));

            when(mockedService.getIncident(CALLER, INCIDENT_ID)).thenReturn(detail);
            assertNotNull(recController.getIncident(INCIDENT_ID).getBody());
            assertNotNull(recController.reconcileIdentities(INCIDENT_ID, "k2",
                new ReconcileIdentitiesRequest(List.of(), NOW, List.of(), "Reconcile")));
            assertNotNull(recController.inventoryDocuments(INCIDENT_ID, "k3",
                new InventoryDocumentsRequest("Inventory")));
            assertNotNull(recController.resolveException(INCIDENT_ID, EXCEPTION_ID, "k4",
                new ResolveExceptionRequest("RESOLVED", "Fixed")));
            assertNotNull(recController.recordApproval(INCIDENT_ID, "k5",
                new RecordApprovalRequest("SYSTEM_ADMIN", "Approve")));

            doThrow(new IllegalArgumentException("bad input"))
                .when(mockedService).openIncident(CALLER, "k-bad", RESTORE_POINT, 300L, "Restore");
            mvc.perform(post("/api/v1/operations/recovery/incidents")
                    .header("Idempotency-Key", "k-bad")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"restorePointAt\":\"2026-11-18T09:45:00Z\",\"observedLossWindowSeconds\":300,\"reason\":\"Restore\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.key").value("RECOVERY_REQUEST_INVALID"));

            doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "no storage"))
                .when(mockedService).inventoryDocuments(CALLER, INCIDENT_ID, "k-503", "Inventory");
            mvc.perform(post("/api/v1/operations/recovery/incidents/" + INCIDENT_ID + "/inventory-documents")
                    .header("Idempotency-Key", "k-503")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reason\":\"Inventory\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.key").value("RECOVERY_STORAGE_UNAVAILABLE"));

            doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "conflict"))
                .when(mockedService).inventoryDocuments(CALLER, INCIDENT_ID, "k-409", "Inventory");
            mvc.perform(post("/api/v1/operations/recovery/incidents/" + INCIDENT_ID + "/inventory-documents")
                    .header("Idempotency-Key", "k-409")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reason\":\"Inventory\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.key").value("RECOVERY_REQUEST_FAILED"));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    @DisplayName("PrivateDocumentStorage verifyCandidate validates size, SHA-256 digest, and 404/mismatch paths")
    void privateDocumentStorageVerifyCandidateCoversAllBranches() {
        var s3 = mock(software.amazon.awssdk.services.s3.S3Client.class);
        var presigner = mock(software.amazon.awssdk.services.s3.presigner.S3Presigner.class);
        var copyClaim = mock(id.mydev.peoplecore.document.infrastructure.storage.DocumentCopyClaim.class);
        var storage = new PrivateDocumentStorage(s3, presigner, "test-bucket", copyClaim);

        byte[] content = "hello-recovery".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String expectedSha = id.mydev.peoplecore.common.command.CommandExecutionService.computeSha256("hello-recovery");

        when(s3.headObject(software.amazon.awssdk.services.s3.model.HeadObjectRequest.builder()
                .bucket("test-bucket").key("candidates/" + ATTEMPT_ID).build()))
            .thenReturn(software.amazon.awssdk.services.s3.model.HeadObjectResponse.builder()
                .contentLength((long) content.length).build());
        when(s3.getObject(software.amazon.awssdk.services.s3.model.GetObjectRequest.builder()
                .bucket("test-bucket").key("candidates/" + ATTEMPT_ID).build()))
            .thenAnswer(inv -> new software.amazon.awssdk.core.ResponseInputStream<>(
                software.amazon.awssdk.services.s3.model.GetObjectResponse.builder().build(),
                new java.io.ByteArrayInputStream(content)));

        assertTrue(storage.verifyCandidate(ATTEMPT_ID, content.length, expectedSha));
        assertFalse(storage.verifyCandidate(ATTEMPT_ID, content.length, "0".repeat(64)));
        assertFalse(storage.verifyCandidate(ATTEMPT_ID, content.length + 5, expectedSha));

        UUID missingId = UUID.fromString("66666666-6666-6666-6666-666666666666");
        when(s3.headObject(software.amazon.awssdk.services.s3.model.HeadObjectRequest.builder()
                .bucket("test-bucket").key("candidates/" + missingId).build()))
            .thenThrow(software.amazon.awssdk.services.s3.model.S3Exception.builder().statusCode(404).message("Not Found").build());
        assertFalse(storage.verifyCandidate(missingId, 10L, expectedSha));
    }

    @Test
    @DisplayName("ServiceRecoveryGate fences CommandExecutionService, DocumentVersionContentService, DocumentContentQuery, DocumentDeletionService, and DocumentVerificationWorker")
    void recoveryGateFencesAllConfiguredServicesAndWorkers() throws Exception {
        ServiceRecoveryGate activeGate = gate();
        when(repository.hasActiveIncident()).thenReturn(true);
        @SuppressWarnings("unchecked")
        ObjectProvider<ServiceRecoveryGate> gateProvider = mock(ObjectProvider.class);
        when(gateProvider.getIfAvailable()).thenReturn(activeGate);

        var docContent = new id.mydev.peoplecore.document.application.service.DocumentVersionContentService(
            mock(id.mydev.peoplecore.document.domain.repository.DocumentVersionContentRepository.class),
            mock(ObjectProvider.class));
        docContent.configureRecoveryGate(gateProvider);
        assertThrows(ResponseStatusException.class, () ->
            docContent.open(DOC_ID, VERSION_ID, UUID.randomUUID(), id.mydev.peoplecore.document.domain.model.Purpose.PAYSLIP));

        var docQuery = new id.mydev.peoplecore.document.application.service.DocumentContentQuery(
            mock(id.mydev.peoplecore.document.domain.repository.DocumentContentRepository.class),
            mock(id.mydev.peoplecore.identity.application.service.AccountAccessService.class),
            clock);
        docQuery.configureRecoveryGate(gateProvider);
        assertThrows(ResponseStatusException.class, () ->
            docQuery.requireOwnedClaimEvidence(DOC_ID, CALLER));

        var deletionService = new id.mydev.peoplecore.document.application.service.DocumentDeletionService(
            mock(id.mydev.peoplecore.document.domain.repository.DocumentDeletionRepository.class),
            mock(id.mydev.peoplecore.document.application.service.DocumentPurgeWorker.class),
            mock(ObjectProvider.class),
            mock(id.mydev.peoplecore.identity.application.service.AccountAccessService.class),
            clock,
            java.time.Duration.ofHours(720));
        deletionService.configureRecoveryGate(gateProvider);
        assertEquals(0, deletionService.purgeExpired(10));

        var verificationWorker = new id.mydev.peoplecore.document.application.service.DocumentVerificationWorker(
            mock(id.mydev.peoplecore.document.domain.repository.DocumentVerificationRepository.class),
            mock(org.springframework.transaction.PlatformTransactionManager.class),
            mock(PrivateDocumentStorage.class),
            mock(id.mydev.peoplecore.document.infrastructure.scanner.ClamAvScanner.class),
            mock(id.mydev.peoplecore.document.application.service.DocumentFormatVerifier.class),
            java.time.Duration.ofSeconds(30));
        verificationWorker.configureRecoveryGate(gateProvider);
        assertFalse(verificationWorker.verifyNext());
        assertFalse(verificationWorker.recoverNext(java.time.Duration.ofSeconds(10)));

        var commandService = new id.mydev.peoplecore.common.command.CommandExecutionService(
            mock(id.mydev.peoplecore.common.command.CommandReceiptRepository.class),
            audit,
            mock(id.mydev.peoplecore.common.outbox.OutboxEventRepository.class),
            mock(id.mydev.peoplecore.common.command.CommandResultSerializer.class),
            mock(id.mydev.peoplecore.common.command.CommandExecutionCoordinator.class),
            mock(id.mydev.peoplecore.common.security.CurrentAccessPolicy.class),
            admission,
            mock(jakarta.persistence.EntityManager.class));
        commandService.configureRecoveryGate(gateProvider);
        assertThrows(ResponseStatusException.class, () ->
            commandService.executeCommand(
                new id.mydev.peoplecore.common.command.CommandExecutionService.CommandContext("ops", "EMPLOYEE_CREATE", "k", "{}", null),
                new id.mydev.peoplecore.common.command.CommandExecutionService.CommandExecutionPlan<>(() -> "ok", String.class, null, null)));
    }
}
