package id.mydev.peoplecore.organization.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "profile_proposal")
public class ProfileProposal {

    public static final String STATE_DRAFT = "DRAFT";
    public static final String STATE_PENDING = "PENDING";
    public static final String STATE_RETURNED = "RETURNED";
    public static final String STATE_APPROVED = "APPROVED";
    public static final String STATE_REJECTED = "REJECTED";
    public static final String STATE_CANCELLED = "CANCELLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true)
    private UUID publicId;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "state", nullable = false, length = 32)
    private String state;

    @Column(name = "current_revision_id")
    private Long currentRevisionId;

    @Column(name = "current_assignment_id")
    private Long currentAssignmentId;

    @Column(name = "blocked_reason", length = 64)
    private String blockedReason;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ProfileProposal() {
    }

    public ProfileProposal(UUID publicId, Long employeeId, Instant now) {
        this.publicId = Objects.requireNonNull(publicId, "publicId must not be null");
        this.employeeId = Objects.requireNonNull(employeeId, "employeeId must not be null");
        this.state = STATE_DRAFT;
        this.createdAt = Objects.requireNonNull(now, "now must not be null");
        this.updatedAt = now;
    }

    public void transitionTo(String state, Instant now) {
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.updatedAt = Objects.requireNonNull(now, "now must not be null");
    }

    public void pointTo(Long revisionId, Long assignmentId, Instant now) {
        this.currentRevisionId = revisionId;
        this.currentAssignmentId = assignmentId;
        this.updatedAt = Objects.requireNonNull(now, "now must not be null");
    }

    public void markBlocked(String reason, Instant now) {
        this.blockedReason = Objects.requireNonNull(reason, "reason must not be null");
        this.updatedAt = Objects.requireNonNull(now, "now must not be null");
    }

    public void clearBlocked(Instant now) {
        this.blockedReason = null;
        this.updatedAt = Objects.requireNonNull(now, "now must not be null");
    }

    public boolean isOpen() {
        return STATE_DRAFT.equals(state) || STATE_PENDING.equals(state) || STATE_RETURNED.equals(state);
    }

    public Long getId() {
        return id;
    }

    public UUID getPublicId() {
        return publicId;
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public String getState() {
        return state;
    }

    public Long getCurrentRevisionId() {
        return currentRevisionId;
    }

    public Long getCurrentAssignmentId() {
        return currentAssignmentId;
    }

    public String getBlockedReason() {
        return blockedReason;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
