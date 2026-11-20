# Peoplecore Backend

Peoplecore is a Java backend for employee records, organization assignments, identity enrollment, contact-profile approvals, attendance, annual leave, private documents, reimbursements and payslip publication. It also implements durable event transport, email delivery, scoped operational reports and CSV exports. The same application can run as an HTTP API or a non-web worker.

## Stack and source layout

[build.gradle](build.gradle) defines Java 25, Spring Boot 4.1.1, Spring MVC, Spring Data JPA, Spring Security, Flyway, Jackson 3, AWS SDK v2 and JaCoCo. PostgreSQL holds application state. AWS clients integrate S3-compatible private storage, SQS, SES v2 and KMS. A separate document-parser JAR uses PDFBox and is packaged into the executable application.

Production code is under [src/main/java/id/mydev/peoplecore](src/main/java/id/mydev/peoplecore):

| Package | Responsibility |
|---|---|
| `common` | HTTP errors, request limits, command receipts, idempotency, audit, outbox and shared security |
| `identity` | Accounts, effective roles, employee bindings, enrollment, verified delivery destinations and authorization fences |
| `organization` | Employees, effective-dated assignments, contact profiles and proposed profile changes |
| `approval` | Shared admission coordination and profile approval-rule snapshots |
| `attendance` | Schedule/calendar revisions, work context, clock events and correction workflows |
| `leave` | Annual entitlements, ledger, request review and cancellation |
| `document` | Private uploads, verification, authenticated streaming, legal holds and deletion |
| `reimbursement` | Categories, claims, review and payment records |
| `payslip` | Batch review, publication history and employee access |
| `notification` | Frozen recipients, delivery attempts, provider evidence and operator repair |
| `reporting` | Scoped reports through domain-owned query adapters |
| `reportexport` | Durable CSV generation, immutable artifacts and authorized downloads |
| `infrastructure` | Runtime configuration, database guards, migrations and queue adapters |

Modules separate HTTP contracts, application services, domain models/repository contracts and infrastructure adapters. The entry point is [PeoplecoreApplication](src/main/java/id/mydev/peoplecore/PeoplecoreApplication.java). Versioned SQL is under [src/main/resources/db/migration](src/main/resources/db/migration).

## Local setup

Install Java 25 and Docker with Compose. The Gradle wrapper is included. [compose.yaml](compose.yaml) provides these loopback services:

| Service | Address |
|---|---|
| PostgreSQL | `127.0.0.1:54329` |
| SQS-compatible ElasticMQ | `127.0.0.1:4566` |
| Keycloak | `127.0.0.1:8081` |

Set `PEOPLECORE_DB_PASSWORD`, `PEOPLECORE_RUNTIME_DB_PASSWORD`, `KEYCLOAK_ADMIN_USERNAME` and `KEYCLOAK_ADMIN_PASSWORD` in the shell environment before invoking Compose. Compose requires all four variables when resolving the configuration. A Compose environment file does not export values into the shell used by Gradle.

Start the database, wait until it accepts connections, migrate as the owner, then provision the runtime role:

```bash
docker compose up -d postgres
docker compose exec postgres pg_isready -U peoplecore -d peoplecore
export PEOPLECORE_MIGRATION_DB_URL=jdbc:postgresql://localhost:54329/peoplecore
export PEOPLECORE_MIGRATION_DB_USERNAME=peoplecore
export PEOPLECORE_MIGRATION_DB_PASSWORD="$PEOPLECORE_DB_PASSWORD"
./gradlew migrate
docker compose --profile provision run --rm postgres-runtime-provision
```

The [migration runner](src/main/java/id/mydev/peoplecore/infrastructure/migration/MigrationRunner.java) requires those three migration variables. Normal application startup forces Flyway off. Rerun [runtime provisioning](local/postgres/provision-runtime.sql) after applying migrations so the runtime role receives current grants. The runtime role must not own schema objects, inherit privileged roles, create schema objects or mutate existing audit events; [RuntimeDatabaseGuard](src/main/java/id/mydev/peoplecore/infrastructure/migration/RuntimeDatabaseGuard.java) enforces these checks.

The `local` profile configures PostgreSQL with `peoplecore_runtime` and `PEOPLECORE_RUNTIME_DB_PASSWORD`. Configure JWT validation and accepted organization units explicitly:

```bash
export PEOPLECORE_SECURITY_JWT_ISSUER_URI="https://identity.example.com/realms/peoplecore"
export PEOPLECORE_SECURITY_JWT_AUDIENCE="peoplecore"
export PEOPLECORE_SECURITY_JWT_JWKS_URI="https://identity.example.com/realms/peoplecore/protocol/openid-connect/certs"
export PEOPLECORE_ORGANIZATION_ALLOWED_UNITS="ENG,FIN"
./gradlew bootRun --args="--spring.profiles.active=local"
```

Replace the identity-provider values with those matching the provisioned account identities and signed tokens. Starting local Keycloak uses `docker compose up -d keycloak`; it does not automatically configure the application's JWT settings or bootstrap application administrators.

`peoplecore.runtime.role` defaults to `api`. Set it to `worker` for a process without an HTTP server:

```bash
./gradlew bootRun --args="--spring.profiles.active=local --peoplecore.runtime.role=worker"
```

Optional processors still require their own feature flags and dependencies.

## API and authorization

Business access uses a verified JWT issuer/subject together with active database accounts, effective role assignments, current employee bindings and organization scope. Token role claims do not assign application roles. The shared HTTP security chain also supports Basic authentication, while business identity checks require JWT identity. Initial application accounts and grants must be provisioned through controlled database administration; there is no administrator bootstrap endpoint.

| API family | Main surfaces |
|---|---|
| Employees and identity | `/api/v1/employees`, `/api/v1/me/access`, `/api/v1/enrollments` |
| Contact-profile changes | `/api/v1/employees/me/profile-proposals`, `/api/v1/profile-proposals/{id}` |
| Approval configuration | `/api/v1/approval/profile-rule-sets`, `/api/v1/approval/attendance-rule-sets`, leave rule-set routes |
| Attendance and work context | `/api/v1/attendance`, employee work-context and schedule/calendar assignment routes |
| Annual leave | `/api/v1/leave/entitlements`, leave-request and cancellation routes |
| Documents and finance | `/api/v1/documents`, `/api/v1/reimbursement`, `/api/v1/payslips` |
| Reports | `/api/v1/reports/{type}`, `/api/v1/audit-events` |
| Exports | `/api/v1/report-exports` |
| Operator repair | `/api/v1/operations/event-replays`, `/api/v1/operations/notification-deliveries` |

Domain mutations use `Idempotency-Key`. Command receipts, domain state, audit and outbox events commit in the same transaction. Repeating a command rechecks current authority; reusing a key with a different payload conflicts. Storing an outbox event does not imply external delivery.

Successful responses use the endpoint's typed result; collection responses use `data` and `metadata`. Errors contain `key`, `message`, `timestamp` and optional field-validation `details`. Correlation IDs, body-size limits and strict JSON parsing are implemented at the request boundary. See the actual handlers under [common/api](src/main/java/id/mydev/peoplecore/common/api) and [common/security](src/main/java/id/mydev/peoplecore/common/security).

## Optional features

These feature switches are opt-in; the checked-in application configuration does not enable them:

| Property | Enabled behavior and dependencies |
|---|---|
| `peoplecore.documents.enabled` | Document HTTP operations; storage and verification dependencies are configured separately |
| `peoplecore.documents.deletion.enabled` | Deletion/restore operations and cleanup when documents are enabled; scheduled cleanup also needs storage |
| `peoplecore.documents.transfer.enabled` | Revocable document transfers; requires explicit `max-concurrent`, `poll-interval` and `check-timeout` under the same prefix |
| `peoplecore.reimbursement.enabled` | Reimbursement categories, claims, reviews and payment recording |
| `peoplecore.payslip.enabled` | Payslip batches and publication lists |
| `peoplecore.payslip.download.enabled` | Published payslip content when payslips are enabled |
| `peoplecore.events.enabled` | Worker SQS transport; requires region and notification/export queue and DLQ URLs |
| `peoplecore.events.replay.enabled` | Restricted operator replay API; the transport worker processes accepted replay work |
| `peoplecore.notifications.enabled` | Notification intents, destination administration and support API; requires region, invitation key ID and HTTPS application URL |
| `peoplecore.notifications.attention.enabled` | Independent alert worker; requires authenticated `attention.webhook-url` and `attention.token`, plus worker runtime |
| `peoplecore.notifications.send.enabled` | SES sending when notifications are enabled; requires sender/configuration set, worker runtime and the independent alert worker |
| `peoplecore.notifications.feedback.enabled` | Trusted SES feedback; the worker additionally needs events enabled, feedback queue/topic configuration and the independent alert worker |
| `peoplecore.reports.enabled` | Scoped reports and audit search |
| `peoplecore.exports.enabled` | CSV export API; generation requires configured private export storage |
| `peoplecore.exports.download.enabled` | Streaming export downloads via `exportRevocableTransfer` when exports are enabled |
| `peoplecore.exports.worker.enabled` | Export processing when exports are enabled and runtime role is worker |

Storage-backed document operations use the configuration in [DocumentStorageConfig](src/main/java/id/mydev/peoplecore/document/infrastructure/storage/DocumentStorageConfig.java). Verification uses separately configured scanner, parser and timing settings. Authenticated content transfer repeatedly checks authorization. Deletion blocks reads immediately; physical cleanup remains retryable and does not treat an expired copy lease as proof that storage I/O stopped.

[EventTransportConfig](src/main/java/id/mydev/peoplecore/infrastructure/queue/EventTransportConfig.java) validates queue redrive and retention topology at startup. The retention-check exception requires the `local` profile and a loopback endpoint. Start the emulator with `docker compose up -d sqs`; the queue definitions are in [local/sqs/elasticmq.conf](local/sqs/elasticmq.conf).

Notifications preserve the intended employee/account/assignment and verified destination version. Invitations use KMS-protected material bound to the invitation and destination. An uncertain send becomes `UNKNOWN` and is not automatically resent. Synchronous acceptance, correlated feedback and evidence-based reconciliation share the invitation-summary mapping. Destination replacement creates linked work while retaining the original delivery; invitations require authorized reissuance instead.

Failure attention is durable and idempotent per event/delivery generation/code, including trusted SES failures and missing destinations. The independent alert worker polls every five seconds and sends only attention/event/delivery references, code and creation time to a configured webhook. HTTPS is required except for loopback HTTP; redirects are disabled, authentication uses a configured bearer token, and calls are bounded at two seconds. A 2xx response acknowledges the alert. Failures retry after 30 seconds, while lease fencing recovers crashed workers. The webhook receives the attention UUID as `Idempotency-Key` and must deduplicate repeat delivery after an uncertain acknowledgment. The Operations attention count includes all unacknowledged alerts without an age window. Sending and feedback workers reject startup without the alert worker.

Reports support attendance, leave balances, request status, reimbursements, payslip status and safe audit metadata. Ranged reports require an explicit half-open range of at most 31 days; balances select one year and payslip status selects one month. Interactive selection is bounded at 10,000 rows and a 30-second budget. Pagination defaults to 50 rows, with a maximum page size of 100. The source employee model has no name field, so response metadata marks `employeeName` unavailable where that column is present. Batch audit scope checks every employee in the current revision independently of display filters.

CSV exports use the same scoped query adapters, with a 50,000-row limit, 10 MiB artifact limit and 120-second generation budget. At most two unfinished jobs are allowed per requester. Finalization requires a snapshot no older than 15 minutes, and jobs expire 24 hours after creation. Download requires current requester authority, a matching authorization generation and an unexpired boundary; content is streamed through a revocable transfer rather than a public object URL. [ReportExportConfig](src/main/java/id/mydev/peoplecore/reportexport/infrastructure/config/ReportExportConfig.java) defines private storage and transfer wiring.

`peoplecore.recovery.isolated=true` (or an active `ISOLATED`/`RECONCILING` database recovery incident in `service_recovery_incident`) suspends normal domain commands, event/replay/email/export processing, document verification/deletion schedulers, and document/payslip/export content download grants via `ServiceRecoveryGate`. `GET /api/v1/operations/health-summary` reports database job-age and pending counts across `outbox_events`, `notification_delivery`, `notification_attention_alert`, `report_export_job`, and `document_verification_attempt`, while `/api/v1/operations/recovery/incidents/*` manages post-restore identity/invitation reconciliation, storage checksum inventory, and three-role (`HR_ADMIN`, `FINANCE_PAYROLL`, `SYSTEM_ADMIN`) reopening sign-off.

## Build and verification

```bash
./gradlew test
./gradlew postgresIntegrationTest
./gradlew check
./gradlew bootJar
```

`test` excludes PostgreSQL-tagged tests. `postgresIntegrationTest` uses a separate database named `peoplecore_tests`, defaulting to `jdbc:postgresql://localhost:54339/peoplecore_tests`. Configure its credentials with `PEOPLECORE_TEST_DB_USERNAME` and `PEOPLECORE_TEST_DB_PASSWORD`, and override the URL with `PEOPLECORE_TEST_DB_URL`. The [PostgresFixture](src/test/java/id/mydev/peoplecore/support/PostgresFixture.java) creates isolated schemas and temporary runtime roles, then removes them. Its administrator therefore needs schema/role creation privileges. Do not use the application database for these tests.

`check` runs both test groups and requires their JaCoCo execution data, with minimum 85% line and branch coverage. Test and coverage output is written under `build/reports/`. `bootJar` packages the application and document-parser runtime. Optional local integration tests use `PEOPLECORE_SQS_TEST_ENDPOINT` and `PEOPLECORE_R2_LOCAL_S3_ENDPOINT` when their services are available.

The executable verification helpers include [local/verify-sqs.py](local/verify-sqs.py), [local/verify-keycloak.mjs](local/verify-keycloak.mjs) and [local/security/check.sh](local/security/check.sh). The security helper scans built artifacts/images and uses `TRIVY_BIN` to select the scanner. The CI configuration in [.github/workflows/backend-check.yml](.github/workflows/backend-check.yml) builds the local services, runs backend verification and service contracts, then runs security scans and uploads the generated results.

### Local invitation delivery verification

`NotificationContextPostgresTest.localDummyProviderCompletesAuthorizedInvitationAndIntendedIdentityActivation` exercises the local application flow with PostgreSQL and an in-process `EmailProvider` fixture. It verifies authorized invitation issue, durable worker delivery, captured invitation content, rejection for a different identity and activation for the intended issuer/subject. This fixture does not call SES or claim mailbox delivery.

```bash
./gradlew postgresIntegrationTest --tests 'id.mydev.peoplecore.notification.application.NotificationContextPostgresTest' --no-daemon
```

### Configured invitation verification helper

[local/verify-invitation.mjs](local/verify-invitation.mjs) provides separate issuance and mailbox-activation phases. Use an unbound controlled employee and an explicitly configured application environment. Issuance sets the verified destination, creates an invitation and waits up to five minutes for provider acceptance evidence. Activation consumes the link received in the controlled mailbox, first checks denial for a different authenticated identity, then verifies the intended binding. The API-returned secret is never used for activation; tokens, mailbox links and secrets are omitted from output.

Both phases require `PEOPLECORE_API_URL`, `PEOPLECORE_EXPECTED_ISSUER` and `PEOPLECORE_EXPECTED_SUBJECT`. Issuance also requires `PEOPLECORE_OPERATOR_TOKEN`, `PEOPLECORE_CONTROLLED_EMPLOYEE_ID`, `PEOPLECORE_VERIFIED_IDENTITY_REF` and `PEOPLECORE_CONTROLLED_RECIPIENT`. Activation requires `PEOPLECORE_INVITATION_MAILBOX_LINK`, `PEOPLECORE_WRONG_IDENTITY_TOKEN` and `PEOPLECORE_RECIPIENT_TOKEN`. Supply sensitive values through the environment.

```bash
node --test local/verify-invitation.test.mjs
PEOPLECORE_ALLOW_LIVE_EMAIL=YES node local/verify-invitation.mjs issue
PEOPLECORE_ALLOW_LIVE_EMAIL=YES node local/verify-invitation.mjs activate
```

The helper tests substitute HTTP responses and send no email. The local PostgreSQL test above verifies application behavior without AWS. The optional live phases require a separately configured provider environment and are outside local acceptance evidence.
