package id.mydev.peoplecore.document.application.service;

import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.document.domain.model.Purpose;
import id.mydev.peoplecore.document.domain.repository.DocumentVersionContentRepository;
import id.mydev.peoplecore.document.domain.repository.DocumentVersionContentRepository.MetadataRecord;
import id.mydev.peoplecore.document.infrastructure.storage.PrivateDocumentStorage;
import java.io.ByteArrayInputStream;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DocumentVersionContentServiceTest {
    private final DocumentVersionContentRepository documents = mock(DocumentVersionContentRepository.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<PrivateDocumentStorage> provider = mock(ObjectProvider.class);
    private final DocumentVersionContentService service = new DocumentVersionContentService(documents, provider);

    @Test
    void inaccessibleVersionDoesNotOpenStorage() {
        UUID document = UUID.randomUUID();
        UUID version = UUID.randomUUID();
        UUID employee = UUID.randomUUID();
        when(documents.findMetadata(document, version, employee, "PAYSLIP")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
            () -> service.open(document, version, employee, Purpose.PAYSLIP));
        verifyNoInteractions(provider);
    }

    @Test
    void opensThePinnedVersionAndFailsClosedWithoutStorage() {
        UUID document = UUID.randomUUID();
        UUID version = UUID.randomUUID();
        UUID employee = UUID.randomUUID();
        UUID attempt = UUID.randomUUID();
        when(documents.findMetadata(document, version, employee, "PAYSLIP"))
            .thenReturn(Optional.of(new MetadataRecord(attempt, "application/pdf", 12L)));

        assertThrows(ResponseStatusException.class,
            () -> service.open(document, version, employee, Purpose.PAYSLIP));

        PrivateDocumentStorage storage = mock(PrivateDocumentStorage.class);
        ByteArrayInputStream bytes = new ByteArrayInputStream(new byte[] {1});
        when(provider.getIfAvailable()).thenReturn(storage);
        when(storage.openCandidate(attempt)).thenReturn(bytes);

        var content = service.open(document, version, employee, Purpose.PAYSLIP);

        assertSame(bytes, content.body());
        assertEquals("application/pdf", content.mediaType());
        assertEquals(12, content.byteSize());
    }
}
