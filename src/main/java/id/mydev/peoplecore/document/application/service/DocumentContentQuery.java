package id.mydev.peoplecore.document.application.service;

import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.document.domain.repository.DocumentContentRepository;
import id.mydev.peoplecore.document.domain.repository.DocumentContentRepository.TargetRecord;
import id.mydev.peoplecore.identity.application.service.AccountAccessService;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentContentQuery {
    private final DocumentContentRepository documents;
    private final AccountAccessService access;
    private final Clock clock;

    public DocumentContentQuery(DocumentContentRepository documents, AccountAccessService access, Clock clock) {
        this.documents = documents;
        this.access = access;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public DocumentTarget requireOwnedClaimEvidence(UUID documentPublicId, Authentication caller) {
        Objects.requireNonNull(documentPublicId, "documentPublicId must not be null");
        Instant now = clock.instant();
        UserAccount account = access.requireActive(caller, now);
        UUID ownEmployee = access.ownEmployeePublicId(account).orElseThrow(ResourceNotFoundException::new);
        if (access.activeRoles(account.getId(), now).isEmpty()) {
            throw new ResourceNotFoundException();
        }
        TargetRecord target = documents.findOwnedVerifiedClaimEvidence(documentPublicId, ownEmployee)
            .orElseThrow(ResourceNotFoundException::new);
        return new DocumentTarget(target.documentPublicId(), target.versionPublicId(),
            target.employeePublicId(), target.mediaType());
    }
}
