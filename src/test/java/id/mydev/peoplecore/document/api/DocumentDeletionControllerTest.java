package id.mydev.peoplecore.document.api;

import id.mydev.peoplecore.document.application.command.DocumentDeletionCommands;
import id.mydev.peoplecore.document.application.service.DocumentDeletionService;
import id.mydev.peoplecore.document.application.service.DocumentDeletionService.Deletion;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentDeletionControllerTest {
    private final DocumentDeletionCommands commands = mock(DocumentDeletionCommands.class);
    private final DocumentDeletionService deletions = mock(DocumentDeletionService.class);
    private final Authentication caller = mock(Authentication.class);
    private final UUID documentId = UUID.randomUUID();
    private final Deletion claimed = new Deletion(documentId, "CLAIMED",
        Instant.parse("2026-11-10T10:00:00Z"), Instant.parse("2026-12-10T10:00:00Z"));
    private final DocumentDeletionController controller = new DocumentDeletionController(commands, deletions);

    @Test
    void claimingDeletionReturnsCreatedWithLocationAndNoStore() {
        when(commands.claim(caller, "key", documentId)).thenReturn(claimed);

        var response = controller.claim(caller, documentId, "key");

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("/api/v1/documents/" + documentId + "/deletion",
            response.getHeaders().getLocation().toString());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals(claimed, response.getBody().data());
    }

    @Test
    void restoringDeletionReturnsTheLiveState() {
        Deletion restored = new Deletion(documentId, "LIVE", null, null);
        when(commands.restore(caller, "key", documentId)).thenReturn(restored);

        var response = controller.restore(caller, documentId, "key");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(restored, response.getBody().data());
        assertEquals("no-store", response.getHeaders().getCacheControl());
    }

    @Test
    void statusDelegatesToTheOwnerScopedQuery() {
        when(deletions.status(documentId, caller)).thenReturn(claimed);

        var response = controller.status(caller, documentId);

        assertEquals(claimed, response.getBody().data());
        verify(deletions).status(documentId, caller);
    }
}
