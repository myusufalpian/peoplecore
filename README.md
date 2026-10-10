# Peoplecore Backend

Peoplecore is a Spring Boot backend that provides employee records, effective-dated organization assignments, and identity enrollment through a transactional HTTP API. This repository contains the API and worker runtime. Client applications are separate consumers of the HTTP API.

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
├── organization/    employees, employment dates, assignments, scoped history
└── infrastructure/  migrations, runtime database checks, and runtime configuration
```

The package layout follows the technical responsibility of each component. Identity and organization contain `api/controller`, `api/dto`, `api/mapper`, `application/command`, `application/service`, `application/policy`, `application/mapper`, `application/trail`, and `domain/model`, `domain/repository`, `domain/exception` packages. These modules reuse the common command, API, audit, outbox, and security infrastructure.

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

Mutations require `Idempotency-Key`. Command receipts, before/after audit details where applicable, and outbox records commit atomically with domain changes. Replay rechecks current authorization, and changed payloads conflict. Outbox records are durable pending events; persisting one does not mean an external provider has delivered a notification.

Detailed behavior and HTTP examples are in [implementation.md](implementation.md); module and transaction boundaries are in [architecture.md](architecture.md).

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

The provisioning script grants foundation-table permissions. Using the schema owner, also grant access to the implemented identity and organization tables before running their endpoints:

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
