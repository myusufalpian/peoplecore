package id.mydev.peoplecore.approval.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "profile_rule_sets")
public class ProfileRuleSet {

    public static final String REQUEST_PROFILE_CHANGE = "PROFILE_CHANGE";

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PUBLISHED = "PUBLISHED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true)
    private UUID publicId;

    @Column(name = "request_type", nullable = false, length = 32)
    private String requestType;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected ProfileRuleSet() {
    }

    public ProfileRuleSet(UUID publicId, Instant createdAt) {
        this.publicId = Objects.requireNonNull(publicId, "publicId must not be null");
        this.requestType = REQUEST_PROFILE_CHANGE;
        this.status = STATUS_DRAFT;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public void publish(Instant publishedAt) {
        if (!STATUS_DRAFT.equals(status)) {
            throw new IllegalStateException("Only draft rule sets can be published");
        }
        this.publishedAt = Objects.requireNonNull(publishedAt, "publishedAt must not be null");
        this.status = STATUS_PUBLISHED;
    }

    public boolean isPublished() {
        return STATUS_PUBLISHED.equals(status);
    }

    public Long getId() {
        return id;
    }

    public UUID getPublicId() {
        return publicId;
    }

    public String getRequestType() {
        return requestType;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }
}
