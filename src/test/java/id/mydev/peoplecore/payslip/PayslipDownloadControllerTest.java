package id.mydev.peoplecore.payslip;

import id.mydev.peoplecore.document.application.service.DocumentVersionContentService;
import id.mydev.peoplecore.document.application.service.RevocableDocumentTransfer;
import id.mydev.peoplecore.document.domain.model.Purpose;
import id.mydev.peoplecore.payslip.api.PayslipDownloadController;
import id.mydev.peoplecore.payslip.application.PayslipModels.PublishedVersion;
import id.mydev.peoplecore.payslip.application.PayslipService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PayslipDownloadControllerTest {
    private final PayslipService payslips = mock(PayslipService.class);
    private final DocumentVersionContentService content = mock(DocumentVersionContentService.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<RevocableDocumentTransfer> transfers = mock(ObjectProvider.class);
    private final Authentication caller = mock(Authentication.class);
    private final UUID publicationId = UUID.randomUUID();
    private final PublishedVersion version = new PublishedVersion(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    private final PayslipDownloadController controller = new PayslipDownloadController(payslips, content, transfers);

    @Test
    void streamsOnlyTheVersionBoundToTheCurrentPublication() throws Exception {
        byte[] bytes = {1, 2, 3};
        try (var transfer = new RevocableDocumentTransfer(1, Duration.ofSeconds(1), Duration.ofSeconds(1))) {
            when(transfers.getIfAvailable()).thenReturn(transfer);
            when(payslips.requirePublishedVersion(publicationId, caller)).thenReturn(version);
            when(content.open(version.documentPublicId(), version.versionPublicId(), version.employeePublicId(), Purpose.PAYSLIP))
                .thenReturn(new DocumentVersionContentService.Content(new ByteArrayInputStream(bytes), "application/pdf", bytes.length));

            var response = controller.content(caller, publicationId);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            response.getBody().writeTo(output);

            assertArrayEquals(bytes, output.toByteArray());
            assertEquals("no-store", response.getHeaders().getCacheControl());
        }
    }

    @Test
    void changedPublicationCannotOpenOldVersion() {
        var replacement = new PublishedVersion(version.employeePublicId(), UUID.randomUUID(), UUID.randomUUID());
        try (var transfer = new RevocableDocumentTransfer(1, Duration.ofSeconds(1), Duration.ofSeconds(1))) {
            when(transfers.getIfAvailable()).thenReturn(transfer);
            when(payslips.requirePublishedVersion(publicationId, caller)).thenReturn(version, replacement);
            var response = controller.content(caller, publicationId);

            assertThrows(IOException.class, () -> response.getBody().writeTo(new ByteArrayOutputStream()));
            verify(content, never()).open(version.documentPublicId(), version.versionPublicId(), version.employeePublicId(), Purpose.PAYSLIP);
        }
    }

    @Test
    void missingTransferConfigurationFailsClosed() {
        when(payslips.requirePublishedVersion(publicationId, caller)).thenReturn(version);
        assertThrows(ResponseStatusException.class, () -> controller.content(caller, publicationId));
        verify(content, never()).open(version.documentPublicId(), version.versionPublicId(), version.employeePublicId(), Purpose.PAYSLIP);
    }

    @Test
    void revokedAccountCannotReceiveBytesWhenTransferStarts() {
        byte[] bytes = {1, 2, 3};
        try (var transfer = new RevocableDocumentTransfer(1, Duration.ofSeconds(1), Duration.ofSeconds(1))) {
            when(transfers.getIfAvailable()).thenReturn(transfer);
            when(payslips.requirePublishedVersion(publicationId, caller)).thenReturn(version, version)
                .thenThrow(new AccessDeniedException("Account access ended"));
            when(content.open(version.documentPublicId(), version.versionPublicId(), version.employeePublicId(), Purpose.PAYSLIP))
                .thenReturn(new DocumentVersionContentService.Content(new ByteArrayInputStream(bytes), "application/pdf", bytes.length));
            var response = controller.content(caller, publicationId);
            ByteArrayOutputStream output = new ByteArrayOutputStream();

            assertThrows(IOException.class, () -> response.getBody().writeTo(output));
            assertEquals(0, output.size());
        }
    }
}
