package id.mydev.peoplecore.document.application.service;

import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.common.command.CommandConflictException;
import id.mydev.peoplecore.document.domain.repository.DocumentDeletionRepository;
import id.mydev.peoplecore.document.domain.repository.DocumentDeletionRepository.TargetRecord;
import id.mydev.peoplecore.document.infrastructure.storage.PrivateDocumentStorage;
import id.mydev.peoplecore.identity.application.service.AccountAccessService;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.core.exception.SdkException;

@Service
public class DocumentDeletionService {
    private static final Logger LOG = LoggerFactory.getLogger(DocumentDeletionService.class);
    private static final String LIVE = "LIVE";
    private static final String CLAIMED = "CLAIMED";
    private static final String DELETED = "DELETED";

    private final DocumentDeletionRepository documents;
    private final DocumentPurgeWorker purger;
    private final ObjectProvider<PrivateDocumentStorage> storage;
    private final AccountAccessService access;
    private final Clock clock;
    private final Duration restoreWindow;

    public DocumentDeletionService(DocumentDeletionRepository documents, DocumentPurgeWorker purger,
                                   ObjectProvider<PrivateDocumentStorage> storage,
                                   AccountAccessService access, Clock clock,
                                   @Value("${peoplecore.documents.deletion.restore-window:PT720H}")
                                   Duration restoreWindow) {
        this.documents = documents;
        this.purger = purger;
        this.storage = storage;
        this.access = access;
        this.clock = clock;
        if (restoreWindow == null || restoreWindow.isNegative() || restoreWindow.isZero()) {
            throw new IllegalArgumentException("Deletion restore window must be positive");
        }
        this.restoreWindow = restoreWindow;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Deletion claim(UUID documentPublicId, Authentication caller) {
        Instant now = clock.instant();
        TargetRecord target = requireOwner(documentPublicId, caller);
        if (DELETED.equals(target.deletionState())) {
            throw new CommandConflictException("Document is already deleted");
        }
        if (CLAIMED.equals(target.deletionState())) {
            throw new CommandConflictException("Document deletion is already claimed");
        }
        if (!LIVE.equals(target.deletionState())) {
            throw new CommandConflictException("Document state does not allow deletion");
        }
        if (documents.hasActiveHold(target.id())) {
            throw new CommandConflictException("Document has an active legal hold");
        }
        if (documents.underRetention(target.id())) {
            throw new CommandConflictException("Document is under retention");
        }
        Instant deadline = now.plus(restoreWindow);
        boolean claimed;
        try {
            claimed = documents.claim(target.id(), now, deadline);
        } catch (DataIntegrityViolationException exception) {
            throw new CommandConflictException(conflict(exception));
        }
        if (!claimed) {
            throw new CommandConflictException("Document state changed");
        }
        return new Deletion(target.publicId(), CLAIMED, now, deadline);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Deletion restore(UUID documentPublicId, Authentication caller) {
        Instant now = clock.instant();
        TargetRecord target = requireOwner(documentPublicId, caller);
        if (DELETED.equals(target.deletionState())) {
            throw new CommandConflictException("Document is already deleted");
        }
        if (!CLAIMED.equals(target.deletionState())) {
            throw new CommandConflictException("Document deletion is not claimed");
        }
        if (target.restoreDeadline() == null || !target.restoreDeadline().isAfter(now)) {
            throw new CommandConflictException("Restore window has expired");
        }
        if (!documents.restore(target.id(), now)) {
            throw new CommandConflictException("Restore window has expired");
        }
        return new Deletion(target.publicId(), LIVE, null, null);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int purgeExpired(int limit) {
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("Limit must be between 1 and 500");
        }
        PrivateDocumentStorage objects = storage.getIfAvailable();
        if (objects == null) {
            LOG.warn("Document purge is paused because storage is not configured");
            return 0;
        }
        Instant now = clock.instant();
        int purged = 0;
        for (Long documentId : documents.findExpired(now, limit)) {
            try {
                if (!purger.purge(documentId, now)) {
                    continue;
                }
                deleteObjects(documentId, objects);
                if (purger.completeObjectPurge(documentId, clock.instant())) {
                    purged++;
                }
            } catch (DataAccessException exception) {
                LOG.warn("Document purge skipped because the claim changed");
            } catch (SdkException exception) {
                LOG.warn("Document object purge will be retried");
            }
        }
        return purged;
    }

    private void deleteObjects(long documentId, PrivateDocumentStorage objects) {
        for (String key : documents.candidateKeys(documentId)) {
            objects.deleteObject(key);
        }
    }

    @Transactional(readOnly = true)
    public Deletion status(UUID documentPublicId, Authentication caller) {
        TargetRecord target = requireOwner(documentPublicId, caller);
        return new Deletion(target.publicId(), target.deletionState(), target.requestedAt(),
            target.restoreDeadline());
    }

    private static String conflict(DataIntegrityViolationException exception) {
        String cause = exception.getMostSpecificCause().getMessage();
        return cause != null && cause.contains("legal hold")
            ? "Document has an active legal hold" : "Document is under retention";
    }

    private TargetRecord requireOwner(UUID documentPublicId, Authentication caller) {
        Objects.requireNonNull(documentPublicId, "documentPublicId must not be null");
        Instant now = clock.instant();
        UserAccount account = access.requireActive(caller, now);
        UUID ownEmployee = access.ownEmployeePublicId(account).orElseThrow(ResourceNotFoundException::new);
        TargetRecord target = documents.findDocument(documentPublicId).orElseThrow(ResourceNotFoundException::new);
        if (!ownEmployee.equals(target.employeePublicId()) || access.activeRoles(account.getId(), now).isEmpty()) {
            throw new ResourceNotFoundException();
        }
        return target;
    }

    public record Deletion(UUID documentPublicId, String state, Instant requestedAt, Instant restoreDeadline) { }
}
