package id.mydev.peoplecore.document.infrastructure.persistence;

import id.mydev.peoplecore.document.domain.repository.VerifiedDocumentRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcVerifiedDocumentRepository implements VerifiedDocumentRepository {
    private final JdbcTemplate jdbc;
    public JdbcVerifiedDocumentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<VerifiedVersion> findForUpdate(UUID documentPublicId, UUID employeePublicId, String purpose) {
        List<VerifiedVersion> versions = jdbc.query("SELECT d.id, v.id, d.public_id, v.public_id "
                + "FROM private_document d JOIN employees e ON e.id=d.employee_id "
                + "JOIN document_version v ON v.id=d.current_version_id AND v.document_id=d.id "
                + "WHERE d.public_id=? AND e.public_id=? AND d.purpose=? "
                + "AND d.status='VERIFIED' AND d.deletion_state='LIVE' FOR UPDATE OF d",
            (row, index) -> new VerifiedVersion(row.getLong(1), row.getLong(2),
                row.getObject(3, UUID.class), row.getObject(4, UUID.class)),
            documentPublicId, employeePublicId, purpose);
        return versions.stream().findFirst();
    }

    @Override
    public Optional<Long> findId(UUID documentPublicId) {
        return jdbc.query("SELECT id FROM private_document WHERE public_id=?",
            (row, index) -> row.getLong(1), documentPublicId).stream().findFirst();
    }
}
