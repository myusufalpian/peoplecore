package id.mydev.peoplecore.document.application.service;

import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.common.command.CommandConflictException;
import id.mydev.peoplecore.document.domain.repository.DocumentHoldRepository;
import id.mydev.peoplecore.document.domain.repository.DocumentHoldRepository.DocumentRecord;
import id.mydev.peoplecore.document.domain.repository.DocumentHoldRepository.HoldRecord;
import id.mydev.peoplecore.identity.application.service.AccountAccessService;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.organization.application.service.EmployeeService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class DocumentHoldService {
    public static final int MAX_REASON_LENGTH = 1000;
    private static final String DELETED = "DELETED";

    private final DocumentHoldRepository holds;
    private final AccountAccessService access;
    private final EmployeeService employees;
    private final Clock clock;

    public DocumentHoldService(DocumentHoldRepository holds, AccountAccessService access,
                               EmployeeService employees, Clock clock) {
        this.holds = holds;
        this.access = access;
        this.employees = employees;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public void verifyAuthority(UUID documentPublicId, Authentication caller) {
        requireAuthority(document(documentPublicId), caller);
    }

    @Transactional(readOnly = true)
    public List<Hold> active(UUID documentPublicId, Authentication caller) {
        requireAuthority(document(documentPublicId), caller);
        return holds.findActive(documentPublicId).stream().map(DocumentHoldService::toHold).toList();
    }

    @Transactional(readOnly = true)
    public Hold hold(UUID documentPublicId, UUID holdPublicId, Authentication caller) {
        requireAuthority(document(documentPublicId), caller);
        Hold hold = hold(holdPublicId);
        if (!Objects.equals(hold.documentPublicId(), documentPublicId)) {
            throw new ResourceNotFoundException();
        }
        return hold;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Hold place(UUID holdPublicId, UUID documentPublicId, String reason, Authentication caller) {
        Objects.requireNonNull(holdPublicId, "holdPublicId must not be null");
        String heldReason = requireReason(reason);
        DocumentRecord document = lockedDocument(documentPublicId);
        UserAccount account = requireAuthority(document, caller);
        try {
            holds.insert(holdPublicId, document.id(), heldReason, account.getId());
        } catch (DuplicateKeyException exception) {
            throw new CommandConflictException("Document already has an active hold");
        }
        return hold(holdPublicId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Hold release(UUID holdPublicId, UUID documentPublicId, String releaseReason, Authentication caller) {
        Objects.requireNonNull(holdPublicId, "holdPublicId must not be null");
        String reason = requireReason(releaseReason);
        DocumentRecord document = document(documentPublicId);
        requireAuthority(document, caller);
        Hold current = hold(holdPublicId);
        if (!Objects.equals(current.documentPublicId(), documentPublicId)) {
            throw new ResourceNotFoundException();
        }
        if (current.releasedAt() != null) {
            throw new CommandConflictException("Document hold is already released");
        }
        if (!holds.release(holdPublicId, reason)) {
            throw new CommandConflictException("Document hold is already released");
        }
        return hold(holdPublicId);
    }

    private UserAccount requireAuthority(DocumentRecord document, Authentication caller) {
        Instant now = clock.instant();
        UserAccount account = access.requireActive(caller, now);
        String unit = employees.currentOrgUnit(document.employeeId(), now).orElse(null);
        try {
            access.requireHrScope(caller, unit, now);
        } catch (AccessDeniedException exception) {
            throw new ResourceNotFoundException();
        }
        return account;
    }

    private String requireReason(String reason) {
        if (!StringUtils.hasText(reason) || reason.length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("A hold reason of at most " + MAX_REASON_LENGTH + " characters is required");
        }
        return reason.trim();
    }

    private DocumentRecord lockedDocument(UUID documentPublicId) {
        Objects.requireNonNull(documentPublicId, "documentPublicId must not be null");
        DocumentRecord document = holds.lockDocument(documentPublicId).orElseThrow(ResourceNotFoundException::new);
        if (DELETED.equals(document.deletionState())) {
            throw new ResourceNotFoundException();
        }
        return document;
    }

    private DocumentRecord document(UUID documentPublicId) {
        Objects.requireNonNull(documentPublicId, "documentPublicId must not be null");
        DocumentRecord document = holds.findDocument(documentPublicId).orElseThrow(ResourceNotFoundException::new);
        if (DELETED.equals(document.deletionState())) {
            throw new ResourceNotFoundException();
        }
        return document;
    }

    private Hold hold(UUID holdPublicId) {
        return holds.findHold(holdPublicId).map(DocumentHoldService::toHold).orElseThrow(ResourceNotFoundException::new);
    }

    private static Hold toHold(HoldRecord record) {
        return new Hold(record.publicId(), record.documentPublicId(), record.reason(),
            record.createdAt(), record.releasedAt());
    }

    public record Hold(UUID publicId, UUID documentPublicId, String reason, Instant createdAt, Instant releasedAt) { }
}
