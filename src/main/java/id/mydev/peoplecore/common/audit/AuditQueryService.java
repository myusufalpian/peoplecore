package id.mydev.peoplecore.common.audit;

import id.mydev.peoplecore.common.api.PageMetadata;
import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.common.security.CurrentAccessPolicy;
import id.mydev.peoplecore.common.security.CurrentCaller;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class AuditQueryService {
    private final AuditEventRepository repository;
    private final CurrentAccessPolicy accessPolicy;

    public AuditQueryService(AuditEventRepository repository, CurrentAccessPolicy accessPolicy) {
        this.repository = repository;
        this.accessPolicy = accessPolicy;
    }

    public record AuditEventSummary(UUID eventId, String actorId, String action, String aggregateType,
                                    UUID aggregatePublicId, String correlationId, Instant createdAt) { }

    @Transactional(readOnly = true)
    public Page<AuditEventSummary> findByAggregate(String aggregateType, UUID aggregatePublicId, int page, int size) {
        Objects.requireNonNull(aggregateType, "aggregateType must not be null");
        Objects.requireNonNull(aggregatePublicId, "aggregatePublicId must not be null");
        Set<String> resources = allowedResources();
        if (resources.isEmpty()) {
            throw new AccessDeniedException("No audit operation permission");
        }
        if (!resources.contains(aggregateType + ":" + aggregatePublicId)) {
            throw new ResourceNotFoundException();
        }
        return repository.findByAggregateTypeAndAggregatePublicId(aggregateType, aggregatePublicId, pageable(page, size))
            .map(AuditEventMapper::summary);
    }

    @Transactional(readOnly = true)
    public Page<AuditEventSummary> findByActor(String targetActorId, int page, int size) {
        Objects.requireNonNull(targetActorId, "targetActorId must not be null");
        Set<String> resources = allowedResources();
        if (resources.isEmpty()) {
            throw new AccessDeniedException("No audit resources in current access scope");
        }
        return repository.findScopedByActor(targetActorId, resources, pageable(page, size)).map(AuditEventMapper::summary);
    }

    private Set<String> allowedResources() {
        return Set.copyOf(accessPolicy.auditResources(CurrentCaller.require()));
    }

    private static PageRequest pageable(int page, int size) {
        PageMetadata.validate(page, size, 0);
        return PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
    }
}
