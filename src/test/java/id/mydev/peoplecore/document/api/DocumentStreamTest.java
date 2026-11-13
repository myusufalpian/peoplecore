package id.mydev.peoplecore.document.api;

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
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.nio.charset.StandardCharsets;

class DocumentStreamTest {
    private static final UUID DOCUMENT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID VERSION = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID EMPLOYEE = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final DocumentTarget TARGET = new DocumentTarget(DOCUMENT, VERSION, EMPLOYEE, "application/pdf");
    private static final byte[] CONTENT = "verified-bytes".getBytes(StandardCharsets.UTF_8);

    private final ObjectProvider<RevocableDocumentTransfer> transfers = mock(ObjectProvider.class);
    private final DocumentVersionContentService content = mock(DocumentVersionContentService.class);

    @Test
    void failsClosedWhenNoTransferBeanIsConfigured() {
        when(transfers.getIfAvailable()).thenReturn(null);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
            () -> DocumentStream.attachment(transfers, content, TARGET, Purpose.CLAIM_EVIDENCE, "receipt.pdf",
                () -> true));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatusCode());
    }

    @Test
    void rejectsUnsafeAttachmentFileNames() {
        when(transfers.getIfAvailable()).thenReturn(null);

        assertThrows(NullPointerException.class,
            () -> DocumentStream.attachment(transfers, content, TARGET, Purpose.CLAIM_EVIDENCE, null, () -> true));
        for (String name : new String[] {"", "   ", "a\"b.pdf", "a\\b.pdf", "a\nb.pdf", "a\u007fb.pdf"}) {
            assertThrows(IllegalArgumentException.class,
                () -> DocumentStream.attachment(transfers, content, TARGET, Purpose.CLAIM_EVIDENCE, name, () -> true),
                "expected rejection for " + name);
        }
        verify(content, never()).open(DOCUMENT, VERSION, EMPLOYEE, Purpose.CLAIM_EVIDENCE);
    }

    @Test
    void streamsVerifiedBytesWithAttachmentHeaders() throws IOException {
        try (RevocableDocumentTransfer transfer =
                 new RevocableDocumentTransfer(1, Duration.ofMillis(50), Duration.ofSeconds(1))) {
            when(transfers.getIfAvailable()).thenReturn(transfer);
            when(content.open(DOCUMENT, VERSION, EMPLOYEE, Purpose.CLAIM_EVIDENCE))
                .thenReturn(new DocumentVersionContentService.Content(new ByteArrayInputStream(CONTENT),
                    "application/pdf", CONTENT.length));

            var response = DocumentStream.attachment(transfers, content, TARGET, Purpose.CLAIM_EVIDENCE,
                "receipt.pdf", () -> true);
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            response.getBody().writeTo(sink);

            assertArrayEquals(CONTENT, sink.toByteArray());
            assertEquals("application/pdf", response.getHeaders().getContentType().toString());
            assertEquals("no-store", response.getHeaders().getCacheControl());
            assertEquals("attachment; filename=\"receipt.pdf\"",
                response.getHeaders().getFirst("Content-Disposition"));
            assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
        }
    }

    @Test
    void stopsBeforeMaterializingWhenAuthorizationEnded() {
        try (RevocableDocumentTransfer transfer =
                 new RevocableDocumentTransfer(1, Duration.ofMillis(50), Duration.ofSeconds(1))) {
            when(transfers.getIfAvailable()).thenReturn(transfer);

            var response = DocumentStream.attachment(transfers, content, TARGET, Purpose.CLAIM_EVIDENCE,
                "receipt.pdf", () -> false);

            assertThrows(IOException.class, () -> response.getBody().writeTo(new ByteArrayOutputStream()));
            verify(content, never()).open(DOCUMENT, VERSION, EMPLOYEE, Purpose.CLAIM_EVIDENCE);
        }
    }
}
