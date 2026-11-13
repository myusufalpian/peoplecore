package id.mydev.peoplecore.document.application.service;

import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.document.domain.model.Purpose;
import id.mydev.peoplecore.document.domain.repository.DocumentVersionContentRepository;
import id.mydev.peoplecore.document.domain.repository.DocumentVersionContentRepository.MetadataRecord;
import id.mydev.peoplecore.document.infrastructure.storage.PrivateDocumentStorage;
import java.io.InputStream;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Service
public class DocumentVersionContentService {
    private final DocumentVersionContentRepository documents;
    private final ObjectProvider<PrivateDocumentStorage> storage;

    public DocumentVersionContentService(DocumentVersionContentRepository documents,
                                         ObjectProvider<PrivateDocumentStorage> storage) {
        this.documents = documents;
        this.storage = storage;
    }

    public Content open(UUID documentPublicId, UUID versionPublicId, UUID employeePublicId, Purpose purpose) {
        Objects.requireNonNull(documentPublicId, "documentPublicId");
        Objects.requireNonNull(versionPublicId, "versionPublicId");
        Objects.requireNonNull(employeePublicId, "employeePublicId");
        Objects.requireNonNull(purpose, "purpose");
        MetadataRecord metadata = documents.findMetadata(documentPublicId, versionPublicId, employeePublicId,
            purpose.name()).orElseThrow(ResourceNotFoundException::new);
        PrivateDocumentStorage configured = storage.getIfAvailable();
        if (configured == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Document storage unavailable");
        }
        try {
            return new Content(configured.openCandidate(metadata.attemptPublicId()), metadata.mediaType(),
                metadata.byteSize());
        } catch (S3Exception | SdkClientException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Document storage unavailable");
        }
    }

    public record Content(InputStream body, String mediaType, long byteSize) { }
}
