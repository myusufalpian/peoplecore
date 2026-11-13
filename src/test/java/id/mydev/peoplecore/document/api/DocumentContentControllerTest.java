package id.mydev.peoplecore.document.api;

import id.mydev.peoplecore.document.application.service.DocumentContentQuery;
import id.mydev.peoplecore.document.application.service.DocumentTarget;
import id.mydev.peoplecore.document.application.service.DocumentVersionContentService;
import id.mydev.peoplecore.document.application.service.RevocableDocumentTransfer;
import id.mydev.peoplecore.document.domain.model.Purpose;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentContentControllerTest {
    private final DocumentContentQuery documents = mock(DocumentContentQuery.class);
    private final DocumentVersionContentService content = mock(DocumentVersionContentService.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<RevocableDocumentTransfer> transfers = mock(ObjectProvider.class);
    private final Authentication caller = mock(Authentication.class);
    private final UUID documentId = UUID.randomUUID();
    private final DocumentTarget target = new DocumentTarget(documentId, UUID.randomUUID(),
        UUID.randomUUID(), "application/pdf");
    private final DocumentContentController controller = new DocumentContentController(documents, content, transfers);

    @Test
    void ownerReceivesOnlyTheCurrentVerifiedEvidence() throws Exception {
        byte[] bytes = {4, 5, 6};
        try (var transfer = new RevocableDocumentTransfer(1, Duration.ofSeconds(1), Duration.ofSeconds(1))) {
            when(transfers.getIfAvailable()).thenReturn(transfer);
            when(documents.requireOwnedClaimEvidence(documentId, caller)).thenReturn(target);
            when(content.open(target.documentPublicId(), target.versionPublicId(), target.employeePublicId(),
                Purpose.CLAIM_EVIDENCE))
                .thenReturn(new DocumentVersionContentService.Content(new ByteArrayInputStream(bytes),
                    "application/pdf", bytes.length));

            var response = controller.content(caller, documentId);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            response.getBody().writeTo(output);

            assertArrayEquals(bytes, output.toByteArray());
            assertEquals("no-store", response.getHeaders().getCacheControl());
            assertTrue(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION).startsWith("attachment"));
            assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
        }
    }

    @Test
    void revokedAccessStopsTheTransferBeforeAnyBytesAreOpened() {
        var replacement = new DocumentTarget(documentId, UUID.randomUUID(), target.employeePublicId(),
            "application/pdf");
        try (var transfer = new RevocableDocumentTransfer(1, Duration.ofSeconds(1), Duration.ofSeconds(1))) {
            when(transfers.getIfAvailable()).thenReturn(transfer);
            when(documents.requireOwnedClaimEvidence(documentId, caller)).thenReturn(target, replacement);
            var response = controller.content(caller, documentId);

            assertThrows(IOException.class, () -> response.getBody().writeTo(new ByteArrayOutputStream()));
            verify(content, never()).open(target.documentPublicId(), target.versionPublicId(),
                target.employeePublicId(), Purpose.CLAIM_EVIDENCE);
        }
    }

    @Test
    void missingTransferConfigurationFailsClosed() {
        when(documents.requireOwnedClaimEvidence(documentId, caller)).thenReturn(target);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
            () -> controller.content(caller, documentId));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());
        verify(content, never()).open(target.documentPublicId(), target.versionPublicId(),
            target.employeePublicId(), Purpose.CLAIM_EVIDENCE);
    }
}
