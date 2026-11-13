package id.mydev.peoplecore.document.application.service;

import id.mydev.peoplecore.common.command.AdmissionGuard;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentStatusQuery {
    private final AdmissionGuard admission;
    private final EntityManager entities;
    private final DocumentUploadService uploads;

    public DocumentStatusQuery(AdmissionGuard admission, EntityManager entities, DocumentUploadService uploads) {
        this.admission = admission;
        this.entities = entities;
        this.uploads = uploads;
    }

    @Transactional
    public DocumentUploadService.Metadata status(UUID documentPublicId, Authentication caller) {
        admission.acquire(AdmissionGuard.Mode.SHARED);
        entities.flush();
        entities.clear();
        return uploads.status(documentPublicId, caller);
    }
}
