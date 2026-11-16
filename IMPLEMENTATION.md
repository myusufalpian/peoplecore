# Implementation

This file describes behavior implemented by the Java application, its SQL migrations, configuration classes and automated tests. Paths below point directly to those executable sources.

## Runtime and persistence

The application uses Java 25, Spring Boot, Spring MVC, Spring Security, Spring Data JPA, JDBC and PostgreSQL. API and worker processes use the same application with different runtime roles:

| Property | Behavior |
|---|---|
| `peoplecore.runtime.role=api` | Default role; starts a servlet application. |
| `peoplecore.runtime.role=worker` | Starts without a web server. Conditional worker components poll queues or durable database work. |
| Any other runtime role | Startup fails. |

[RuntimeEnvironmentPostProcessor](src/main/java/id/mydev/peoplecore/infrastructure/profile/RuntimeEnvironmentPostProcessor.java) disables automatic Flyway execution for both roles. Apply schema migrations explicitly with the migration entry point:

```sh
export PEOPLECORE_MIGRATION_DB_URL='jdbc:postgresql://localhost:54329/peoplecore'
export PEOPLECORE_MIGRATION_DB_USERNAME='<migration-user>'
export PEOPLECORE_MIGRATION_DB_PASSWORD='<migration-password>'
./gradlew migrate
```

[MigrationRunner](src/main/java/id/mydev/peoplecore/infrastructure/migration/MigrationRunner.java) requires all three environment variables. Runtime credentials are separate from migration credentials. The local profile reads `PEOPLECORE_RUNTIME_DB_PASSWORD` and uses Hibernate schema validation. SQL migrations define the tables, constraints, immutable histories and authorization-generation triggers under [the migration source directory](src/main/resources/db/migration).

## Authentication and command execution

[ApiSecurityConfig](src/main/java/id/mydev/peoplecore/common/security/ApiSecurityConfig.java) requires authentication for every HTTP request. HTTP Basic is configured; JWT resource-server support is installed when `peoplecore.security.jwt.jwks-uri` is present. [SecurityJwtConfig](src/main/java/id/mydev/peoplecore/identity/infrastructure/config/SecurityJwtConfig.java) then requires an issuer and audience, validates the issuer and expiration, requires an expiration claim and checks audience membership.

Business access comes from persisted accounts, bindings and effective role assignments. Token identity alone does not establish an employee binding or grant a domain role. Current account state, role scope and the relevant employee/assignment are checked by application services.

Mutation endpoints that declare `Idempotency-Key` require a non-blank value of at most 128 characters with no NUL character. Reuse the same key for the same logical request; use a new key for a different request. [CommandExecutionService](src/main/java/id/mydev/peoplecore/common/command/CommandExecutionService.java) coordinates command receipts, authorization checks, domain work, audit records and outbox events. Command-specific adapters supply the payload, result mapping and authorization checks. Receipt replay does not replace current authorization.

Identifiers exposed by HTTP endpoints are public UUIDs. JPA/database row IDs stay inside persistence and application boundaries. Most business controllers return an `ApiData` envelope; report-export endpoints return their summary directly. Inspect the individual controller before assuming one response envelope for every route.

## Implemented domain entry points

| Domain | HTTP roots and supported operations | Executable entry point |
|---|---|---|
| Employee history | `/api/v1/employees`: create/read, employee-number update, employment dates, assignments and assignment revisions | [EmployeeController](src/main/java/id/mydev/peoplecore/organization/api/controller/EmployeeController.java) |
| Identity lifecycle | `/api/v1/enrollments`: issue/reissue invitations, activate bindings, offboard, rebind, rehire and restore access; `/api/v1/me/access` reads current access | [EnrollmentController](src/main/java/id/mydev/peoplecore/identity/api/controller/EnrollmentController.java), [MeController](src/main/java/id/mydev/peoplecore/identity/api/controller/MeController.java) |
| Contact-profile changes | Own contact profile, draft proposals, submit/resubmit/withdraw, decisions, reassign/retry routing, detail/history and HR correction | [ProfileProposalController](src/main/java/id/mydev/peoplecore/organization/api/controller/ProfileProposalController.java) |
| Attendance context | Schedule/calendar revisions, publication preview, employee context assignments and effective work context | [AttendanceContextController](src/main/java/id/mydev/peoplecore/attendance/api/controller/AttendanceContextController.java) |
| Attendance recording | `/api/v1/attendance/clock-actions`, corrections, actions and history | [AttendanceRecordingController](src/main/java/id/mydev/peoplecore/attendance/api/controller/AttendanceRecordingController.java) |
| Leave | Entitlement opening/adjustment/ledger/balances; requests, portions, cancellation flows, approval rules and routing preview | [LeaveEntitlementController](src/main/java/id/mydev/peoplecore/leave/api/controller/LeaveEntitlementController.java), [LeaveWorkflowController](src/main/java/id/mydev/peoplecore/leave/api/controller/LeaveWorkflowController.java) |
| Private files | `/api/v1/documents`: upload metadata, upload reservation, finalize, status/content, holds and deletion lifecycle | [DocumentUploadController](src/main/java/id/mydev/peoplecore/document/api/DocumentUploadController.java), [DocumentContentController](src/main/java/id/mydev/peoplecore/document/api/DocumentContentController.java) |
| Reimbursement | `/api/v1/reimbursement`: category publication, claim drafts, workflow actions, history and authorized evidence content | [ReimbursementController](src/main/java/id/mydev/peoplecore/reimbursement/api/controller/ReimbursementController.java) |
| Payslips | `/api/v1/payslips/batches`: create/edit/review/publish/read; employee publication list and authenticated content | [PayslipBatchController](src/main/java/id/mydev/peoplecore/payslip/api/PayslipBatchController.java), [PayslipDownloadController](src/main/java/id/mydev/peoplecore/payslip/api/PayslipDownloadController.java) |

The document, reimbursement and payslip HTTP surfaces are conditional on their respective `peoplecore.documents.enabled`, `peoplecore.reimbursement.enabled` and `peoplecore.payslip.enabled` properties. Their presence does not bypass service-level authorization or required storage/verification dependencies.

## Private-file flow

1. Create metadata with `POST /api/v1/documents/uploads`, including `employeeId`, `purpose`, `fileName`, `mediaType` and `byteSize`. The controller limits the declared size to 10 MiB.
2. Reserve a presigned upload URL with `POST /api/v1/documents/{documentId}/upload-url` and upload the bytes to the reserved location.
3. Call `POST /api/v1/documents/{documentId}/finalize`. Finalization and verification are separate from merely obtaining an upload URL.
4. Read `GET /api/v1/documents/{documentId}` for status. Content reads use authenticated application endpoints rather than exposing a permanent private-object URL.

All three mutation routes require `Idempotency-Key`. [DocumentUploadController](src/main/java/id/mydev/peoplecore/document/api/DocumentUploadController.java) rejects creation/reservation/finalization with service-unavailable when the verification scheduler is unavailable. Verification, scanner and transfer dependencies are configured separately in [the document configuration package](src/main/java/id/mydev/peoplecore/document/infrastructure/config).

Reimbursement evidence and published payslip reads pass their domain-specific authorization into document content access. [RevocableDocumentTransfer](src/main/java/id/mydev/peoplecore/document/application/service/RevocableDocumentTransfer.java) bounds transfer concurrency and periodically checks continued authorization. Legal holds and deletion state are explicit persisted lifecycle data, exposed through [DocumentHoldController](src/main/java/id/mydev/peoplecore/document/api/DocumentHoldController.java) and [DocumentDeletionController](src/main/java/id/mydev/peoplecore/document/api/DocumentDeletionController.java).

## Durable event processing

[EventRoutingPolicy](src/main/java/id/mydev/peoplecore/common/outbox/EventRoutingPolicy.java) is the event-name allowlist. Metadata-only events are retained as command/outbox evidence but marked ignored by publication. Known asynchronous events route to `notifications`, except `EXPORT_REQUESTED`, which routes to `exports`. Unknown asynchronous input is quarantined rather than dispatched to an arbitrary handler.

[EventDispatchService](src/main/java/id/mydev/peoplecore/common/outbox/EventDispatchService.java) claims publication work in a short transaction, sends outside that transaction and records completion against the claimed generation. Failure defers work with jittered backoff. A known event whose handler is unavailable remains deferred rather than being acknowledged as completed.

[EventIngestionService](src/main/java/id/mydev/peoplecore/common/outbox/EventIngestionService.java) admits consumer/event identity and invokes matching handlers in one transaction. It rejects a wrong consumer, an event more than 60 seconds in the future, an event older than the 14-day replay horizon, or an unsupported event. Inbox admission prevents the same consumer/event pair from repeating materialization after normal redelivery.

[EventTransportConfig](src/main/java/id/mydev/peoplecore/infrastructure/queue/EventTransportConfig.java) starts the SQS transport only when `peoplecore.events.enabled=true` and the runtime role is `worker`. Configure a region and source/DLQ URLs for both consumers. Queue endpoints require HTTPS except for loopback HTTP. Retention validation defaults on; disabling it is restricted to the local profile with a loopback endpoint. The queue adapter validates topology before starting.

The conditional Operations API is `/api/v1/operations/event-replays`:

- `POST` creates a reasoned replay with `consumer`, `reason` and up to 100 `eventIds`, using an idempotency key.
- `GET /{id}` reads the replay.
- `POST /{id}/cancel` requests cancellation with a reason.

[EventReplayService](src/main/java/id/mydev/peoplecore/common/outbox/EventReplayService.java) enforces Operations authority and replay constraints. Replay republishes existing event identity; it does not rerun the original business command. The dispatcher pauses replay publication while the oldest normal pending publication is older than 300 seconds.

## Notifications and invitation delivery

[NotificationIntentService](src/main/java/id/mydev/peoplecore/notification/application/NotificationIntentService.java) and domain resolvers materialize delivery work from stored event-time intent. The delivery pins recipient, account/assignment context, template and verified destination identity/version. A later address or reviewer change does not silently retarget old work.

[NotificationTransactions](src/main/java/id/mydev/peoplecore/notification/application/NotificationTransactions.java) separates claim, preparation and completion into transactions. Preparation rechecks the claimed generation, live lease, account/domain eligibility and the pinned current verified destination. Ineligible work becomes `SUPPRESSED`. Ordinary messages contain event/reference metadata and an authenticated application link. Invitation messages carry the activation secret in the URL fragment, generated from identity-owned encrypted material.

[NotificationWorker](src/main/java/id/mydev/peoplecore/notification/application/NotificationWorker.java) calls SES outside the preparation transaction. A definitely-unsent retryable failure can return normal work to `PENDING`, up to three attempts. An uncertain result becomes `UNKNOWN`; it is not automatically resent. Manual attempts do not receive the normal automatic retry sequence. The worker pauses when `peoplecore.recovery.isolated=true`.

[NotificationAcceptance](src/main/java/id/mydev/peoplecore/notification/application/NotificationAcceptance.java) is shared by synchronous completion, trusted SES feedback and Operations reconciliation. It locks admission, then the exact identity-owned invitation context, then the delivery. Mapping invitation `deliveryStatus` to `SENT` requires persisted `ACCEPTED` or `DELIVERED` state, matching generation and the current attempt UUID. Stale evidence cannot mark another current attempt accepted. A valid historical acceptance can still update this summary after invitation revocation, expiry or ciphertext disposal; that update does not reactivate the invitation or create another send.

[KmsInvitationCipher](src/main/java/id/mydev/peoplecore/identity/infrastructure/delivery/KmsInvitationCipher.java) protects invitation material. [JdbcNotificationIdentityRepository](src/main/java/id/mydev/peoplecore/identity/infrastructure/persistence/JdbcNotificationIdentityRepository.java) binds acceptance to the immutable invitation/employee/destination/version tuple and disposes invalid invitation ciphertext in bounded batches. Invitation-source identities, attempts and evidence are protected by [the notification integrity migration](src/main/resources/db/migration/V30__protect_notification_identity.sql).

[SesFeedbackService](src/main/java/id/mydev/peoplecore/notification/application/SesFeedbackService.java) accepts correlated Send, Delivery, Bounce, Complaint, Reject and Rendering Failure evidence through the feedback worker. Parsing is bounded, the configured SNS topic is checked and the repository correlates delivery, attempt and provider message identity. Bounce/complaint evidence suppresses the destination. [SesFeedbackWorker](src/main/java/id/mydev/peoplecore/notification/infrastructure/provider/SesFeedbackWorker.java) validates the configured SQS/SNS source policy; there is no public HTTP feedback route.

### Notification configuration

| Enablement | Additional configuration used by the implementation |
|---|---|
| `peoplecore.notifications.enabled=true` | `peoplecore.notifications.region`, `peoplecore.notifications.invitation-key-id`, HTTPS `peoplecore.notifications.application-url`; creates KMS-backed invitation services and Operations endpoints. |
| `peoplecore.notifications.send.enabled=true` with notifications enabled | `peoplecore.notifications.sender`, `peoplecore.notifications.configuration-set`; the send lifecycle runs only in the worker role. |
| `peoplecore.notifications.feedback.enabled=true` with notifications and events enabled | `peoplecore.notifications.feedback.queue-url`, `peoplecore.notifications.feedback.topic-arn`; feedback consumption runs only in the worker role. |

The opt-in conditions above do not match when their enablement properties are absent. Credential resolution is performed by the configured AWS SDK clients.

Verified destinations are written through `PUT /api/v1/enrollments/employees/{id}/notification-destination`, with `address`, `evidenceRef`, `reason` and `Idempotency-Key`. [NotificationDestinationService](src/main/java/id/mydev/peoplecore/identity/application/service/NotificationDestinationService.java) performs the authorization and persists verification evidence.

### Operations actions

[NotificationSupportController](src/main/java/id/mydev/peoplecore/notification/api/NotificationSupportController.java) exposes `/api/v1/operations/notification-deliveries` when notifications are enabled. List/detail/evidence/attention reads and retry/reconcile/replacement mutations require [NotificationSupportService](src/main/java/id/mydev/peoplecore/notification/application/NotificationSupportService.java) authorization.

| Route suffix | Mutation input and behavior |
|---|---|
| `/{id}/retry` | `expectedVersion`, `reason`, `acknowledgePossibleDuplicate`; UNKNOWN requires explicit duplicate acknowledgement. |
| `/{id}/reconcile` | `expectedVersion`, `reason`, persisted `evidenceId`; applies correlated provider evidence through the shared acceptance mapping. |
| `/{id}/replacements` | Same input shape as retry; creates linked fresh delivery work using a changed verified destination and preserves the original recipient/account/assignment/template. |

Each mutation requires `Idempotency-Key`. Invitations reject the replacement path and require authorized identity reissuance because their encrypted secret is bound to the original destination. The service checks current global Operations authority, optimistic version, reason, idempotency, recovery isolation and a support-action budget.

## Reports and audit search

With `peoplecore.reports.enabled=true`, [ReportController](src/main/java/id/mydev/peoplecore/reporting/api/ReportController.java) exposes:

| GET path | Time selector |
|---|---|
| `/api/v1/reports/attendance` | `from` and exclusive `to` |
| `/api/v1/reports/leave-balances` | `year` |
| `/api/v1/reports/request-status` | `from` and exclusive `to` |
| `/api/v1/reports/reimbursements` | `from` and exclusive `to` |
| `/api/v1/reports/payslip-status` | `month`, formatted as `YYYY-MM` |
| `/api/v1/audit-events` | `from` and exclusive `to` |

[ScopedReportService](src/main/java/id/mydev/peoplecore/reporting/application/ScopedReportService.java) supports report version 1. Date-range reports require an explicit non-empty interval of at most 31 days. Leave balances require one entitlement year; payslip status requires one month. Unsupported combinations and unknown status/workflow values are rejected. Optional employee/unit/status/workflow/category and audit-specific filters are validated before execution.

Reports execute authorization and domain-owned query adapters in a read-only Repeatable Read snapshot. The snapshot records database time, canonical filter hash, authorization generation and the next scheduled authorization boundary. Interactive results are capped at 10,000 rows; export snapshots are capped at 50,000 rows. An oversized result fails rather than returning a silently truncated report. The service uses deadlines and a 30-second snapshot transaction timeout; query adapters also bound JDBC query time.

[ReportDtoMapper](src/main/java/id/mydev/peoplecore/reporting/api/ReportDtoMapper.java) defaults to 50 rows per response and permits page sizes from 1 to 100. It returns `nextCursor`, `totalRows`, `reportVersion`, `filterHash`, `dataAsOf`, timezone `Asia/Jakarta`, `historical=false` and `unavailableColumns`. A cursor binds the filter hash and offset; each HTTP request still reads a fresh authorized snapshot. It is not a retained historical snapshot. Employee names are currently unavailable and reported through `unavailableColumns` when that column is present.

[ReportAuthorizationService](src/main/java/id/mydev/peoplecore/identity/application/service/ReportAuthorizationService.java) establishes active account/report authority; individual domain adapters apply current subject scope. Audit search projects safe event/actor/action/domain/resource/timestamp/reason-presence metadata, not raw audit details or reason text.

[JdbcAuditReportQuery](src/main/java/id/mydev/peoplecore/common/audit/infrastructure/persistence/JdbcAuditReportQuery.java) requires authority over every subject in the current revision of a payslip batch. For each subject, effective HR or Finance scope must cover the current organizational unit, or the caller must have the explicit global Operations exception. Employee/unit/resource filters cannot reduce the subject set used for this check. The same adapter supplies audit export rows.

Example GET requests, with an already-issued valid token:

```sh
export PEOPLECORE_API_URL='https://peoplecore.example.test'
export PEOPLECORE_ACCESS_TOKEN='<access-token>'
curl --fail-with-body --get "$PEOPLECORE_API_URL/api/v1/reports/attendance" \
  -H "Authorization: Bearer $PEOPLECORE_ACCESS_TOKEN" \
  --data-urlencode 'from=2026-11-01' \
  --data-urlencode 'to=2026-12-01' \
  --data-urlencode 'size=50'

curl --fail-with-body --get "$PEOPLECORE_API_URL/api/v1/audit-events" \
  -H "Authorization: Bearer $PEOPLECORE_ACCESS_TOKEN" \
  --data-urlencode 'from=2026-11-01' \
  --data-urlencode 'to=2026-12-01' \
  --data-urlencode 'workflowType=PAYSLIP_BATCH'
```

## CSV export lifecycle

With `peoplecore.exports.enabled=true`, [ReportExportController](src/main/java/id/mydev/peoplecore/reportexport/api/ReportExportController.java) exposes:

- `POST /api/v1/report-exports`: validates `format=CSV`, a versioned report request and `Idempotency-Key`; returns HTTP 202 with a summary and `Location`.
- `GET /api/v1/report-exports/{id}`: returns an owned job summary after fresh report authorization.
- `GET /api/v1/report-exports/{id}/content`: streams owned READY content after fresh authorization, using `Cache-Control: no-store`, a CSV attachment filename and `nosniff`.

[ReportExportService](src/main/java/id/mydev/peoplecore/reportexport/application/ReportExportService.java) allows at most two unfinished jobs per requester and expires jobs 24 hours after creation. Job creation records requester identity and a canonical report request in the command transaction. [ReportExportWorker](src/main/java/id/mydev/peoplecore/reportexport/application/ReportExportWorker.java) generates rows from the same scoped report service and uploads an immutable candidate.

Publication requires the current lease generation, a live lease, unexpired job, snapshot age no greater than 15 minutes, current report authorization, unchanged authorization generation and no passed scheduled boundary. Failed authorization invalidates the job instead of publishing stale data. Download repeats ownership/account/report/generation/boundary/expiry checks and rechecks during the bounded revocable transfer. Job ownership alone is insufficient to read the artifact.

[ReportCsvWriter](src/main/java/id/mydev/peoplecore/reportexport/application/ReportCsvWriter.java) emits UTF-8, quoted fields and CRLF rows, escapes embedded quotes, prefixes spreadsheet-formula/control-leading values and caps the generated artifact at 10 MiB. [ExportArtifactStorage](src/main/java/id/mydev/peoplecore/reportexport/infrastructure/storage/ExportArtifactStorage.java) owns immutable candidate object operations and reconciliation. Export processing, expiry and cleanup remain durable database work rather than being inferred from one queue delivery.

Configure `peoplecore.exports.storage.endpoint`, `.region` and `.bucket` for artifact storage. Endpoint validation permits HTTPS and loopback HTTP. `peoplecore.exports.worker.enabled=true` plus the worker runtime role enables polling; the default poll interval is `PT5S`. Recovery isolation makes export service operations unavailable and pauses relevant background work.

Example creation and authenticated retrieval:

```sh
curl --fail-with-body "$PEOPLECORE_API_URL/api/v1/report-exports" \
  -H "Authorization: Bearer $PEOPLECORE_ACCESS_TOKEN" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: attendance-export-example-1' \
  --data '{"format":"CSV","report":{"reportType":"ATTENDANCE","reportVersion":1,"from":"2026-11-01","to":"2026-12-01"}}'

export PEOPLECORE_EXPORT_ID='<export-public-uuid>'
curl --fail-with-body "$PEOPLECORE_API_URL/api/v1/report-exports/$PEOPLECORE_EXPORT_ID" \
  -H "Authorization: Bearer $PEOPLECORE_ACCESS_TOKEN"
curl --fail-with-body "$PEOPLECORE_API_URL/api/v1/report-exports/$PEOPLECORE_EXPORT_ID/content" \
  -H "Authorization: Bearer $PEOPLECORE_ACCESS_TOKEN" \
  --output report.csv
```

## Automated checks

[The Gradle build](build.gradle) separates unit/HTTP tests from PostgreSQL-tagged tests and configures combined line/branch coverage minimums of 85%. `check` depends on both test groups through coverage-data verification. The parser JAR and dependency inventory are built separately and included in application packaging.

```sh
./gradlew test --no-daemon
./gradlew postgresIntegrationTest --no-daemon
./gradlew check bootJar --no-daemon
```

[PostgresFixture](src/test/java/id/mydev/peoplecore/support/PostgresFixture.java) defaults to `jdbc:postgresql://localhost:54339/peoplecore_tests` and creates an isolated schema. Tests explicitly invoke its Flyway runner and runtime-role provisioning helpers as needed; closing the fixture removes its schema and runtime role. Override `PEOPLECORE_TEST_DB_URL`, `PEOPLECORE_TEST_DB_USERNAME` and `PEOPLECORE_TEST_DB_PASSWORD` for an isolated test server; the fixture requires the database name `peoplecore_tests`.

Focused behavior coverage is implemented in [NotificationAcceptanceTest](src/test/java/id/mydev/peoplecore/notification/application/NotificationAcceptanceTest.java), [NotificationContextPostgresTest](src/test/java/id/mydev/peoplecore/notification/application/NotificationContextPostgresTest.java), [InvitationAcceptancePostgresTest](src/test/java/id/mydev/peoplecore/notification/infrastructure/persistence/InvitationAcceptancePostgresTest.java), [PayslipBatchAuditScopePostgresTest](src/test/java/id/mydev/peoplecore/payslip/PayslipBatchAuditScopePostgresTest.java), [ScopedReportsPostgresTest](src/test/java/id/mydev/peoplecore/reporting/ScopedReportsPostgresTest.java) and [ReportExportAuthorizationPostgresTest](src/test/java/id/mydev/peoplecore/reportexport/application/ReportExportAuthorizationPostgresTest.java). These cover persisted acceptance states, stale evidence, reconciliation, local worker delivery and intended-identity activation through an in-process email-provider fixture, complete batch scope, report snapshots and export revocation boundaries. The local provider fixture does not send email or establish live-provider behavior.
