package id.mydev.peoplecore.document.application.service;

import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.common.command.CommandConflictException;
import id.mydev.peoplecore.document.domain.repository.DocumentDeletionRepository;
import id.mydev.peoplecore.document.domain.repository.DocumentDeletionRepository.TargetRecord;
import id.mydev.peoplecore.document.infrastructure.storage.PrivateDocumentStorage;
import id.mydev.peoplecore.identity.application.service.AccountAccessService;
import id.mydev.peoplecore.identity.domain.model.HrisRole;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.core.Authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentDeletionServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-10T10:00:00Z"), ZoneOffset.UTC);
    private static final Instant NOW = Instant.parse("2026-11-10T10:00:00Z");
    private static final Duration WINDOW = Duration.ofDays(30);
    private static final UUID DOCUMENT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID EMPLOYEE = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final long DOCUMENT_ID = 7L;
    private static final long OTHER_DOCUMENT_ID = 8L;
    private static final long ACCOUNT_ID = 11L;
    private static final String CANDIDATE_KEY = "candidates/33333333-3333-3333-3333-333333333333";

    private final DocumentDeletionRepository documents = mock(DocumentDeletionRepository.class);
    private final DocumentPurgeWorker purger = mock(DocumentPurgeWorker.class);
    private final PrivateDocumentStorage storage = mock(PrivateDocumentStorage.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<PrivateDocumentStorage> provider = mock(ObjectProvider.class);
    private final AccountAccessService access = mock(AccountAccessService.class);
    private final UserAccount account = mock(UserAccount.class);
    private final Authentication caller = mock(Authentication.class);

    @Test
    void purgeDeletesCandidateKeysOnlyAfterTheClaimIsMarked() {
        when(provider.getIfAvailable()).thenReturn(storage);
        when(documents.findExpired(NOW, 10)).thenReturn(List.of(DOCUMENT_ID));
        when(purger.purge(DOCUMENT_ID, NOW)).thenReturn(true);
        when(purger.completeObjectPurge(DOCUMENT_ID, NOW)).thenReturn(true);
        when(documents.candidateKeys(DOCUMENT_ID)).thenReturn(List.of(CANDIDATE_KEY));

        assertEquals(1, service().purgeExpired(10));
        verify(storage).deleteObject(CANDIDATE_KEY);
    }

    @Test
    void purgeSkipsADocumentWhoseClaimChanged() {
        when(provider.getIfAvailable()).thenReturn(storage);
        when(documents.findExpired(NOW, 10)).thenReturn(List.of(DOCUMENT_ID));
        when(purger.purge(DOCUMENT_ID, NOW)).thenReturn(false);

        assertEquals(0, service().purgeExpired(10));
        verify(documents, never()).candidateKeys(DOCUMENT_ID);
    }

    @Test
    void purgeIsolatesOneFailingDocumentFromTheRestOfTheBatch() {
        when(provider.getIfAvailable()).thenReturn(storage);
        when(documents.findExpired(NOW, 10)).thenReturn(List.of(DOCUMENT_ID, OTHER_DOCUMENT_ID));
        when(purger.purge(DOCUMENT_ID, NOW)).thenThrow(new DataAccessResourceFailureException("locked"));
        when(purger.purge(OTHER_DOCUMENT_ID, NOW)).thenReturn(true);
        when(purger.completeObjectPurge(OTHER_DOCUMENT_ID, NOW)).thenReturn(true);
        when(documents.candidateKeys(OTHER_DOCUMENT_ID)).thenReturn(List.of(CANDIDATE_KEY));

        assertEquals(1, service().purgeExpired(10));
        verify(storage).deleteObject(CANDIDATE_KEY);
    }

    @Test
    void purgeIsPausedWithoutStorage() {
        when(provider.getIfAvailable()).thenReturn(null);

        assertEquals(0, service().purgeExpired(10));
        verify(documents, never()).findExpired(NOW, 10);
    }

    @Test
    void purgeRejectsOutOfRangeBatches() {
        assertThrows(IllegalArgumentException.class, () -> service().purgeExpired(0));
        assertThrows(IllegalArgumentException.class, () -> service().purgeExpired(501));
    }

    @Test
    void claimRequiresLiveDocumentWithoutHoldOrRetention() {
        owner();
        when(documents.findDocument(DOCUMENT)).thenReturn(Optional.of(target("LIVE")));
        when(documents.hasActiveHold(DOCUMENT_ID)).thenReturn(false);
        when(documents.underRetention(DOCUMENT_ID)).thenReturn(false);
        when(documents.claim(DOCUMENT_ID, NOW, NOW.plus(WINDOW))).thenReturn(true);

        DocumentDeletionService.Deletion deletion = service().claim(DOCUMENT, caller);

        assertEquals("CLAIMED", deletion.state());
        assertEquals(NOW.plus(WINDOW), deletion.restoreDeadline());
    }

    @Test
    void claimRejectsARetainedDocument() {
        owner();
        when(documents.findDocument(DOCUMENT)).thenReturn(Optional.of(target("LIVE")));
        when(documents.hasActiveHold(DOCUMENT_ID)).thenReturn(false);
        when(documents.underRetention(DOCUMENT_ID)).thenReturn(true);

        CommandConflictException exception = assertThrows(CommandConflictException.class,
            () -> service().claim(DOCUMENT, caller));

        assertEquals("Document is under retention", exception.getMessage());
        verify(documents, never()).claim(DOCUMENT_ID, NOW, NOW.plus(WINDOW));
    }

    @Test
    void claimRejectsHoldAndForeignOrTerminalStates() {
        owner();
        when(documents.findDocument(DOCUMENT)).thenReturn(Optional.of(target("LIVE")));
        when(documents.hasActiveHold(DOCUMENT_ID)).thenReturn(true);
        assertThrows(CommandConflictException.class, () -> service().claim(DOCUMENT, caller));

        when(documents.hasActiveHold(DOCUMENT_ID)).thenReturn(false);
        when(documents.underRetention(DOCUMENT_ID)).thenReturn(false);
        when(documents.claim(DOCUMENT_ID, NOW, NOW.plus(WINDOW))).thenReturn(false);
        assertThrows(CommandConflictException.class, () -> service().claim(DOCUMENT, caller));

        when(documents.findDocument(DOCUMENT)).thenReturn(Optional.of(target("CLAIMED")));
        assertThrows(CommandConflictException.class, () -> service().claim(DOCUMENT, caller));

        when(documents.findDocument(DOCUMENT)).thenReturn(Optional.of(target("DELETED")));
        assertThrows(CommandConflictException.class, () -> service().claim(DOCUMENT, caller));
    }

    @Test
    void claimMapsAHoldViolationToTheHoldConflict() {
        owner();
        when(documents.findDocument(DOCUMENT)).thenReturn(Optional.of(target("LIVE")));
        when(documents.hasActiveHold(DOCUMENT_ID)).thenReturn(false);
        when(documents.underRetention(DOCUMENT_ID)).thenReturn(false);
        when(documents.claim(DOCUMENT_ID, NOW, NOW.plus(WINDOW)))
            .thenThrow(new DataIntegrityViolationException("violation",
                new SQLException("Document has an active legal hold")));

        assertEquals("Document has an active legal hold",
            assertThrows(CommandConflictException.class, () -> service().claim(DOCUMENT, caller)).getMessage());
    }

    @Test
    void claimMapsARetentionViolationToTheRetentionConflict() {
        owner();
        when(documents.findDocument(DOCUMENT)).thenReturn(Optional.of(target("LIVE")));
        when(documents.hasActiveHold(DOCUMENT_ID)).thenReturn(false);
        when(documents.underRetention(DOCUMENT_ID)).thenReturn(false);
        when(documents.claim(DOCUMENT_ID, NOW, NOW.plus(WINDOW)))
            .thenThrow(new DataIntegrityViolationException("violation",
                new SQLException("Document is under retention")));

        assertEquals("Document is under retention",
            assertThrows(CommandConflictException.class, () -> service().claim(DOCUMENT, caller)).getMessage());
    }

    @Test
    void restoreReopensOnlyAnUnexpiredClaim() {
        owner();
        when(documents.findDocument(DOCUMENT)).thenReturn(Optional.of(
            new TargetRecord(DOCUMENT_ID, DOCUMENT, EMPLOYEE, "CLAIMED", NOW, NOW.plus(WINDOW))));
        when(documents.restore(DOCUMENT_ID, NOW)).thenReturn(true);

        DocumentDeletionService.Deletion restored = service().restore(DOCUMENT, caller);

        assertEquals("LIVE", restored.state());
        assertNull(restored.restoreDeadline());

        when(documents.findDocument(DOCUMENT)).thenReturn(Optional.of(
            new TargetRecord(DOCUMENT_ID, DOCUMENT, EMPLOYEE, "CLAIMED", NOW, NOW)));
        assertThrows(CommandConflictException.class, () -> service().restore(DOCUMENT, caller));

        when(documents.findDocument(DOCUMENT)).thenReturn(Optional.of(target("LIVE")));
        assertThrows(CommandConflictException.class, () -> service().restore(DOCUMENT, caller));
    }

    @Test
    void restoreFailsWhenTheClaimChangedUnderneath() {
        owner();
        when(documents.findDocument(DOCUMENT)).thenReturn(Optional.of(
            new TargetRecord(DOCUMENT_ID, DOCUMENT, EMPLOYEE, "CLAIMED", NOW, NOW.plus(WINDOW))));
        when(documents.restore(DOCUMENT_ID, NOW)).thenReturn(false);

        assertThrows(CommandConflictException.class, () -> service().restore(DOCUMENT, caller));
    }

    @Test
    void foreignCallerSeesNotFoundAndBadWindowIsRejected() {
        owner();
        when(documents.findDocument(DOCUMENT)).thenReturn(Optional.of(
            new TargetRecord(DOCUMENT_ID, DOCUMENT, UUID.randomUUID(), "LIVE", null, null)));
        assertThrows(ResourceNotFoundException.class, () -> service().claim(DOCUMENT, caller));

        assertThrows(IllegalArgumentException.class, () -> new DocumentDeletionService(documents, purger, provider,
            access, CLOCK, Duration.ZERO));
    }

    @Test
    void statusReportsTheCurrentStateAndRequestTime() {
        owner();
        when(documents.findDocument(DOCUMENT)).thenReturn(Optional.of(
            new TargetRecord(DOCUMENT_ID, DOCUMENT, EMPLOYEE, "CLAIMED", NOW, NOW.plus(WINDOW))));

        DocumentDeletionService.Deletion status = service().status(DOCUMENT, caller);

        assertEquals("CLAIMED", status.state());
        assertEquals(DOCUMENT, status.documentPublicId());
        assertEquals(NOW, status.requestedAt());
    }

    private DocumentDeletionService service() {
        return new DocumentDeletionService(documents, purger, provider, access, CLOCK, WINDOW);
    }

    private void owner() {
        when(access.requireActive(caller, NOW)).thenReturn(account);
        when(account.getId()).thenReturn(ACCOUNT_ID);
        when(access.ownEmployeePublicId(account)).thenReturn(Optional.of(EMPLOYEE));
        when(access.activeRoles(ACCOUNT_ID, NOW)).thenReturn(List.of(HrisRole.EMPLOYEE));
    }

    private static TargetRecord target(String state) {
        return new TargetRecord(DOCUMENT_ID, DOCUMENT, EMPLOYEE, state, null, null);
    }
}
