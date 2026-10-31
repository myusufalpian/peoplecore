package id.mydev.peoplecore.attendance.domain.model;

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
@Table(name = "work_schedule_revision")
public class WorkScheduleRevision {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String PILOT_TIMEZONE = "Asia/Jakarta";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true)
    private UUID publicId;

    @Column(name = "timezone", nullable = false, length = 64)
    private String timezone;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected WorkScheduleRevision() {
    }

    public WorkScheduleRevision(UUID publicId, String timezone, Instant createdAt) {
        this.publicId = Objects.requireNonNull(publicId, "publicId must not be null");
        if (timezone == null || timezone.isBlank()) {
            throw new IllegalArgumentException("timezone must not be blank");
        }
        this.timezone = timezone;
        this.status = STATUS_DRAFT;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public void publish(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (!STATUS_DRAFT.equals(status)) {
            throw new IllegalStateException("Only a draft schedule revision can be published");
        }
        this.status = STATUS_PUBLISHED;
        this.publishedAt = now;
    }

    public boolean isPublished() {
        return STATUS_PUBLISHED.equals(status);
    }

    public Long getId() { return id; }

    public UUID getPublicId() { return publicId; }

    public String getTimezone() { return timezone; }

    public String getStatus() { return status; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getPublishedAt() { return publishedAt; }
}
