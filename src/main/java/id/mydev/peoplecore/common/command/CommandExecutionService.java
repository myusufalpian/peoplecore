package id.mydev.peoplecore.common.command;

import id.mydev.peoplecore.common.audit.AuditEvent;
import id.mydev.peoplecore.common.security.CurrentAccessPolicy;
import id.mydev.peoplecore.common.security.CurrentCaller;
import org.springframework.security.access.AccessDeniedException;
import id.mydev.peoplecore.common.audit.AuditEventWriter;
import id.mydev.peoplecore.common.outbox.OutboxEvent;
import id.mydev.peoplecore.common.outbox.OutboxEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.util.Assert;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class CommandExecutionService {

    private final CommandReceiptRepository commandReceiptRepository;
    private final AuditEventWriter auditEventWriter;
    private final OutboxEventRepository outboxEventRepository;
    private final CommandResultSerializer serializer;
    private final CommandExecutionCoordinator coordinator;
    private final CurrentAccessPolicy accessPolicy;

    public CommandExecutionService(
        CommandReceiptRepository commandReceiptRepository,
        AuditEventWriter auditEventWriter,
        OutboxEventRepository outboxEventRepository,
        CommandResultSerializer serializer,
        CommandExecutionCoordinator coordinator,
        CurrentAccessPolicy accessPolicy
    ) {
        this.commandReceiptRepository = commandReceiptRepository;
        this.auditEventWriter = auditEventWriter;
        this.outboxEventRepository = outboxEventRepository;
        this.serializer = serializer;
        this.coordinator = coordinator;
        this.accessPolicy = accessPolicy;
    }

    public record CommandContext(
        String actorId,
        String commandType,
        String idempotencyKey,
        String requestPayload,
        Duration receiptTtl
    ) {
        public CommandContext {
            validateIdentity(actorId, "actorId");
            validateIdentity(commandType, "commandType");
            validateIdentity(idempotencyKey, "idempotencyKey");
            Objects.requireNonNull(requestPayload, "requestPayload must not be null");
            if (receiptTtl == null) {
                receiptTtl = Duration.ofDays(7);
            }
            Assert.isTrue(!receiptTtl.isNegative() && !receiptTtl.isZero(), "receiptTtl must be positive");
        }
    }

    public record AuditDescriptor(
        String action,
        String aggregateType,
        Long aggregateId,
        UUID aggregatePublicId,
        String correlationId,
        String reason,
        String details
    ) {}

    public record OutboxDescriptor(
        String eventType,
        int schemaVersion,
        String aggregateType,
        Long aggregateId,
        UUID aggregatePublicId,
        String payload
    ) {}

    public record CommandExecutionPlan<T>(
        Supplier<T> domainAction,
        Class<T> resultType,
        AuditDescriptor audit,
        OutboxDescriptor outbox
    ) {
        public CommandExecutionPlan {
            Objects.requireNonNull(domainAction, "domainAction must not be null");
            Objects.requireNonNull(resultType, "resultType must not be null");
            Assert.isTrue(!Collection.class.isAssignableFrom(resultType)
                && !Map.class.isAssignableFrom(resultType), "Use a concrete result DTO for parameterized results");
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public <T> T executeCommand(CommandContext context, CommandExecutionPlan<T> plan) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(plan, "plan must not be null");
        var caller = CurrentCaller.require();
        if (!caller.getName().equals(context.actorId())) {
            throw new AccessDeniedException("Command actor does not match authenticated caller");
        }
        accessPolicy.checkCommand(caller, context);
        String requestHash = coordinator.requestHash(context.requestPayload());
        coordinator.lock(context.actorId(), context.commandType(), context.idempotencyKey());
        accessPolicy.checkCommand(caller, context);
        var existing = commandReceiptRepository.findByActorIdAndCommandTypeAndIdempotencyKey(
            context.actorId(), context.commandType(), context.idempotencyKey());
        if (existing.isPresent()) {
            CommandReceipt receipt = existing.get();
            if (!receipt.getRequestHash().equals(requestHash)) {
                throw new CommandConflictException("Idempotency key already used with different content");
            }
            if (!"SUCCESS".equals(receipt.getResultStatus())) {
                throw new CommandBusyException("Command has an incomplete receipt");
            }
            accessPolicy.checkReplay(caller, context, receipt.getResultPayload());
            return serializer.deserialize(receipt.getResultPayload(), plan.resultType());
        }

        Instant now = Instant.now();
        T result = plan.domainAction().get();
        Assert.state(result != null || Void.class.equals(plan.resultType()), "Only a void command may return null");
        String serializedResult = serializer.serialize(result);
        serializer.deserialize(serializedResult, plan.resultType());
        CommandReceipt receipt = new CommandReceipt(
            context.actorId(), context.commandType(), context.idempotencyKey(), requestHash,
            "SUCCESS", serializedResult, now, now.plus(context.receiptTtl()));
        commandReceiptRepository.save(receipt);

        if (plan.audit() != null) {
            AuditDescriptor audit = plan.audit();
            AuditEvent auditEvent = new AuditEvent(
                UUID.randomUUID(),
                context.actorId(),
                audit.action(),
                audit.aggregateType(),
                audit.aggregateId(),
                audit.aggregatePublicId(),
                audit.correlationId(),
                audit.reason(),
                audit.details(),
                now
            );
            auditEventWriter.append(auditEvent);
        }

        if (plan.outbox() != null) {
            OutboxDescriptor outbox = plan.outbox();
            OutboxEvent outboxEvent = new OutboxEvent(
                UUID.randomUUID(),
                outbox.eventType(),
                outbox.schemaVersion(),
                outbox.aggregateType(),
                outbox.aggregateId(),
                outbox.aggregatePublicId(),
                outbox.payload(),
                now
            );
            outboxEventRepository.save(outboxEvent);
        }

        return result;
    }

    private static void validateIdentity(String value, String field) {
        Assert.hasText(value, field + " must not be blank");
        Assert.isTrue(value.length() <= 128 && value.indexOf('\0') < 0, field + " is invalid");
    }

    public static String computeSha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
