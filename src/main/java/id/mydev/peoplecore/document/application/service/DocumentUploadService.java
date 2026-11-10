package id.mydev.peoplecore.document.application.service;

import id.mydev.peoplecore.document.application.service.VerifiedDocumentAccess.Purpose;
import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.common.command.CommandConflictException;
import id.mydev.peoplecore.organization.domain.model.Employee;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class DocumentUploadService {
    private final JdbcTemplate jdbc;
    private final EntityManager entities;
    private final DocumentAccessPolicy access;
    private final Clock clock;

    public DocumentUploadService(JdbcTemplate jdbc, EntityManager entities, DocumentAccessPolicy access, Clock clock) {
        this.jdbc = jdbc;
        this.entities = entities;
        this.access = access;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Upload create(UUID documentPublicId, Request request, Authentication caller) {
        Objects.requireNonNull(documentPublicId, "documentPublicId");
        validate(request);
        Employee employee = access.requireUpload(caller, request.employeePublicId(), request.purpose());
        Timestamp now = Timestamp.from(Instant.now(clock));
        jdbc.update("INSERT INTO document_account(employee_id,created_at) VALUES (?,?) ON CONFLICT (employee_id) DO NOTHING",
            employee.getId(), now);
        jdbc.queryForObject("SELECT id FROM document_account WHERE employee_id=? FOR UPDATE", Long.class, employee.getId());
        entities.flush();
        entities.clear();
        employee = access.requireUpload(caller, request.employeePublicId(), request.purpose());
        now = Timestamp.from(Instant.now(clock));
        jdbc.update("INSERT INTO private_document(public_id,employee_id,purpose,status,file_name,declared_media_type,"
                + "declared_size,created_at,updated_at) VALUES (?,?,?,'PENDING_UPLOAD',?,?,?,?,?)",
            documentPublicId, employee.getId(), request.purpose().name(), request.fileName(), request.mediaType(),
            request.byteSize(), now, now);
        return new Upload(documentPublicId, "PENDING_UPLOAD");
    }

    public void verifyCreate(Request request, Authentication caller) {
        validate(request);
        access.requireUpload(caller, request.employeePublicId(), request.purpose());
    }

    @Transactional(readOnly = true)
    public void requireUploadAuthority(UUID documentPublicId, Authentication caller) {
        PendingDocument document = find(documentPublicId, false);
        access.requireUpload(caller, document.employeePublicId(), document.purpose());
        if (!"LIVE".equals(document.deletionState())) {
            throw new ResourceNotFoundException();
        }
    }

    public Upload status(UUID documentPublicId, Authentication caller) {
        PendingDocument document = find(documentPublicId, false);
        access.requireUpload(caller, document.employeePublicId(), document.purpose());
        if (!"LIVE".equals(document.deletionState())) {
            throw new ResourceNotFoundException();
        }
        access.requireUpload(caller, document.employeePublicId(), document.purpose());
        return new Upload(document.publicId(), document.status());
    }

    @Transactional(readOnly = true)
    public PendingUpload currentUpload(UUID documentPublicId, Authentication caller) {
        PendingDocument document = find(documentPublicId, false);
        access.requireUpload(caller, document.employeePublicId(), document.purpose());
        if (!"PENDING_UPLOAD".equals(document.status()) || !"LIVE".equals(document.deletionState())
            || document.uploadPublicId() == null) {
            throw new CommandConflictException("Document has no pending upload");
        }
        return new PendingUpload(document.publicId(), document.employeePublicId(), document.purpose(),
            document.uploadPublicId(), document.mediaType(), document.byteSize());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public PendingUpload reserve(UUID documentPublicId, UUID uploadPublicId, Authentication caller) {
        Objects.requireNonNull(uploadPublicId, "uploadPublicId");
        PendingDocument document = lockAuthorized(documentPublicId, caller);
        if (!"PENDING_UPLOAD".equals(document.status()) || !"LIVE".equals(document.deletionState())) {
            throw new CommandConflictException("Document cannot accept an upload");
        }
        if (document.uploadPublicId() != null) {
            jdbc.update("UPDATE document_upload_attempt SET status='ABANDONED' WHERE public_id=? AND status='RESERVED'",
                document.uploadPublicId());
        }
        Long generation = jdbc.queryForObject("UPDATE private_document SET current_upload_id=NULL,"
                + "current_upload_generation=current_upload_generation+1,updated_at=? WHERE id=? "
                + "RETURNING current_upload_generation", Long.class, Timestamp.from(clock.instant()), document.id());
        Long uploadId = jdbc.queryForObject("INSERT INTO document_upload_attempt(public_id,document_id,generation,"
                + "staging_key,byte_size,status,created_at) VALUES (?,?,?,?,?,'RESERVED',?) RETURNING id", Long.class,
            uploadPublicId, document.id(), generation, "staging/" + uploadPublicId, document.byteSize(), Timestamp.from(clock.instant()));
        jdbc.update("UPDATE private_document SET current_upload_id=? WHERE id=?", uploadId, document.id());
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
        PendingDocument document = lockAuthorized(expected.documentPublicId(), caller);
        if (!"PENDING_UPLOAD".equals(document.status()) || !"LIVE".equals(document.deletionState())
            || !Objects.equals(document.uploadPublicId(), expected.uploadPublicId())
            || !Objects.equals(document.employeePublicId(), expected.employeePublicId())
            || document.purpose() != expected.purpose() || document.byteSize() != expected.byteSize()
            || !Objects.equals(document.mediaType(), expected.mediaType())) {
            throw new CommandConflictException("Document upload changed");
        }
        int changed = jdbc.update("UPDATE document_upload_attempt SET status='COMPLETED',object_etag=?,completed_at=? "
                + "WHERE public_id=? AND status='RESERVED'", etag, Timestamp.from(clock.instant()), expected.uploadPublicId());
        if (changed != 1) {
            throw new CommandConflictException("Document upload is no longer pending");
        }
        Long generation = jdbc.queryForObject("UPDATE private_document SET status='VERIFYING',"
                + "current_generation=current_generation+1,updated_at=? WHERE id=? RETURNING current_generation",
            Long.class, Timestamp.from(clock.instant()), document.id());
        jdbc.update("INSERT INTO document_verification_attempt(public_id,document_id,upload_attempt_id,generation,"
                + "candidate_key,status,created_at) SELECT ?,id,current_upload_id,?,?,'QUEUED',? FROM private_document WHERE id=?",
            attemptPublicId, generation, "candidates/" + attemptPublicId, Timestamp.from(clock.instant()), document.id());
        access.requireUpload(caller, document.employeePublicId(), document.purpose());
        return new Upload(document.publicId(), "VERIFYING");
    }

    private PendingDocument lockAuthorized(UUID publicId, Authentication caller) {
        PendingDocument before = find(publicId, false);
        access.requireUpload(caller, before.employeePublicId(), before.purpose());
        jdbc.queryForObject("SELECT id FROM document_account WHERE employee_id=? FOR UPDATE", Long.class, before.employeeId());
        PendingDocument locked = find(publicId, true);
        entities.flush();
        entities.clear();
        access.requireUpload(caller, locked.employeePublicId(), locked.purpose());
        return locked;
    }

    private PendingDocument find(UUID publicId, boolean lock) {
        Objects.requireNonNull(publicId, "publicId");
        String statement = "SELECT d.id,d.public_id,d.employee_id,e.public_id,d.purpose,d.status,d.deletion_state,"
            + "d.declared_media_type,d.declared_size,u.public_id FROM private_document d "
            + "JOIN employees e ON e.id=d.employee_id LEFT JOIN document_upload_attempt u ON u.id=d.current_upload_id "
            + "WHERE d.public_id=?" + (lock ? " FOR UPDATE OF d" : "");
        List<PendingDocument> rows = jdbc.query(statement, (row, index) -> new PendingDocument(row.getLong(1),
            row.getObject(2, UUID.class), row.getLong(3), row.getObject(4, UUID.class), Purpose.valueOf(row.getString(5)),
            row.getString(6), row.getString(7), row.getString(8), row.getLong(9), row.getObject(10, UUID.class)), publicId);
        return rows.stream().findFirst().orElseThrow(ResourceNotFoundException::new);
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

    public record Upload(UUID publicId, String status) {
    }

    public record PendingUpload(UUID documentPublicId, UUID employeePublicId, Purpose purpose, UUID uploadPublicId,
                                String mediaType, long byteSize) {
    }

    private record PendingDocument(long id, UUID publicId, long employeeId, UUID employeePublicId, Purpose purpose,
                                   String status, String deletionState, String mediaType, long byteSize, UUID uploadPublicId) {
    }
}
