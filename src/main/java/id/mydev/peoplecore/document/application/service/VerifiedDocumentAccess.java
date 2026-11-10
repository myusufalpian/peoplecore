package id.mydev.peoplecore.document.application.service;

import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VerifiedDocumentAccess {
    private final JdbcTemplate jdbc;

    public VerifiedDocumentAccess(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public VerifiedVersion requireForUpdate(UUID documentPublicId, UUID employeePublicId, Purpose purpose) {
        Objects.requireNonNull(documentPublicId, "documentPublicId");
        Objects.requireNonNull(employeePublicId, "employeePublicId");
        Objects.requireNonNull(purpose, "purpose");
        List<VerifiedVersion> versions = jdbc.query("SELECT d.id, v.id, d.public_id, v.public_id "
                + "FROM private_document d JOIN employees e ON e.id=d.employee_id "
                + "JOIN document_version v ON v.id=d.current_version_id AND v.document_id=d.id "
                + "WHERE d.public_id=? AND e.public_id=? AND d.purpose=? "
                + "AND d.status='VERIFIED' AND d.deletion_state='LIVE' FOR UPDATE OF d",
            (row, index) -> new VerifiedVersion(row.getLong(1), row.getLong(2),
                row.getObject(3, UUID.class), row.getObject(4, UUID.class)),
            documentPublicId, employeePublicId, purpose.name());
        return versions.stream().findFirst().orElseThrow(ResourceNotFoundException::new);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<VerifiedVersion> requireAllForUpdate(List<Request> requests) {
        Objects.requireNonNull(requests, "requests");
        List<OrderedRequest> ordered = new ArrayList<>(requests.size());
        for (Request request : requests) {
            Objects.requireNonNull(request, "request");
            List<Long> ids = jdbc.query("SELECT id FROM private_document WHERE public_id=?",
                (row, index) -> row.getLong(1), request.documentPublicId());
            long id = ids.stream().findFirst().orElseThrow(ResourceNotFoundException::new);
            ordered.add(new OrderedRequest(id, request));
        }
        ordered.sort(Comparator.comparingLong(OrderedRequest::id));
        List<VerifiedVersion> versions = new ArrayList<>(ordered.size());
        for (OrderedRequest orderedRequest : ordered) {
            Request request = orderedRequest.request();
            versions.add(requireForUpdate(request.documentPublicId(), request.employeePublicId(), request.purpose()));
        }
        return List.copyOf(versions);
    }

    public enum Purpose {
        CLAIM_EVIDENCE, PAYSLIP
    }

    public record VerifiedVersion(long documentId, long versionId, UUID documentPublicId, UUID versionPublicId) {
    }

    public record Request(UUID documentPublicId, UUID employeePublicId, Purpose purpose) {
    }

    private record OrderedRequest(long id, Request request) {
    }
}
