package id.mydev.peoplecore.document.infrastructure.persistence;

import id.mydev.peoplecore.document.domain.repository.DocumentHoldRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDocumentHoldRepository implements DocumentHoldRepository {
    private final JdbcTemplate jdbc;

    public JdbcDocumentHoldRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<DocumentRecord> findDocument(UUID documentPublicId) {
        return jdbc.query("SELECT id,employee_id,deletion_state FROM private_document WHERE public_id=?",
            (row, index) -> new DocumentRecord(row.getLong(1), row.getLong(2), row.getString(3)),
            documentPublicId).stream().findFirst();
    }

    @Override
    public Optional<DocumentRecord> lockDocument(UUID documentPublicId) {
        return jdbc.query("SELECT id,employee_id,deletion_state FROM private_document WHERE public_id=? FOR UPDATE",
            (row, index) -> new DocumentRecord(row.getLong(1), row.getLong(2), row.getString(3)),
            documentPublicId).stream().findFirst();
    }

    @Override
    public void insert(UUID holdPublicId, long documentId, String reason, long createdBy) {
        jdbc.update("INSERT INTO document_hold(public_id,document_id,reason,created_by,created_at) "
                + "VALUES (?,?,?,?,clock_timestamp())",
            holdPublicId, documentId, reason, createdBy);
    }

    @Override
    public Optional<HoldRecord> findHold(UUID holdPublicId) {
        return jdbc.query("SELECT h.public_id,d.public_id,h.reason,h.created_at,h.released_at "
                + "FROM document_hold h JOIN private_document d ON d.id=h.document_id WHERE h.public_id=?",
            JdbcDocumentHoldRepository::mapHold, holdPublicId).stream().findFirst();
    }

    @Override
    public List<HoldRecord> findActive(UUID documentPublicId) {
        return jdbc.query("SELECT h.public_id,d.public_id,h.reason,h.created_at,h.released_at "
                + "FROM document_hold h JOIN private_document d ON d.id=h.document_id "
                + "WHERE d.public_id=? AND h.released_at IS NULL ORDER BY h.created_at DESC",
            JdbcDocumentHoldRepository::mapHold, documentPublicId);
    }

    @Override
    public boolean release(UUID holdPublicId, String releaseReason) {
        return jdbc.update("UPDATE document_hold SET released_at=clock_timestamp(),release_reason=? "
            + "WHERE public_id=? AND released_at IS NULL", releaseReason, holdPublicId) == 1;
    }

    private static HoldRecord mapHold(ResultSet row, int index) throws SQLException {
        return new HoldRecord(row.getObject(1, UUID.class), row.getObject(2, UUID.class), row.getString(3),
            row.getTimestamp(4).toInstant(),
            row.getTimestamp(5) == null ? null : row.getTimestamp(5).toInstant());
    }
}
