package id.mydev.peoplecore.document.application.service;

import id.mydev.peoplecore.document.domain.model.Purpose;

import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;
import id.mydev.peoplecore.document.domain.repository.VerifiedDocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VerifiedDocumentAccess {
    private final VerifiedDocumentRepository repository;

    public VerifiedDocumentAccess(VerifiedDocumentRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public VerifiedVersion requireForUpdate(UUID documentPublicId, UUID employeePublicId, Purpose purpose) {
        Objects.requireNonNull(documentPublicId, "documentPublicId");
        Objects.requireNonNull(employeePublicId, "employeePublicId");
        Objects.requireNonNull(purpose, "purpose");
        var version = repository.findForUpdate(documentPublicId, employeePublicId, purpose.name())
            .orElseThrow(ResourceNotFoundException::new);
        return new VerifiedVersion(version.documentId(), version.versionId(), version.documentPublicId(), version.versionPublicId());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<VerifiedVersion> requireAllForUpdate(List<Request> requests) {
        Objects.requireNonNull(requests, "requests");
        List<OrderedRequest> ordered = new ArrayList<>(requests.size());
        for (Request request : requests) {
            Objects.requireNonNull(request, "request");
            long id = repository.findId(request.documentPublicId()).orElseThrow(ResourceNotFoundException::new);
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

    public record VerifiedVersion(long documentId, long versionId, UUID documentPublicId, UUID versionPublicId) {
    }

    public record Request(UUID documentPublicId, UUID employeePublicId, Purpose purpose) {
    }

    private record OrderedRequest(long id, Request request) {
    }
}
