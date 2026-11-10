package id.mydev.peoplecore.document.application.service;

import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.document.application.service.VerifiedDocumentAccess.Purpose;
import id.mydev.peoplecore.document.infrastructure.storage.PrivateDocumentStorage;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Service
public class DocumentVersionContentService {
    private final JdbcTemplate jdbc;
    private final ObjectProvider<PrivateDocumentStorage> storage;

    public DocumentVersionContentService(JdbcTemplate jdbc, ObjectProvider<PrivateDocumentStorage> storage) {
        this.jdbc = jdbc;
        this.storage = storage;
    }

    public Content open(UUID documentPublicId, UUID versionPublicId, UUID employeePublicId, Purpose purpose) {
        Objects.requireNonNull(documentPublicId, "documentPublicId");
        Objects.requireNonNull(versionPublicId, "versionPublicId");
        Objects.requireNonNull(employeePublicId, "employeePublicId");
        Objects.requireNonNull(purpose, "purpose");
        List<Metadata> rows = jdbc.query("SELECT a.public_id,v.media_type,v.byte_size FROM document_version v "
                + "JOIN private_document d ON d.id=v.document_id "
                + "JOIN employees e ON e.id=d.employee_id "
                + "JOIN document_verification_attempt a ON a.id=v.attempt_id AND a.document_id=d.id "
                + "WHERE d.public_id=? AND v.public_id=? AND e.public_id=? AND d.purpose=? AND d.deletion_state='LIVE'",
            (row, index) -> new Metadata(row.getObject(1, UUID.class), row.getString(2), row.getLong(3)),
            documentPublicId, versionPublicId, employeePublicId, purpose.name());
        Metadata metadata = rows.stream().findFirst().orElseThrow(ResourceNotFoundException::new);
        PrivateDocumentStorage configured = storage.getIfAvailable();
        if (configured == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Document storage unavailable");
        }
        try {
            return new Content(configured.openCandidate(metadata.attemptPublicId()), metadata.mediaType(), metadata.byteSize());
        } catch (S3Exception | SdkClientException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Document storage unavailable");
        }
    }

    public record Content(InputStream body, String mediaType, long byteSize) { }

    private record Metadata(UUID attemptPublicId, String mediaType, long byteSize) { }
}
