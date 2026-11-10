package id.mydev.peoplecore.document.api;

import id.mydev.peoplecore.document.application.command.DocumentCommands;
import id.mydev.peoplecore.document.application.service.DocumentStatusQuery;
import id.mydev.peoplecore.document.infrastructure.config.DocumentVerificationScheduler;
import id.mydev.peoplecore.support.JwtAuthFixture;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DocumentUploadControllerTest {
    private final DocumentCommands commands = mock(DocumentCommands.class);
    private final DocumentStatusQuery status = mock(DocumentStatusQuery.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<DocumentVerificationScheduler> schedulers = mock(ObjectProvider.class);
    private final DocumentUploadController controller = new DocumentUploadController(commands, status, schedulers);

    @AfterEach
    void clearCaller() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void uploadAuthorizationFailsClosedWithoutVerificationWorker() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
            () -> controller.uploadUrl(UUID.randomUUID(), "key"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());
        verifyNoInteractions(commands);
    }

    @Test
    void issuedUrlIsNotCacheableOrExposedThroughToString() throws Exception {
        Authentication caller = JwtAuthFixture.token("https://identity.example", "owner");
        SecurityContextHolder.getContext().setAuthentication(caller);
        UUID documentId = UUID.randomUUID();
        var url = new DocumentCommands.UploadUrl(URI.create("https://storage.example/staging?signature=secret").toURL(),
            Map.of(), Instant.parse("2026-11-02T00:05:00Z"));
        when(schedulers.getIfAvailable()).thenReturn(mock(DocumentVerificationScheduler.class));
        when(commands.reserve(caller, "key", documentId)).thenReturn(url);

        var response = controller.uploadUrl(documentId, "key");
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals(url, response.getBody().data());
        assertFalse(url.toString().contains("secret"));
    }
}
