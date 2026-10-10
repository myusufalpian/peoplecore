# Implementation Overview

Peoplecore implements employee records, effective-dated organization assignments, and identity enrollment on a Spring Boot backend. PostgreSQL stores domain state, command receipts, append-only audit records, and pending outbox events. The API uses public UUIDs for employee, assignment, and invitation references.

## Delivered capabilities

- Employee creation, employee-number changes, and employment start/end date updates.
- Organization assignments with a unit, job level, optional manager public UUID, and a start-inclusive/end-exclusive interval.
- Assignment revisions that preserve the original row, retain an earlier interval when necessary, and expose supersession ancestry in history.
- Invitation issuance/reissue, identity activation, offboarding, reassociation, rehire, and explicit role restoration.
- JWT signature, issuer, expiration, and audience validation, followed by live account, role, binding, and organization-scope checks.
- Typed success/error responses, centralized exception mapping, correlation IDs, bounded JSON input, and CSRF protection for applicable requests.
- Canonical request hashing, idempotency receipts, PostgreSQL advisory locks, and atomic domain/receipt/audit/outbox writes.
- Explicit Flyway migrations, schema validation, runtime database-role checks, dependency inventories, and vulnerability-gate tooling.

HTTP DTO mappers, application result mappers, and audit/outbox trail mappers keep representation logic separate from mutation services.

## Employee and assignment behavior

Employee-number uniqueness is enforced. Creating an employee requires organization-wide HR authority; updates require HR coverage of the employee's current unit. Employment start is required when setting employment dates, and an end date cannot precede it. Omitting the end date clears it. These business dates are independent of account access cutoff and do not change employee status.

Assignments validate the configured unit allowlist, manager existence, job level, interval order, and overlap with non-superseded assignments. A manager cannot reference the same employee. Timestamp boundaries are truncated to PostgreSQL microsecond precision before validation, overlap checks, persistence, and response mapping; collapsed intervals are rejected.

A revision begins within the selected assignment interval. The original row keeps its interval and receives `supersededAt`. A later revision boundary creates a retained prefix and replacement, both linked to the original public UUID through `supersedesId`. Employee locking serializes writes. Authorization covers the current, original, and replacement units. Audit details retain before/after values and the retained prefix identifier.

Employee detail returns all assignment history, including superseded rows, with manager public UUIDs. History uses one scalar database projection and derives the current unit from that same result before authorizing disclosure. Reads require applicable HR scope or the employee's own active binding with an effective role; unavailable or out-of-scope employee reads return `404`.

## Identity and access behavior

Invitation issue/reissue and rebind require both applicable `HR_ADMIN` scope and an effective organization-wide `SYSTEM_ADMIN` grant. The operator supplies a verified expected issuer/subject and a non-secret identity reference; external identity evidence is verified through controlled operator procedures. The server enforces caller authority and required references.

Invitations expire after seven days. The plaintext secret appears only in the initial issuance response; receipt replay returns invitation metadata with no secret. Stored invitations contain a secret hash, and new receipts, audit records, and outbox payloads exclude the plaintext. Reissue revokes outstanding invitations and preserves the first known revocation time.

Activation requires the matching JWT issuer/subject, a valid secret, an issued and unexpired invitation, and active employment. Binding creation, invitation consumption, and the EMPLOYEE grant commit together. Database constraints enforce one active binding per employee, account, and issuer/subject.

Activation admission reserves durable capacity in a separate transaction: 20 attempts per principal and five per principal/invitation pair in 15 minutes, plus 30 non-recipient attempts per known invitation. The expected recipient is exempt from the shared invitation limit. Failed commands and receipt replays consume capacity; supported rejection audits survive command rollback. Throttling returns `429`.

Offboarding immediately marks employment inactive and revokes outstanding invitations. A bound account remains usable until its configured `accessEndsAt`, then fails live access checks at that exact instant. Optional local cutoff time/timezone values are metadata; the request must supply the resolved cutoff instant. Rehire restores employee status only. Explicit access restoration requires active employment and a binding; privileged role restoration additionally requires organization-wide HR and system authority.

Rebind revokes the old binding, ends the old account's roles, and cuts off its access. An existing earlier cutoff is carried to the replacement account. Token role claims do not grant application roles. Basic authentication remains in the shared HTTP chain but does not establish the verified identity required by these business endpoints.

## API contract

Every endpoint below requires a verified JWT. Every mutation also requires `Idempotency-Key`, nonblank, at most 128 characters, and without a NUL character. Required request fields are shown without a question mark; `?` indicates an optional field.

| Method | Path | Request fields | Success |
| --- | --- | --- | --- |
| GET | `/api/v1/me/access` | — | `200`, current account, roles, employee binding, cutoff, authorization generation |
| POST | `/api/v1/employees` | `employeeNumber` | `201`, employee |
| GET | `/api/v1/employees/{id}` | — | `200`, employee and assignment history |
| PUT | `/api/v1/employees/{id}` | `employeeNumber` | `200`, employee |
| PUT | `/api/v1/employees/{id}/employment` | `startDate`, `endDate?`, `reason` | `200`, employee |
| POST | `/api/v1/employees/{id}/assignments` | `orgUnit`, `jobLevel`, `validFrom`, `validTo?`, `managerId?` | `201`, assignment |
| POST | `/api/v1/employees/{id}/assignments/{assignmentId}/revisions` | Assignment fields plus `reason` | `201`, replacement assignment |
| POST | `/api/v1/enrollments/invitations` | `employeeId`, `intendedIdentityRef`, `expectedIssuer`, `expectedSubject` | `201`, invitation |
| POST | `/api/v1/enrollments/employees/{id}/invitations` | `intendedIdentityRef`, `expectedIssuer`, `expectedSubject` | `201`, replacement invitation |
| POST | `/api/v1/enrollments/activations` | `invitationId`, `secret` | `200`, binding |
| POST | `/api/v1/enrollments/employees/{id}/offboard` | `accessEndsAt`, `cutoffTime?`, `cutoffTimezone?`, `reason?` | `200`, `OFFBOARDED` |
| POST | `/api/v1/enrollments/employees/{id}/rebind` | `newIssuer`, `newSubject`, `reason` | `200`, `REBOUND` |
| POST | `/api/v1/enrollments/employees/{id}/rehire` | `reason?` | `200`, `REHIRED` |
| POST | `/api/v1/enrollments/employees/{id}/access-restore` | `roles`, `scope?`, `reason?` | `200`, `ACCESS_RESTORED` |

Use a token issued for the configured issuer and audience to inspect live access:

```bash
curl --fail-with-body http://localhost:8080/api/v1/me/access \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

Example JSON for `POST /api/v1/employees/{id}/assignments`, sent with `Content-Type: application/json`, `Authorization: Bearer ...`, and a new `Idempotency-Key`:

```json
{
  "orgUnit": "ENG",
  "jobLevel": "L2",
  "managerId": null,
  "validFrom": "2026-10-10T00:00:00Z",
  "validTo": null
}
```

The unit must be configured and the caller must hold applicable HR authority. A successful assignment response uses this shape:

```json
{
  "data": {
    "id": "d3352b7b-c911-4bb4-b373-bfa91f40ab12",
    "orgUnit": "ENG",
    "jobLevel": "L2",
    "validFrom": "2026-10-10T00:00:00Z",
    "validTo": null,
    "managerId": null,
    "supersededAt": null,
    "supersedesId": null
  },
  "metadata": {}
}
```

Employee responses contain `id`, `employeeNumber`, `status`, `createdAt`, `employmentStartDate`, and `employmentEndDate`. Binding responses contain `employeeId`, `issuer`, `subject`, and `createdAt`. Invitation responses contain `id`, `employeeId`, `intendedIdentityRef`, `status`, `deliveryStatus`, `expiresAt`, and `secret`; the secret is unavailable on replay.

Validation failures use field/message pairs:

```json
{
  "key": "VALIDATION_ERROR",
  "message": "Request validation failed.",
  "timestamp": "2026-10-10T10:15:30Z",
  "details": [
    { "field": "employeeNumber", "message": "must not be blank" }
  ]
}
```

Errors omit `details` when no field-level details exist. Security handlers provide the appropriate authentication challenge. Malformed input, access denial, missing resources, conflicts, oversized bodies, throttling, and service failures use typed HTTP errors rather than a success flag.

## Command and operational boundaries

Command identity is scoped by actor, command type, and idempotency key. Equivalent canonical payloads replay a successful result after current authorization is rechecked; changing the payload under the same identity returns `409`. Domain mutations, receipts, audit records, and outbox records share a transaction. Activation admission and rejection audit use the separate transactions described above. Receipts carry a seven-day retention deadline.

Outbox persistence records pending events; no implemented dispatcher publishes them or delivers invitations to an external provider. Initial accounts and privileged roles require controlled database provisioning. Runtime credentials must have the identity/organization table grants as well as foundation permissions, while audit permissions remain append-only.

Upgrade migrations remove legacy plaintext invitation secrets from command receipts and fail closed for unusable replay payloads. Potential exposure revokes outstanding invitations. SQL cleanup does not erase historical backups or WAL: restricted retention and isolated, migrated restores remain operator responsibilities. Interval-order migration rejects invalid existing assignments rather than rewriting their history.

Local PostgreSQL, SQS-compatible messaging, and Keycloak use ports `54329`, `4566`, and `8081`. Setup, configuration, provisioning, and isolated PostgreSQL test requirements are in [README.md](README.md). Module ownership and transaction design are in [architecture.md](architecture.md).

## Runtime and verification

The verification path is:

```bash
./gradlew check bootJar --no-daemon --console=plain
```

The latest recorded verification contains 205 tests: 204 passed and one optional R2 test skipped. All 61 PostgreSQL integration tests passed. Merged JaCoCo coverage was 96.54% line and 86.18% branch, exceeding the configured 85% gates. These are recorded implementation results; the documentation update itself changes no runtime behavior.
