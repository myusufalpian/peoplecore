package id.mydev.peoplecore.common.audit;

public final class AuditEventMapper {
    private AuditEventMapper() { }

    public static AuditQueryService.AuditEventSummary summary(AuditEvent event) {
        return new AuditQueryService.AuditEventSummary(event.getEventId(), event.getActorId(), event.getAction(),
            event.getAggregateType(), event.getAggregatePublicId(), event.getCorrelationId(), event.getCreatedAt());
    }
}
