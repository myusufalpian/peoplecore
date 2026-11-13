package id.mydev.peoplecore.document.infrastructure.persistence;

import id.mydev.peoplecore.document.domain.repository.DocumentContentRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDocumentContentRepository implements DocumentContentRepository {
    private static final String OWNED_CLAIM_EVIDENCE = """
        SELECT d.public_id, v.public_id, e.public_id, v.media_type
        FROM private_document d
        JOIN employees e ON e.id = d.employee_id
        JOIN document_version v ON v.id = d.current_version_id AND v.document_id = d.id
        WHERE d.public_id = ? AND e.public_id = ? AND d.purpose = 'CLAIM_EVIDENCE'
          AND d.status = 'VERIFIED' AND d.deletion_state = 'LIVE'
        """;

    private final JdbcTemplate jdbc;

    public JdbcDocumentContentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<TargetRecord> findOwnedVerifiedClaimEvidence(UUID documentPublicId, UUID employeePublicId) {
        return jdbc.query(OWNED_CLAIM_EVIDENCE,
            (row, index) -> new TargetRecord(row.getObject(1, UUID.class), row.getObject(2, UUID.class),
                row.getObject(3, UUID.class), row.getString(4)),
            documentPublicId, employeePublicId).stream().findFirst();
    }
}
