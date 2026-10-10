# Architecture

## System shape

Peoplecore is a Spring Boot modular monolith. The deployable contains the HTTP API and worker-facing integration boundaries, while PostgreSQL remains the system of record. Each business module (`identity`, `organization`) is split into `api` (`controller`, `dto`, `mapper`), `application` (`service`, `policy`, `command`, `mapper`, `trail`), `domain` (`model`, `repository`, `exception`), and `infrastructure` (`config`, `properties`); shared concerns live under `common` and `infrastructure`. Repository ports live with the domain they serve, services collaborate through each other rather than reaching into another module's repositories, and HTTP mapping stays in `api` mappers. Mutating use cases run through small application command facades that derive deterministic command identities, execute through the shared command receipt boundary with bounded transient-conflict retries, and map audit/outbox events from the actual guarded outcome within the same transaction. This keeps cross-cutting guarantees reusable without introducing distributed service coordination for the current backend scope.

The application is stateless at the process level. PostgreSQL stores durable state, command receipts, audit events, and outbox events. External identities are identified by OIDC issuer and subject; application-owned accounts, bindings, and effective-dated role assignments determine resource access. JWT role claims do not replace these database checks.

## Runtime boundaries

The API boundary is Spring MVC. `RequestCorrelationFilter` establishes a correlation identifier, `ApiSecurityConfig` authenticates requests, and `RequestBodyLimitFilter` runs inside the Spring Security chain after authorization. This ordering prevents unauthenticated requests from being buffered by the application body limit logic. `GlobalExceptionHandler` maps failures to the stable error contract.

The command boundary is represented by `CommandExecutionService`. A command receives an authenticated actor, command type, idempotency key, canonical JSON payload, and a concrete result type. The service owns the transaction and coordinates domain work with the receipt, audit, and outbox writes. Callers provide the domain action and descriptors; persistence and consistency rules remain centralized.

Runtime configuration separates API and worker process roles. The transactional outbox is the implemented asynchronous persistence boundary: successful commands append pending events in the same database transaction. Neither the worker runtime role nor a pending outbox row proves broker publication or invitation delivery; the application contains no outbox delivery consumer or provider dispatcher. The local SQS-compatible service supports integration testing and local development and is not a second source of truth.

## Persistence and transaction model

PostgreSQL stores the core aggregates (`user_accounts` and `employees`) together with `command_receipts`, `audit_events`, and `outbox_events`. Flyway migrations define the schema. Application startup does not implicitly mutate the schema; `MigrationRunner` provides an explicit migration operation with migration credentials, separating schema ownership from the runtime database role.

Commands run with `READ_COMMITTED` isolation and acquire a PostgreSQL transaction advisory lock derived from actor, command type, and idempotency key. The lock serializes retries for the same logical command without requiring an application-wide lock service. The unique receipt constraint is the database backstop. A matching successful receipt is replayed after request-hash and access-policy checks; a different payload using the same key is rejected as a conflict.

Receipt, audit, and outbox writes occur in the command transaction. A domain failure rolls back all three, preventing an audit record or outbound event from claiming a change that was not committed. Audit data is append-only and protected by database rules; readers use an explicit resource scope supplied by the access policy.

## API contract

Successful collection and resource responses use the payload itself as `data`, with `metadata` available for pagination and related response metadata. Pagination is represented by `PageMetadata` and validates page, size, total elements, and total pages at construction time.

Failures use `ErrorResponse`: `key`, `message`, and `timestamp`, with optional validation details. HTTP status codes are selected by the exception handler and security handlers according to the failure class. The contract uses a data/metadata envelope without a success boolean or generic success message.

Input handling applies bounded JSON parsing, duplicate-key rejection, strict trailing-token checks, canonical ordering for request hashes, and request/body limits. Big decimal parsing is used when calculating canonical request hashes so semantically equivalent numeric values are handled predictably.

## Employee and assignment model

Employee employment dates are business dates, independent of the account's access cutoff instant. `PUT /api/v1/employees/{id}/employment` replaces the current employment start/end dates with a required reason; omitting endDate explicitly clears it. Legacy dates remain unknown until HR supplies verified values. Setting dates does not activate/deactivate employment or alter account access. Number/date changes, offboarding and rehire retain structured before/after employee values in the transactional audit; date changes retain previous employment date values there.

`POST /api/v1/employees/{id}/assignments/{assignmentId}/revisions` applies a manager/unit/job-level replacement beginning within the selected interval. Employee locking serializes revision with other assignment writes. The old row retains its original interval and receives supersededAt; when the boundary is after its start, a new retained prefix plus the replacement form the current non-overlapping timeline. Both refer to the old public UUID through supersedesId. Current scope excludes superseded rows, while assignment history returns all revisions with manager public UUID and revision metadata. Backdated revisions preserve the original assignment interval as historical evidence. Scope guards cover current, original and replacement units, including replay, and the command atomically increments authorization generation with receipt/audit/outbox writes. Structured audit details retain before/after assignment values and the retained prefix UUID.

Assignment history is read as a scalar projection with manager public IDs in one SQL statement, so an already managed assignment cannot hide a concurrently committed supersession. History authorization derives the effective organization unit from that same projection before returning any rows; a concurrent transfer cannot combine permission for an earlier unit with data from the new unit. Effective HR scope or an active role with the employee's own binding remains required.

Assignment intervals use inclusive start and exclusive end boundaries. Boundaries are truncated to PostgreSQL microsecond precision before interval validation, overlap checks, splitting, persistence, and result mapping. Intervals that collapse after normalization are rejected. The database interval-order constraint also requires a finite end to be after its start. Its migration validates existing rows and fails if invalid legacy intervals exist; operators must review and remediate those rows before retrying. It does not delete or rewrite historical evidence.

## Identity and employment lifecycle

An account represents an issuer/subject identity; an employee represents employment. Active bindings associate them, while revoked binding rows remain as history. Partial unique indexes enforce one active binding per employee, account, and issuer/subject. Activation and reassociation serialize employee changes and flush the binding write against these constraints, preventing concurrent requests from claiming the same association.

Activation requires an unexpired, issued invitation, its secret, an exact match between its expected issuer/subject and the authenticated JWT identity, and active employment. It consumes the invitation, links the account, and grants EMPLOYEE access atomically. Reassociation revokes the former binding, cuts off the previous account and ends its roles, while carrying an existing earlier access cutoff to the replacement account. Offboarding deactivates employment, revokes outstanding invitations, and sets account access cutoff when applicable. Rehire changes employment status; access restoration is a separate guarded operation that requires active employment and an active binding.

New invitation revocations record revokedAt from the application clock and preserve the first revocation instant; consuming or already-revoked invitations does not change it. V8 adds employee dates, assignment revision metadata and invitation revocation time. Existing revoked invitations keep a null revokedAt because their original revocation time is unavailable.

## Security architecture

When JWT authentication is configured, the decoder verifies the signature using configured JWKS and validates issuer, required expiration, and audience. Identity operations accept independently verified JWT callers. Account status and accessEndsAt, effective role dates, HR unit coverage, and active employee bindings are checked from application data. HrisAccessPolicy implements command permissions, receipt replay guards, and audit resource scopes; the default policy denies access when it does not authorize an operation.

Identity verification and role delegation use the existing HRIS roles as a conservative authority boundary. Issuing/reissuing an invitation or reassociating a binding requires an active account with both an effective HR_ADMIN grant covering the employee and an effective organization-wide SYSTEM_ADMIN grant (null or blank scope). HR_ADMIN alone may manage employment and restore EMPLOYEE/MANAGER within its grant scope; restoring HR_ADMIN, FINANCE_PAYROLL or SYSTEM_ADMIN additionally requires organization-wide SYSTEM_ADMIN and organization-wide HR_ADMIN. Scoped SYSTEM_ADMIN does not confer these powers. Checks run again after employee/command locks and on receipt replay; loss of authority also denies old command replays. No migration automatically grants privileged roles. Initial privileged operators must be provisioned through the controlled database administration process with effective-dated grants and an approved change record.

The designated identity operator must independently establish the employee's exact corporate issuer/subject through the trusted IdP provisioning record or a controlled verification procedure before submitting an invitation or rebind. Email, a bearer secret, or a claim supplied by the recipient is insufficient evidence. `intendedIdentityRef` must identify that verification record for issuance/reissuance; rebind `reason` is mandatory and must reference the replacement verification record. These references must be non-secret identifiers. The authenticated operator and reference are retained in the append-only audit trail. The application enforces operator authority and nonblank references; verification of external evidence remains the operator's responsibility.

Invitation secrets are returned only on first issuance and excluded from new receipts, audit and outbox payloads. V7 removes the legacy top-level `secret` from issuance/reissuance receipts, retaining idempotency keys, hashes and metadata. Malformed/non-object payloads are cleared while their receipt identities remain reserved; replay fails closed. If cleanup finds potentially exposed data, all outstanding invitations are revoked and the authorization generation advances. Operators must reverify and reissue with new command keys. Metadata-only receipts do not trigger blanket revocation.

Run the explicit migration with migration credentials before enabling traffic on an upgrade or a restore from a pre-V7 snapshot. SQL redaction does not erase historical WAL, replicas, exports or backups. Treat such retained copies as secret-bearing: restrict access, follow the approved retention/secure disposal policy, and restore only into an isolated environment followed by V7 before API access. Never serve an unmigrated restored database or reuse an exposed invitation. Backup inventory and secure disposal remain operational responsibilities.

Command execution requires an authenticated caller whose identity matches the command actor. Initial execution and receipt replay pass through access-policy checks; employee mutations refresh managed state and recheck authority after acquiring domain locks. Successful changes increment the shared authorization generation. API DTO mappers, result mappers, and trail mappers keep HTTP representation and audit/outbox serialization separate from domain mutation logic.

Activation admission uses durable budget slots with a 15-minute window: 20 attempts per principal across invitations, five per principal/invitation pair, and 30 non-recipient attempts per known invitation. The expected recipient is exempt from the shared invitation budget but remains subject to both principal limits. A database singleton lock serializes reservations across application instances. Admission commits in a short transaction before the command begins, so failed commands and receipt replays consume budget. Supported activation rejections are recorded in a separate transaction; a rolled-back activation does not erase those records. Secrets are not included in rejection audit messages.

Runtime database access is separated from migration access. The runtime role is restricted and cannot modify append-only audit records or perform administrative PostgreSQL operations. JDBC error logging is disabled where it could expose sensitive database details, while application logging uses safe exception metadata.

Local infrastructure images are pinned and run with non-root identities where supported. PostgreSQL uses the maintained Alpine helper, the Keycloak image is reduced to the required runtime distribution, and the SQS-compatible runtime stages its JVM dependencies into a reproducible image with a generated CycloneDX inventory and SHA-256 hashes. These images are development and integration boundaries; production deployment remains responsible for registry provenance, secret injection, TLS, and operational hardening.

## Architectural decisions and tradeoffs

The modular monolith is the simplest fit for a single transactional data model. It keeps command, audit, and outbox consistency local and avoids premature network failure modes. Module boundaries are enforced by package ownership and tests within one deployable.

PostgreSQL advisory locks are preferable here to a distributed lock service because command coordination already depends on the same transaction database. They are scoped to a transaction and have a bounded wait, but high contention on a single logical command still produces a busy response and requires client retry policy.

The transactional outbox guarantees that event persistence and the originating domain change commit together. That guarantee ends at PostgreSQL: pending events are not proof of external delivery, and provider delivery status is not advanced by the implemented command flow.

Explicit migrations improve startup safety and least privilege at the cost of an operational step in deployment. The API contract is intentionally small and stable; adding operation-specific fields is preferable to introducing a universal envelope that obscures resource semantics.
