package id.mydev.peoplecore.common.audit;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

@Component
public class AuditEventWriter {
    private final EntityManager entityManager;

    public AuditEventWriter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(AuditEvent event) {
        Assert.isTrue(event.getId() == null, "Audit writer only accepts new events");
        entityManager.persist(event);
    }
}
