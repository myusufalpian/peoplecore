package id.mydev.peoplecore.document.infrastructure.persistence;

import id.mydev.peoplecore.document.domain.model.Purpose;
import id.mydev.peoplecore.document.domain.repository.DocumentUploadRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDocumentUploadRepository implements DocumentUploadRepository {
    private final JdbcTemplate jdbc;

    public JdbcDocumentUploadRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void ensureAccount(long employeeId, Instant instant) {
        Timestamp now = Timestamp.from(instant);
        jdbc.update("INSERT INTO document_account(employee_id,created_at) VALUES (?,?) "
            + "ON CONFLICT (employee_id) DO NOTHING", employeeId, now);
    }

    @Override
    public void lockAccount(long employeeId) {
        jdbc.queryForObject("SELECT id FROM document_account WHERE employee_id=? FOR UPDATE", Long.class, employeeId);
    }

    @Override
    public void insertDocument(UUID documentPublicId, long employeeId, Purpose purpose, String fileName,
                               String mediaType, long byteSize, Instant instant) {
        Timestamp now = Timestamp.from(instant);
        jdbc.update("INSERT INTO private_document(public_id,employee_id,purpose,status,file_name,declared_media_type,"
                + "declared_size,created_at,updated_at) VALUES (?,?,?,'PENDING_UPLOAD',?,?,?,?,?)",
            documentPublicId, employeeId, purpose.name(), fileName, mediaType, byteSize, now, now);
    }

    @Override
    public Optional<PendingDocumentRecord> findDocument(UUID documentPublicId, boolean lock) {
        String statement = "SELECT d.id,d.public_id,d.employee_id,e.public_id,d.purpose,d.status,d.deletion_state,"
            + "d.declared_media_type,d.declared_size,u.public_id FROM private_document d "
            + "JOIN employees e ON e.id=d.employee_id LEFT JOIN document_upload_attempt u ON u.id=d.current_upload_id "
            + "WHERE d.public_id=?" + (lock ? " FOR UPDATE OF d" : "");
        List<PendingDocumentRecord> rows = jdbc.query(statement, (row, index) -> new PendingDocumentRecord(
            row.getLong(1), row.getObject(2, UUID.class), row.getLong(3), row.getObject(4, UUID.class),
            Purpose.valueOf(row.getString(5)), row.getString(6), row.getString(7), row.getString(8),
            row.getLong(9), row.getObject(10, UUID.class)), documentPublicId);
        return rows.stream().findFirst();
    }

    @Override
    public boolean abandonReservedUpload(UUID uploadPublicId) {
        return jdbc.update("UPDATE document_upload_attempt SET status='ABANDONED' "
            + "WHERE public_id=? AND status='RESERVED'", uploadPublicId) == 1;
    }

    @Override
    public long advanceUploadGeneration(long documentId, Instant instant) {
        Timestamp now = Timestamp.from(instant);
        Long generation = jdbc.queryForObject("UPDATE private_document SET current_upload_id=NULL,"
            + "current_upload_generation=current_upload_generation+1,updated_at=? WHERE id=? "
            + "RETURNING current_upload_generation", Long.class, now, documentId);
        return generation == null ? 0 : generation;
    }

    @Override
    public long insertUploadAttempt(UUID uploadPublicId, long documentId, long generation, String stagingKey,
                                    long byteSize, Instant instant) {
        Timestamp now = Timestamp.from(instant);
        Long id = jdbc.queryForObject("INSERT INTO document_upload_attempt(public_id,document_id,generation,"
                + "staging_key,byte_size,status,created_at) VALUES (?,?,?,?,?,'RESERVED',?) RETURNING id",
            Long.class, uploadPublicId, documentId, generation, stagingKey, byteSize, now);
        return id == null ? 0 : id;
    }

    @Override
    public void attachUpload(long documentId, long uploadId) {
        jdbc.update("UPDATE private_document SET current_upload_id=? WHERE id=?", uploadId, documentId);
    }

    @Override
    public boolean completeUpload(UUID uploadPublicId, String etag, Instant instant) {
        Timestamp now = Timestamp.from(instant);
        return jdbc.update("UPDATE document_upload_attempt SET status='COMPLETED',object_etag=?,completed_at=? "
            + "WHERE public_id=? AND status='RESERVED'", etag, now, uploadPublicId) == 1;
    }

    @Override
    public long advanceVerificationGeneration(long documentId, Instant instant) {
        Timestamp now = Timestamp.from(instant);
        Long generation = jdbc.queryForObject("UPDATE private_document SET status='VERIFYING',"
            + "current_generation=current_generation+1,updated_at=? WHERE id=? RETURNING current_generation",
            Long.class, now, documentId);
        return generation == null ? 0 : generation;
    }

    @Override
    public void queueVerificationAttempt(UUID attemptPublicId, long documentId, long generation,
                                         String candidateKey, Instant instant) {
        Timestamp now = Timestamp.from(instant);
        jdbc.update("INSERT INTO document_verification_attempt(public_id,document_id,upload_attempt_id,generation,"
                + "candidate_key,status,created_at) SELECT ?,id,current_upload_id,?,?,'QUEUED',? "
                + "FROM private_document WHERE id=?",
            attemptPublicId, generation, candidateKey, now, documentId);
    }

    @Override
    public Optional<MetadataRecord> findMetadata(UUID documentPublicId) {
        return jdbc.query("SELECT d.public_id,d.purpose,d.status,v.media_type,v.byte_size,d.created_at,"
                + "v.verified_at FROM private_document d LEFT JOIN document_version v ON v.id=d.current_version_id "
                + "AND v.document_id=d.id WHERE d.public_id=?",
            (row, index) -> new MetadataRecord(row.getObject(1, UUID.class), Purpose.valueOf(row.getString(2)),
                row.getString(3), row.getString(4), row.getObject(5, Long.class),
                row.getTimestamp(6).toInstant(),
                row.getTimestamp(7) == null ? null : row.getTimestamp(7).toInstant()),
            documentPublicId).stream().findFirst();
    }
}
