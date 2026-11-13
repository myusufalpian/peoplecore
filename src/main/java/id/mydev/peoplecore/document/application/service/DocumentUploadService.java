package id.mydev.peoplecore.document.application.service;

import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.common.command.CommandConflictException;
import id.mydev.peoplecore.document.domain.model.Purpose;
import id.mydev.peoplecore.document.domain.repository.DocumentUploadRepository;
import id.mydev.peoplecore.document.domain.repository.DocumentUploadRepository.MetadataRecord;
import id.mydev.peoplecore.document.domain.repository.DocumentUploadRepository.PendingDocumentRecord;
import id.mydev.peoplecore.organization.domain.model.Employee;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class DocumentUploadService {
    private final DocumentUploadRepository documents;
    private final EntityManager entities;
    private final DocumentAccessPolicy access;
    private final Clock clock;

    public DocumentUploadService(DocumentUploadRepository documents, EntityManager entities,
                                 DocumentAccessPolicy access, Clock clock) {
        this.documents = documents;
        this.entities = entities;
        this.access = access;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Upload create(UUID documentPublicId, Request request, Authentication caller) {
        Objects.requireNonNull(documentPublicId, "documentPublicId");
        validate(request);
        Employee employee = access.requireUpload(caller, request.employeePublicId(), request.purpose());
        documents.ensureAccount(employee.getId(), Instant.now(clock));
        documents.lockAccount(employee.getId());
        entities.flush();
        entities.clear();
        employee = access.requireUpload(caller, request.employeePublicId(), request.purpose());
        documents.insertDocument(documentPublicId, employee.getId(), request.purpose(), request.fileName(),
            request.mediaType(), request.byteSize(), Instant.now(clock));
        return new Upload(documentPublicId, "PENDING_UPLOAD");
    }

    public void verifyCreate(Request request, Authentication caller) {
        validate(request);
        access.requireUpload(caller, request.employeePublicId(), request.purpose());
    }

    @Transactional(readOnly = true)
    public void requireUploadAuthority(UUID documentPublicId, Authentication caller) {
        PendingDocumentRecord document = find(documentPublicId, false);
        access.requireUpload(caller, document.employeePublicId(), document.purpose());
        if (!"LIVE".equals(document.deletionState())) {
            throw new ResourceNotFoundException();
        }
    }

    public Metadata status(UUID documentPublicId, Authentication caller) {
        PendingDocumentRecord document = find(documentPublicId, false);
        access.requireUpload(caller, document.employeePublicId(), document.purpose());
        if (!"LIVE".equals(document.deletionState())) {
            throw new ResourceNotFoundException();
        }
        MetadataRecord metadata = documents.findMetadata(documentPublicId)
            .orElseThrow(ResourceNotFoundException::new);
        return new Metadata(metadata.publicId(), metadata.purpose(), metadata.status(), metadata.mediaType(),
            metadata.byteSize(), metadata.createdAt(), metadata.verifiedAt());
    }

    @Transactional(readOnly = true)
    public PendingUpload currentUpload(UUID documentPublicId, Authentication caller) {
        PendingDocumentRecord document = find(documentPublicId, false);
        access.requireUpload(caller, document.employeePublicId(), document.purpose());
        if (!"PENDING_UPLOAD".equals(document.status()) || !"LIVE".equals(document.deletionState())
            || document.uploadPublicId() == null) {
            throw new CommandConflictException("Document has no pending upload");
        }
        return pendingUpload(document);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public PendingUpload reserve(UUID documentPublicId, UUID uploadPublicId, Authentication caller) {
        Objects.requireNonNull(uploadPublicId, "uploadPublicId");
        PendingDocumentRecord document = lockAuthorized(documentPublicId, caller);
        if (!"PENDING_UPLOAD".equals(document.status()) || !"LIVE".equals(document.deletionState())) {
            throw new CommandConflictException("Document cannot accept an upload");
        }
        if (document.uploadPublicId() != null) {
            documents.abandonReservedUpload(document.uploadPublicId());
        }
        Instant now = clock.instant();
        long generation = documents.advanceUploadGeneration(document.id(), now);
        long uploadId = documents.insertUploadAttempt(uploadPublicId, document.id(), generation,
            "staging/" + uploadPublicId, document.byteSize(), now);
        documents.attachUpload(document.id(), uploadId);
        access.requireUpload(caller, document.employeePublicId(), document.purpose());
        return new PendingUpload(document.publicId(), document.employeePublicId(), document.purpose(),
            uploadPublicId, document.mediaType(), document.byteSize());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Upload queueVerification(PendingUpload expected, UUID attemptPublicId, String etag, Authentication caller) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(attemptPublicId, "attemptPublicId");
        if (!StringUtils.hasText(etag)) {
            throw new IllegalArgumentException("Object identity is required");
        }
        PendingDocumentRecord document = lockAuthorized(expected.documentPublicId(), caller);
        if (!"PENDING_UPLOAD".equals(document.status()) || !"LIVE".equals(document.deletionState())
            || !Objects.equals(document.uploadPublicId(), expected.uploadPublicId())
            || !Objects.equals(document.employeePublicId(), expected.employeePublicId())
            || document.purpose() != expected.purpose() || document.byteSize() != expected.byteSize()
            || !Objects.equals(document.mediaType(), expected.mediaType())) {
            throw new CommandConflictException("Document upload changed");
        }
        Instant now = clock.instant();
        if (!documents.completeUpload(expected.uploadPublicId(), etag, now)) {
            throw new CommandConflictException("Document upload is no longer pending");
        }
        long generation = documents.advanceVerificationGeneration(document.id(), now);
        documents.queueVerificationAttempt(attemptPublicId, document.id(), generation,
            "candidates/" + attemptPublicId, now);
        access.requireUpload(caller, document.employeePublicId(), document.purpose());
        return new Upload(document.publicId(), "VERIFYING");
    }

    private PendingDocumentRecord lockAuthorized(UUID publicId, Authentication caller) {
        PendingDocumentRecord before = find(publicId, false);
        access.requireUpload(caller, before.employeePublicId(), before.purpose());
        documents.lockAccount(before.employeeId());
        PendingDocumentRecord locked = find(publicId, true);
        entities.flush();
        entities.clear();
        access.requireUpload(caller, locked.employeePublicId(), locked.purpose());
        return locked;
    }

    private PendingDocumentRecord find(UUID publicId, boolean lock) {
        Objects.requireNonNull(publicId, "publicId");
        return documents.findDocument(publicId, lock).orElseThrow(ResourceNotFoundException::new);
    }

    private static PendingUpload pendingUpload(PendingDocumentRecord document) {
        return new PendingUpload(document.publicId(), document.employeePublicId(), document.purpose(),
            document.uploadPublicId(), document.mediaType(), document.byteSize());
    }

    private static void validate(Request request) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(request.employeePublicId(), "employeePublicId");
        Objects.requireNonNull(request.purpose(), "purpose");
        if (!StringUtils.hasText(request.fileName()) || request.fileName().length() > 255
            || request.fileName().chars().anyMatch(Character::isISOControl)
            || request.byteSize() < 1 || request.byteSize() > DocumentContentInspector.MAX_BYTES) {
            throw new IllegalArgumentException("Invalid document metadata");
        }
        boolean allowed = "application/pdf".equals(request.mediaType())
            || (request.purpose() == Purpose.CLAIM_EVIDENCE
                && ("image/jpeg".equals(request.mediaType()) || "image/png".equals(request.mediaType())));
        if (!allowed) {
            throw new IllegalArgumentException("Unsupported document media type");
        }
    }

    public record Request(UUID employeePublicId, Purpose purpose, String fileName, String mediaType, long byteSize) {
    }

    public record Upload(UUID publicId, String status) { }

    public record Metadata(UUID publicId, Purpose purpose, String status, String mediaType, Long byteSize,
                           Instant createdAt, Instant verifiedAt) { }

    public record PendingUpload(UUID documentPublicId, UUID employeePublicId, Purpose purpose, UUID uploadPublicId,
                                String mediaType, long byteSize) {
    }
}
