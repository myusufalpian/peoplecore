# Peoplecore Backend

Peoplecore is a Spring Boot backend that provides the foundation for a secure, transactional people and business domain API. This repository contains the API and worker runtime. Client applications are separate consumers of the HTTP API.

## Technology stack

- Java 25 with the Gradle wrapper.
- Spring Boot 4.1.1 and Spring MVC.
- Spring Data JPA with PostgreSQL 18.
- Flyway for versioned database migrations.
- Spring Security for HTTP Basic authentication in the local foundation and OAuth2 client/resource-server support for deployment integrations.
- Jackson 3 for JSON serialization and Bean Validation for request validation.
- AWS SDK v2 S3 client for object storage integration.
- JUnit 5, Spring Boot test slices, Testcontainers-style PostgreSQL fixtures, and JaCoCo for verification.
- Docker Compose for local PostgreSQL, SQS-compatible ElasticMQ, and Keycloak services.

The application is started from `id.mydev.peoplecore.PeoplecoreApplication`. Database migrations are intentionally run explicitly through the `migrate` Gradle task; normal application startup does not mutate the database schema.

## Repository structure

```text
src/main/java/id/mydev/peoplecore/
├── common/api       HTTP response models, validation, pagination, exception mapping
├── common/audit     append-only audit recording and scoped audit queries
├── common/command   transactional command execution, receipts, conflicts, and idempotency
├── common/outbox    durable events published from the transaction boundary
├── common/security  authentication, authorization, correlation IDs, and payload limits
└── infrastructure/  migrations, runtime database checks, and profile configuration
```

The package layout follows the technical responsibility of each component. Domain-specific features should keep their application, persistence, mapping, and HTTP concerns close to the feature while reusing the common command, API, audit, outbox, and security infrastructure.

## Code conventions

Use Java records for immutable API and value data where appropriate, constructor or method injection for dependencies, and explicit validation at input boundaries. Keep persistence access behind repository or service classes and keep mapping logic out of controllers. Use `Instant` for timestamps and `BigDecimal` for exact decimal values.

HTTP errors are mapped centrally through `GlobalExceptionHandler`; controllers should raise the relevant typed exception instead of constructing ad-hoc error JSON. Security decisions belong in `CurrentAccessPolicy` and the Spring Security configuration. Audit records are written through the audit writer and are append-only at the database layer.

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

The current codebase provides the API response and security contract foundation; feature endpoints are added as their domain use cases are implemented. There is no generated OpenAPI/Swagger endpoint catalog in the current runtime, so the Java response records, exception handler, and HTTP tests are the authoritative contract until endpoint-specific documentation is introduced.

## Running and verifying

```bash
./gradlew test
./gradlew check
./gradlew bootJar
./gradlew migrate
```

Start local dependencies with `docker compose --profile provision up` after supplying the required environment variables. Use the PostgreSQL integration test task when a test database is available:

```bash
./gradlew postgresIntegrationTest
```

The `check` task requires both ordinary and PostgreSQL test execution data and enforces at least 85% line and branch coverage.

