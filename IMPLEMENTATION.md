# Implementation Overview

This repository contains the backend foundation for a secure, auditable HR platform. The implementation currently focuses on the service runtime, persistence, security boundaries, command processing, and local infrastructure needed to run and verify that backend.

## Delivered capabilities

- Spring Boot web runtime with typed API response records and centralized exception handling.
- Consistent success responses shaped as `{data, metadata}`. Success responses do not include a status flag or a generic message.
- Consistent failure responses shaped as `{key, message, timestamp}` with optional validation `details`.
- HTTP status handling for authentication, authorization, validation, malformed input, conflicts, unsupported media, oversized payloads, rate limits, and service failures.
- Request correlation IDs and bounded request-body processing. JSON parsing applies depth, string, field-name, number, token, duplicate-key, and body-size limits.
- Spring Security baseline with authenticated and anonymous request paths, CSRF protection, explicit authorization rules, and a default-deny access policy.
- Command execution with idempotency receipts, canonical request hashing, PostgreSQL advisory-lock serialization, conflict detection, and bounded receipt retention.
- Audit event persistence and query support, plus an outbox model for durable event publication.
- PostgreSQL persistence managed by explicit Flyway migrations, startup schema validation, and a runtime database-role guard.
- Least-privilege local database roles and custom local images for PostgreSQL, SQS-compatible messaging, and Keycloak.
- Security and supply-chain verification for backend dependencies and local service images, including SBOM generation and vulnerability-gate checks.

## Runtime and verification

The local composition provides PostgreSQL on port `54329`, an SQS-compatible service on `4566`, and Keycloak on `8081`. The application can run against these services using the repository's Gradle and Docker Compose configuration.

The verified build path is:

```bash
./gradlew check bootJar --no-daemon --console=plain
```

The latest verification completed with 67 tests, 66 passing and one optional R2 test skipped. PostgreSQL integration tests passed. Merged JaCoCo coverage was 96.74% line and 89.60% branch. Negative coverage checks and the security gate also passed; the latter reported no High or Critical findings for the backend and scanned local service images.

## API contract

Successful responses return only the payload and its contextual metadata:

```json
{
  "data": {},
  "metadata": {}
}
```

Failure responses return a stable machine-readable key, a user-facing message, and an event timestamp:

```json
{
  "key": "VALIDATION_ERROR",
  "message": "Request validation failed",
  "timestamp": "2026-10-08T10:15:30Z",
  "details": {}
}
```

The implementation uses `200`, `201`, `202`, and `204` for successful operations, with `400`, `401`, `403`, `404`, `409`, `413`, `415`, `422`, `429`, `500`, and `503` selected according to the failure scenario. Authentication failures include the appropriate `WWW-Authenticate` challenge, and temporary service unavailability includes `Retry-After` where applicable.

There is no generated OpenAPI/Swagger catalog in this repository yet. The response records, centralized exception handler, and HTTP tests are the current executable definition of the API contract.

## Operational boundary

The repository is a backend service boundary. A separate frontend application can consume the stable API contract later. The local authentication setup is a compatibility baseline; production identity-provider integration and deployment-specific policy remain environment concerns.
