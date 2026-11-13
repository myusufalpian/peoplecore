package id.mydev.peoplecore.document.infrastructure.config;

import id.mydev.peoplecore.document.application.service.DocumentDeletionService;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DocumentDeletionSchedulerTest {
    private final DocumentDeletionService deletions = mock(DocumentDeletionService.class);
    private final Environment enabled = environment(true);

    @Test
    void pollPurgesABoundedBatch() {
        when(deletions.purgeExpired(50)).thenReturn(2);

        new DocumentDeletionScheduler(deletions, enabled, 50).poll();

        verify(deletions).purgeExpired(50);
    }

    @Test
    void pollWithNothingDueIsQuiet() {
        when(deletions.purgeExpired(50)).thenReturn(0);

        new DocumentDeletionScheduler(deletions, enabled, 50).poll();

        verify(deletions).purgeExpired(50);
    }

    @Test
    void pollDoesNothingWhileTheFlagIsOff() {
        new DocumentDeletionScheduler(deletions, environment(false), 50).poll();

        verifyNoInteractions(deletions);
    }

    @Test
    void pollSwallowsTemporaryFailuresAndRejectsBadBatches() {
        when(deletions.purgeExpired(5)).thenThrow(new DataAccessResourceFailureException("db down"));
        var scheduler = new DocumentDeletionScheduler(deletions, enabled, 5);

        assertDoesNotThrow(scheduler::poll);
        assertThrows(IllegalArgumentException.class, () -> new DocumentDeletionScheduler(deletions, enabled, 0));
        assertThrows(IllegalArgumentException.class, () -> new DocumentDeletionScheduler(deletions, enabled, 501));
    }

    private static Environment environment(boolean enabled) {
        return new MockEnvironment().withProperty("peoplecore.documents.deletion.enabled", Boolean.toString(enabled));
    }
}
