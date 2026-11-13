package id.mydev.peoplecore.document.application.service;

import id.mydev.peoplecore.document.domain.repository.DocumentDeletionRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentPurgeWorkerTest {
    private static final Instant NOW = Instant.parse("2026-11-10T10:00:00Z");
    private static final long DOCUMENT_ID = 7L;

    private final DocumentDeletionRepository documents = mock(DocumentDeletionRepository.class);
    private final DocumentPurgeWorker worker = new DocumentPurgeWorker(documents);

    @Test
    void markingAPurgedClaimWritesItsAuditEvent() {
        when(documents.markPurged(DOCUMENT_ID, NOW)).thenReturn(true);
        when(documents.readyForObjectPurge(DOCUMENT_ID)).thenReturn(true);

        assertTrue(worker.purge(DOCUMENT_ID, NOW));
        verify(documents).recordPurgeAudit(DOCUMENT_ID, NOW);
    }

    @Test
    void aClaimThatChangedIsNotAudited() {
        when(documents.markPurged(DOCUMENT_ID, NOW)).thenReturn(false);

        assertFalse(worker.purge(DOCUMENT_ID, NOW));
        verify(documents, never()).recordPurgeAudit(DOCUMENT_ID, NOW);
    }

    @Test
    void aPendingObjectPurgeIsRetriedWithoutAnotherAudit() {
        when(documents.readyForObjectPurge(DOCUMENT_ID)).thenReturn(true);

        assertTrue(worker.purge(DOCUMENT_ID, NOW));
        verify(documents, never()).recordPurgeAudit(DOCUMENT_ID, NOW);
    }

    @Test
    void onlyTheFirstCompletionAcknowledgesTheObjectPurge() {
        when(documents.completeObjectPurge(DOCUMENT_ID, NOW)).thenReturn(true, false);

        assertTrue(worker.completeObjectPurge(DOCUMENT_ID, NOW));
        assertFalse(worker.completeObjectPurge(DOCUMENT_ID, NOW));
    }

    @Test
    void metadataDeletionCommitsItsAuditEvenWhenAnUnsettledCopyDefersObjectDeletion() {
        when(documents.markPurged(DOCUMENT_ID, NOW)).thenReturn(true);

        assertFalse(worker.purge(DOCUMENT_ID, NOW));
        verify(documents).recordPurgeAudit(DOCUMENT_ID, NOW);
    }
}
