# Peoplecore Backend

Peoplecore is a Spring Boot backend that provides employee records, effective-dated organization assignments, identity enrollment, and reviewed contact-profile changes through a transactional HTTP API. This repository contains the API and worker runtime. Client applications are separate consumers of the HTTP API.

## Technology stack

- Java 25 with the Gradle wrapper.
- Spring Boot 4.1.1 and Spring MVC.
- Spring Data JPA with PostgreSQL 18.
- Flyway for versioned database migrations.
- Spring Security with JWT resource-server validation and database-backed account, role, and organization-scope checks.
- Jackson 3 for JSON serialization and Bean Validation for request validation.
- AWS SDK v2 S3 client for object storage integration.
- JUnit 5, Spring Boot test slices, isolated-schema PostgreSQL fixtures, and JaCoCo for verification.
- Docker Compose for local PostgreSQL, SQS-compatible ElasticMQ, and Keycloak services.

The application is started from `id.mydev.peoplecore.PeoplecoreApplication`. Database migrations are intentionally run explicitly through the `migrate` command; normal application startup does not mutate the database schema.

## Repository structure

```text
src/main/java/id/mydev/peoplecore/
├── common/api       HTTP response models, validation, pagination, exception mapping
├── common/audit     append-only audit recording and scoped audit queries
├── common/command   transactional command execution, receipts, conflicts, and idempotency
├── common/outbox    pending events stored within the command transaction
├── common/security  authentication, authorization, correlation IDs, and payload limits
├── identity/        accounts, effective roles, enrollment, bindings, access lifecycle
├── approval/        typed profile rule snapshots, activation, route evaluator/preview
├── organization/    employees, assignments, contact profiles, proposals, review history
└── infrastructure/  migrations, runtime database checks, and runtime configuration
```

The package layout follows the technical responsibility of each component. Business modules organize HTTP contracts under `api`, command execution and services under `application`, and models, repositories, and typed exceptions under `domain`. Dedicated mappers, policies, validators, and trail writers are present where needed. These modules reuse the common command, API, audit, outbox, and security infrastructure.

## Code conventions

Use Java records for immutable API and value data where appropriate, constructor or method injection for dependencies, and explicit validation at input boundaries. Keep persistence access behind repository or service classes and keep mapping logic out of controllers. Use `Instant` for timestamps and `BigDecimal` for exact decimal values.

HTTP errors are mapped centrally through `GlobalExceptionHandler`; controllers should raise the relevant typed exception instead of constructing ad-hoc error JSON. Security decisions belong in the access-policy and identity services and the Spring Security configuration. Audit records are written through the audit writer and are append-only at the database layer.

Tests mirror the production package structure. Unit tests cover deterministic domain and infrastructure behavior, HTTP tests verify status codes and response contracts, and PostgreSQL-tagged tests verify migrations and database constraints against a real PostgreSQL instance.

## Runtime services

For local development, Compose provides:

- PostgreSQL on `127.0.0.1:54329`.
- An SQS-compatible ElasticMQ endpoint on `127.0.0.1:4566`.
- Keycloak on `127.0.0.1:8081`.

The application uses the least-privileged `peoplecore_runtime` PostgreSQL role at runtime. The owner role is reserved for migrations and provisioning. Set `PEOPLECORE_DB_PASSWORD`, `PEOPLECORE_RUNTIME_DB_PASSWORD`, `KEYCLOAK_ADMIN_USERNAME`, and `KEYCLOAK_ADMIN_PASSWORD` in the local environment before starting Compose. Runtime startup checks reject privileged database roles and unsafe audit permissions.

## API contract

Successful responses use the typed payload represented by the endpoint. Collection responses use a `data` value together with `metadata`; `PageMetadata` contains `page`, `size`, `totalElements`, and `totalPages`. Success responses do not add a generic success flag or message.

Failure responses use this shape:

```json
{
  "key": "VALIDATION_ERROR",
  "message": "Request validation failed.",
  "timestamp": "2026-10-08T10:15:30Z",
  "details": [
    { "field": "name", "message": "must not be blank" }
  ]
}
```

`details` is omitted when there are no field-level validation errors. Authentication, authorization, validation, not-found, pagination, payload-size, and unexpected failures are mapped to the corresponding HTTP status by the central exception and security handlers. Every request receives a correlation ID, and oversized or excessively nested JSON is rejected at the request boundary.

Implemented endpoints include `/api/v1/employees`, `/api/v1/me/access`, and `/api/v1/enrollments`. Employee operations cover creation, number changes, employment start/end dates, assignment creation, and assignment revision. Employee detail includes the manager public UUID and assignment ancestry. Assignment intervals are start-inclusive and end-exclusive, normalized to microsecond precision, and checked for overlap. Revisions preserve the original row and any retained earlier interval. Employment dates do not set the account access cutoff.

Enrollment supports invitation issuance/reissue, activation, offboarding, rebind, rehire, and explicit role restoration. Invitations are bound to a verified issuer/subject, store a secret hash, and enforce expiry, revocation, one-time consumption, and bounded activation attempts. The plaintext secret appears only in the initial issuance response; replay returns metadata without the secret. Rehire does not automatically restore access roles.

Business endpoints require a verified JWT identity. Account status, access cutoff, active database role assignments, and current organization scope determine access; token role claims do not grant HRIS roles. Own-employee reads require a current binding and an effective role. Invitation issue/reissue and identity rebind require both applicable HR scope and an effective organization-wide `SYSTEM_ADMIN` grant. The shared HTTP chain retains Basic authentication, but Basic credentials do not satisfy these business identity checks.

Sprint 4 attendance and annual-leave APIs are implemented under `/api/v1/attendance`, `/api/v1/leave`, `/api/v1/employees/{id}/work-context`, and `/api/v1/approval/{attendance,leave}-rule-sets`. They cover schedule/calendar revision catalogs and assignments, clock actions and correction history, annual entitlement/ledger, leave requests and whole cancellation. Mutations require the shared idempotent command contract; owner, current reviewer and scoped-HR access is rechecked on reads, commands and replay. The [Sprint 4 technical design](docs/SPRINT_4_TECHNICAL_DESIGN_2026-10-15.md) documents the routes, payloads, lock order and boundary rules. Local implementation evidence and the remaining remote-CI gate are in the [Sprint 4 audit closure](docs/SPRINT_4_AUDIT_REMEDIATION_CLOSURE_2026-10-18.md).

Mutations require `Idempotency-Key`. Command receipts, before/after audit details where applicable, and outbox records commit atomically with domain changes. Replay rechecks current authorization, and changed payloads conflict. Outbox records are durable pending events; persisting one does not mean an external provider has delivered a notification.

Contact-profile self-service uses `/api/v1/employees/me/profile-proposals` and `/api/v1/profile-proposals/{id}`. Employees propose phone, email, address, or complete emergency-contact changes; active values change only after an assigned eligible HR reviewer approves. Returned proposals can be edited before resubmission; editing preserves `RETURNED`, and resubmission captures fresh routing context. Explicit null values and field removal are rejected. Touched-group counters detect intervening changes, including A→B→A; unrelated corrections survive approval. One open proposal per employee is enforced in PostgreSQL.

HR publishes designated-reviewer rule snapshots through `/api/v1/approval/profile-rule-sets`. Changing the single global activation pointer additionally requires organization-wide HR authority, including receipt replay. Scoped publication does not confer global activation authority. No match/tie or ineligible reviewer leaves a visible blocked request; repair uses explicit retry/reassignment. Owner/current scoped HR alone may read sensitive proposal history.

Proposal detail includes `availableActions` derived from current authority and state; every command rechecks authorization. History exposes sealed revisions, decisions, route evaluations, and reviewer-assignment history. Submitted revisions pin the effective organization-assignment UUID and predecessor UUID alongside the evaluated unit and job level. `organizationContextStatus` distinguishes `RESOLVED`, `NOT_EVALUATED`, and legacy `UNKNOWN` context without inventing historical references.

For `/api/v1/profile-proposals/{id}/history`, `page` selects revisions; `decisionPage`, `evaluationPage`, and `assignmentPage` independently select the other history collections. All pages are zero-based and share `size` (default 20, maximum 100). Metadata reports each collection total, allowing clients to retrieve history beyond the first page.

Detailed behavior and HTTP examples are in [IMPLEMENTATION.md](IMPLEMENTATION.md); module and transaction boundaries are in [architecture.md](architecture.md).

## Running and verifying

Use Java 25. Supply `PEOPLECORE_DB_PASSWORD`, `PEOPLECORE_RUNTIME_DB_PASSWORD`, `KEYCLOAK_ADMIN_USERNAME`, and `KEYCLOAK_ADMIN_PASSWORD` in the shell environment. Start PostgreSQL before migrating:

```bash
docker compose up -d postgres
export PEOPLECORE_MIGRATION_DB_URL=jdbc:postgresql://localhost:54329/peoplecore
export PEOPLECORE_MIGRATION_DB_USERNAME=peoplecore
export PEOPLECORE_MIGRATION_DB_PASSWORD="$PEOPLECORE_DB_PASSWORD"
./gradlew migrate
docker compose --profile provision run --rm postgres-runtime-provision
```

The provisioning script grants foundation and contact-profile approval permissions, including append-only history and audit access. Using the schema owner, also grant access to the implemented identity and organization tables before running their endpoints:

```sql
GRANT SELECT, INSERT, UPDATE, DELETE ON
  role_assignments, auth_generation, employee_assignments, enrollment_invitations,
  account_bindings, activation_attempts, activation_budget_slots, activation_admission_lock
TO peoplecore_runtime;
```

Keep the existing audit-table permission limited to `SELECT, INSERT`. Provision initial accounts and role assignments through controlled owner-side administration; the API has no administrator bootstrap endpoint.

Enable JWT validation with your identity provider configuration and define the accepted organization units:

```bash
export PEOPLECORE_SECURITY_JWT_ISSUER_URI="https://identity.example.com/realms/peoplecore"
export PEOPLECORE_SECURITY_JWT_AUDIENCE="peoplecore"
export PEOPLECORE_SECURITY_JWT_JWKS_URI="https://identity.example.com/realms/peoplecore/protocol/openid-connect/certs"
export PEOPLECORE_ORGANIZATION_ALLOWED_UNITS="ENG,FIN"
./gradlew bootRun --args="--spring.profiles.active=local"
```

Replace the example issuer, audience, and JWKS URL with values matching your verified tokens and provisioned account identities. `peoplecore.runtime.role` defaults to `api`; setting it to `worker` starts without an HTTP server.

```bash
./gradlew test
./gradlew postgresIntegrationTest
./gradlew check
./gradlew bootJar
```

PostgreSQL tests require a separate `peoplecore_tests` database, defaulting to `jdbc:postgresql://localhost:54339/peoplecore_tests`, with `peoplecore_test` credentials. Override these with `PEOPLECORE_TEST_DB_URL`, `PEOPLECORE_TEST_DB_USERNAME`, and `PEOPLECORE_TEST_DB_PASSWORD`. The test administrator needs permission to create isolated schemas and temporary runtime roles; fixtures remove them afterward. Never point these tests at the application database.

`check` requires ordinary and PostgreSQL execution data and enforces at least 85% line and branch coverage. Migrations reject invalid existing assignment intervals rather than rewriting history.

### Work calendars and context

HR publishes reusable immutable configuration with `POST /api/v1/attendance/schedule-revisions` and `/calendar-revisions` (Idempotency-Key required). Current HR operators may retrieve published catalogs with GET on those paths (`page=0&size=20`, maximum size 100), and full configuration with GET `/{id}`. Employee identities do not grant catalog access.

Use `POST /api/v1/attendance/publish-preview` before assigning a revision. Assignment commands under `/api/v1/employees/{employeeId}/schedule-assignments` and `/calendar-assignments` require `expectedFingerprint`; stale context returns 409 and requires a new preview. Publish itself has no fingerprint because applicability changes only with assignments. Backdated assignments require a reason. `GET /api/v1/employees/{employeeId}/work-context?from=2026-10-01&to=2026-11-01` returns the half-open range, at most 366 days, without pinning evidence. Missing context appears as a per-date blockedReason. Owner reads require a current binding and effective role; HR reads require current target coverage.

Apply migrations using owner credentials before rerunning `local/postgres/provision-runtime.sql` for runtime grants. V14 supplies context storage and V15 seals assignment provenance. See [architecture](architecture.md#attendance-and-annual-leave) for admission ordering, canonical evidence and delivery boundaries.

Security artifacts include `*-all-severities.json`, the HIGH/CRITICAL gate report, and `*-review.json` with remediation ownership/decision status. Review lower-severity results even when the gate passes.

### Sprint 4 delivery verification

Local acceptance is complete; formal delivery still requires a passing remote run for the final commit. The maintainer must record its commit SHA and run URL, confirm that branch protection requires the `check` job in the `Backend checks` workflow, and retain the uploaded test, coverage and security reports. The workflow rebuilds service images, runs the full Gradle gate and scans dependencies/images with Trivy; allow the scanner to refresh its vulnerability database. A passing run for an earlier commit does not close this gate.

Before endpoint enablement, the deployment owner must verify that every serving replica participates in the shared `profile-admission` protocol, apply V14–V18 with migration-owner credentials, rerun runtime provisioning, and confirm approved schedule, calendar, entitlement and reviewer configuration. Record the deployed revision and replica verification with the delivery evidence.

The October 31 local security refresh rebuilt the backend and service images using Trivy 0.75.0 with vulnerability and Java databases updated that day. New image findings were fixed with updated Alpine packages and checksum-pinned Guava, MariaDB and OpenTelemetry API/context JARs in the local Keycloak helper. Its clean final stage contains the patched distribution; Quarkus compatibility aliases point to the verified new JAR bytes. The full security gate passed with zero findings across five targets, and PostgreSQL readiness/query, SQS send/receive/delete and Keycloak PKCE smoke checks passed. Before/after reports are in `build/reports/security/2026-10-31` and `build/reports/security/2026-10-31-after`; the latter includes `followup-summary.json`. These checks do not replace the remote gate for the final commit.

The user selected a dummy local workload for the availability follow-up. On October 31 an isolated PostgreSQL fixture with one employee, one five-weekday schedule, an empty calendar and no pins completed five warmups per operation, then 40 baseline reads and 20 baseline writes. Four concurrent readers completed 80 requests for 366-day context alongside 20 preview/revise writes. Read p95 was 36.07 ms baseline and 195.05 ms mixed; write p95 was 79.75 ms and 384.36 ms. There were zero errors and no new pins. Results, workload and reproduction harness are in `build/reports/performance/2026-10-31/`.

This short single-run baseline measures direct service calls with a four-connection pool during concurrent image builds. It excludes HTTP/JWT/network, authority mutations and multiple replicas; sampled waiter counts are not measured lock-wait durations. Deployment owners must still agree on production workload and latency/error budgets and verify them before rollout. Route measured implementation failures to code review and security failures to security review.
