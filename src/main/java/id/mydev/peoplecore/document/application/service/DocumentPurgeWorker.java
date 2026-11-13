package id.mydev.peoplecore.document.application.service;

import id.mydev.peoplecore.document.domain.repository.DocumentDeletionRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentPurgeWorker {
    private final DocumentDeletionRepository documents;

    public DocumentPurgeWorker(DocumentDeletionRepository documents) {
        this.documents = documents;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean purge(long documentId, Instant now) {
        if (documents.markPurged(documentId, now)) {
            documents.recordPurgeAudit(documentId, now);
        }
        return documents.readyForObjectPurge(documentId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean completeObjectPurge(long documentId, Instant completedAt) {
        return documents.completeObjectPurge(documentId, completedAt);
    }
}
