package id.mydev.peoplecore.common.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Set;

import java.util.Optional;
import java.util.UUID;

public interface AuditEventRepository extends Repository<AuditEvent, Long> {

    Optional<AuditEvent> findById(Long id);

    Page<AuditEvent> findByAggregateTypeAndAggregatePublicId(
        String aggregateType,
        UUID aggregatePublicId,
        Pageable pageable
    );

    @Query("select a from AuditEvent a where a.actorId = :actorId and concat(a.aggregateType, ':', cast(a.aggregatePublicId as string)) in :resources")
    Page<AuditEvent> findScopedByActor(@Param("actorId") String actorId, @Param("resources") Set<String> resources,
                                     Pageable pageable);
}
