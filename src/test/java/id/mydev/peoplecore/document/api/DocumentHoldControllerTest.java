package id.mydev.peoplecore.document.api;

import id.mydev.peoplecore.document.application.command.DocumentHoldCommands;
import id.mydev.peoplecore.document.application.service.DocumentHoldService;
import id.mydev.peoplecore.document.application.service.DocumentHoldService.Hold;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentHoldControllerTest {
    private final DocumentHoldCommands commands = mock(DocumentHoldCommands.class);
    private final DocumentHoldService holds = mock(DocumentHoldService.class);
    private final Authentication caller = mock(Authentication.class);
    private final UUID documentId = UUID.randomUUID();
    private final Hold hold = new Hold(UUID.randomUUID(), documentId, "Litigation hold",
        Instant.parse("2026-11-10T10:00:00Z"), null);
    private final DocumentHoldController controller = new DocumentHoldController(commands, holds);

    @Test
    void placingAHolderReturnsCreatedWithLocationAndNoStore() {
        when(commands.place(caller, "key", documentId, "Litigation hold")).thenReturn(hold);

        var response = controller.place(caller, documentId, "key", new DocumentHoldController.HoldRequest("Litigation hold"));

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("/api/v1/documents/" + documentId + "/holds/" + hold.publicId(),
            response.getHeaders().getLocation().toString());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals(hold, response.getBody().data());
    }

    @Test
    void activeHoldsAreListedForAuthorizedCallersOnlyThroughTheService() {
        when(holds.active(documentId, caller)).thenReturn(List.of(hold));

        var response = controller.active(caller, documentId);

        assertEquals(List.of(hold), response.getBody().data());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        verify(holds).active(documentId, caller);
    }

    @Test
    void releaseDelegatesWithTheReasonAndReturnsTheReleasedHold() {
        Hold released = new Hold(hold.publicId(), documentId, hold.reason(), hold.createdAt(),
            Instant.parse("2026-11-11T10:00:00Z"));
        when(commands.release(caller, "key", documentId, hold.publicId(), "Matter closed")).thenReturn(released);

        var response = controller.release(caller, documentId, hold.publicId(), "key",
            new DocumentHoldController.HoldRequest("Matter closed"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(released, response.getBody().data());
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
    }
}
