# Architecture

## System shape

Peoplecore is a Spring Boot modular monolith. The deployable contains the HTTP API and worker-facing integration boundaries, while PostgreSQL remains the system of record. Modules are organized by responsibility under `common` and `infrastructure`; this keeps cross-cutting guarantees reusable without introducing distributed service coordination for the current backend scope.

The application is intentionally stateless at the process level. Durable state, command receipts, audit events, and outbox events are stored in PostgreSQL. External identity is represented by OIDC issuer and subject fields, while the current HTTP security chain provides authenticated access and a policy seam for resource-specific authorization.

## Runtime boundaries

The API boundary is Spring MVC. `RequestCorrelationFilter` establishes a correlation identifier, `ApiSecurityConfig` authenticates requests, and `RequestBodyLimitFilter` runs inside the Spring Security chain after authorization. This ordering prevents unauthenticated requests from being buffered by the application body limit logic. `GlobalExceptionHandler` maps failures to the stable error contract.

The command boundary is represented by `CommandExecutionService`. A command receives an authenticated actor, command type, idempotency key, canonical JSON payload, and a concrete result type. The service owns the transaction and coordinates domain work with the receipt, audit, and outbox writes. Callers provide the domain action and descriptors; persistence and consistency rules remain centralized.

The worker boundary is the transactional outbox. A successful command can append a pending event in the same database transaction. A future publisher can claim and deliver those events without coupling the request path to an external broker. The local SQS-compatible service is an infrastructure adapter for integration testing and local development, not a second source of truth.

## Persistence and transaction model

PostgreSQL stores the core aggregates (`user_accounts` and `employees`) together with `command_receipts`, `audit_events`, and `outbox_events`. Flyway migrations define the schema. Application startup does not implicitly mutate the schema; `MigrationRunner` provides an explicit migration operation with migration credentials, separating schema ownership from the runtime database role.

Commands run with `READ_COMMITTED` isolation and acquire a PostgreSQL transaction advisory lock derived from actor, command type, and idempotency key. The lock serializes retries for the same logical command without requiring an application-wide lock service. The unique receipt constraint is the database backstop. A matching successful receipt is replayed after request-hash and access-policy checks; a different payload using the same key is rejected as a conflict.

Receipt, audit, and outbox writes occur in the command transaction. A domain failure rolls back all three, preventing an audit record or outbound event from claiming a change that was not committed. Audit data is append-only and protected by database rules; readers use an explicit resource scope supplied by the access policy.

## API contract

Successful collection and resource responses use the payload itself as `data`, with `metadata` available for pagination and related response metadata. Pagination is represented by `PageMetadata` and validates page, size, total elements, and total pages at construction time.

Failures use `ErrorResponse`: `key`, `message`, and `timestamp`, with optional validation details. HTTP status codes are selected by the exception handler and security handlers according to the failure class. The contract deliberately avoids a generic success envelope and a success boolean so each response carries only information useful to its operation.

Input handling applies bounded JSON parsing, duplicate-key rejection, strict trailing-token checks, canonical ordering for request hashes, and request/body limits. Big decimal parsing is used when calculating canonical request hashes so semantically equivalent numeric values are handled predictably.

## Security architecture

The default access policy is deny-by-default. Command execution requires an authenticated caller whose identity matches the command actor, and both first execution and receipt replay pass through policy checks. The policy interface is the extension point for OIDC claims, command permissions, and audit resource scopes.

Runtime database access is separated from migration access. The runtime role is restricted and cannot modify append-only audit records or perform administrative PostgreSQL operations. JDBC error logging is disabled where it could expose sensitive database details, while application logging uses safe exception metadata.

Local infrastructure images are pinned and run with non-root identities where supported. PostgreSQL uses the maintained Alpine helper, the Keycloak image is reduced to the required runtime distribution, and the SQS-compatible runtime stages its JVM dependencies into a reproducible image with a generated CycloneDX inventory and SHA-256 hashes. These images are development and integration boundaries; production deployment remains responsible for registry provenance, secret injection, TLS, and operational hardening.

## Architectural decisions and tradeoffs

The modular monolith is the simplest fit for a single transactional data model. It keeps command, audit, and outbox consistency local and avoids premature network failure modes. The tradeoff is that module boundaries are enforced by package ownership and tests rather than independent deployment; extraction into services should happen only when ownership or scaling needs become concrete.

PostgreSQL advisory locks are preferable here to a distributed lock service because command coordination already depends on the same transaction database. They are scoped to a transaction and have a bounded wait, but high contention on a single logical command still produces a busy response and requires client retry policy.

The transactional outbox favors delivery reliability over immediate publication. It requires a publisher, retry policy, and operational monitoring before production use. Until that worker is deployed, outbox rows are durable pending work rather than proof of external delivery.

Explicit migrations improve startup safety and least privilege at the cost of an operational step in deployment. The API contract is intentionally small and stable; adding operation-specific fields is preferable to introducing a universal envelope that obscures resource semantics.

## Fit and remaining boundaries

This architecture fits the current backend because it provides one source of truth, atomic command behavior, replay safety, auditability, bounded input handling, and a clear path to asynchronous delivery without requiring separate services. The main future boundaries are the concrete domain modules, the OIDC-backed implementation of `CurrentAccessPolicy`, the outbox publisher, retention/cleanup for receipts and events, and production observability for lock contention and delivery lag. Those are extension points rather than reasons to split the current application prematurely.
