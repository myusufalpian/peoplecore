package id.mydev.peoplecore.document.infrastructure.persistence;

import id.mydev.peoplecore.document.domain.repository.DocumentVersionContentRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDocumentVersionContentRepository implements DocumentVersionContentRepository {
    private final JdbcTemplate jdbc;

    public JdbcDocumentVersionContentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<MetadataRecord> findMetadata(UUID documentPublicId, UUID versionPublicId, UUID employeePublicId,
                                                 String purpose) {
        return jdbc.query("SELECT a.public_id,v.media_type,v.byte_size FROM document_version v "
                + "JOIN private_document d ON d.id=v.document_id "
                + "JOIN employees e ON e.id=d.employee_id "
                + "JOIN document_verification_attempt a ON a.id=v.attempt_id AND a.document_id=d.id "
                + "WHERE d.public_id=? AND v.public_id=? AND e.public_id=? AND d.purpose=? AND d.status='VERIFIED' "
                + "AND d.deletion_state='LIVE'",
            (row, index) -> new MetadataRecord(row.getObject(1, UUID.class), row.getString(2), row.getLong(3)),
            documentPublicId, versionPublicId, employeePublicId, purpose).stream().findFirst();
    }
}
