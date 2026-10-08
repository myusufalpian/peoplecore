package id.mydev.peoplecore.common.command;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(
    name = "command_receipts",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_command_receipts_actor_type_key",
            columnNames = {"actor_id", "command_type", "idempotency_key"}
        )
    }
)
public class CommandReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "actor_id", nullable = false, length = 128)
    private String actorId;

    @Column(name = "command_type", nullable = false, length = 128)
    private String commandType;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "result_status", nullable = false, length = 64)
    private String resultStatus;

    @Column(name = "result_payload", columnDefinition = "TEXT")
    private String resultPayload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected CommandReceipt() {
    }

    public CommandReceipt(
        String actorId,
        String commandType,
        String idempotencyKey,
        String requestHash,
        String resultStatus,
        String resultPayload,
        Instant createdAt,
        Instant expiresAt
    ) {
        this.actorId = Objects.requireNonNull(actorId, "actorId must not be null");
        this.commandType = Objects.requireNonNull(commandType, "commandType must not be null");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        this.requestHash = Objects.requireNonNull(requestHash, "requestHash must not be null");
        this.resultStatus = Objects.requireNonNull(resultStatus, "resultStatus must not be null");
        this.resultPayload = resultPayload;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }

    public Long getId() {
        return id;
    }

    public String getActorId() {
        return actorId;
    }

    public String getCommandType() {
        return commandType;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public String getResultStatus() {
        return resultStatus;
    }

    public String getResultPayload() {
        return resultPayload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
