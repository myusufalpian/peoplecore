# Implementation Overview

Peoplecore implements employee records, effective-dated organization assignments, identity enrollment, and reviewed contact-profile changes on a Spring Boot backend. PostgreSQL stores domain state, command receipts, append-only audit records, and pending outbox events. Public API references use UUIDs.

## Delivered capabilities

- Employee creation, employee-number changes, and employment start/end date updates.
- Organization assignments with a unit, job level, optional manager public UUID, and a start-inclusive/end-exclusive interval.
- Assignment revisions that preserve the original row, retain an earlier interval when necessary, and expose supersession ancestry in history.
- Invitation issuance/reissue, identity activation, offboarding, reassociation, rehire, and explicit role restoration.
- JWT signature, issuer, expiration, and audience validation, followed by live account, role, binding, and organization-scope checks.
- Typed success/error responses, centralized exception mapping, correlation IDs, bounded JSON input, and CSRF protection for applicable requests.
- Canonical request hashing, idempotency receipts, PostgreSQL advisory locks, and atomic domain/receipt/audit/outbox writes.
- Explicit Flyway migrations, schema validation, runtime database-role checks, dependency inventories, and vulnerability-gate tooling.
- Bounded profile-rule publication, organization-wide global activation, and shared preview/submission evaluation with a designated HR reviewer.
- Employee-owned proposals, separate active contact values, return/edit/resubmit, reasoned HR decisions/reassignment, and explicit blocked-route recovery.
- Database-sealed content, touched-field conflict counters, scoped queues/history, current receipt authorization, and atomic approval/application.

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

## Contact-profile API

All paths below are under `/api/v1`. Mutations require the same verified JWT and `Idempotency-Key` headers as the employee APIs.

| Method | Path | Request / purpose |
| --- | --- | --- |
| POST | `/employees/me/profile-proposals` | `changes`; create one open owner proposal, 201 |
| PATCH | `/profile-proposals/{id}` | `expectedVersion`, `changes`; edit DRAFT or RETURNED without applying active values |
| POST | `/profile-proposals/{id}/submit` | `expectedVersion`; submit DRAFT |
| POST | `/profile-proposals/{id}/resubmit` | `expectedVersion`, `changes`; create a freshly evaluated revision from RETURNED |
| POST | `/profile-proposals/{id}/withdraw` | `expectedVersion`; owner pre-approval withdrawal |
| POST | `/profile-proposals/{id}/{approve,reject,return}` | `expectedVersion`, `revisionId`, `assignmentId`, `reason?`; assigned eligible HR only, reason mandatory for reject/return |
| POST | `/profile-proposals/{id}/reassign` | `expectedVersion`, `assignmentId`, `replacementAccountId`, `reason`; scoped HR recovery |
| POST | `/profile-proposals/{id}/retry-route` | `expectedVersion`; scoped HR reevaluates blocked routing against pinned original inputs |
| GET | `/profile-proposals/{id}` | Scoped proposal detail and reviewed values |
| GET | `/profile-proposals/{id}/history` | Bounded revision/decision/evaluation/reassignment history |
| GET | `/profile-proposals?page=0&size=20` | Current HR scope-filtered queue |
| GET | `/employees/me/contact-profile` | Owner active contact values and four group-version counters |
| PATCH | `/employees/{id}/contact-profile` | Scoped HR correction; `changes`, `reason`, `expectedPhoneVersion`, `expectedEmailVersion`, `expectedAddressVersion`, `expectedEmergencyVersion` |
| POST | `/approval/profile-rule-sets` | `rules`; scoped snapshot publication, 201 |
| POST | `/approval/profile-rule-sets/{id}/activate` | No JSON body; organization-wide HR required for global pointer activation |
| POST | `/approval/profile-routes/preview` | `orgUnit`, `jobLevel`, optional `employeeId`; HR-only advisory route |

Proposal mutation results contain `proposalId`, `employeeId`, `state`, `proposalVersion`, `revisionId`, `revisionNumber`, `assignmentId`, and `blockedReason`. Read the returned version and current revision/assignment before forming the next command; do not infer them from a previous screen. A valid blocked submission still returns 200 and PENDING. Approval alone changes active contact values; return, reject and withdrawal do not.

Detail includes `availableActions`, calculated for the current caller and state: owner actions `EDIT`, `SUBMIT`, `RESUBMIT`, `WITHDRAW`; eligible assigned-reviewer actions `APPROVE`, `REJECT`, `RETURN`; scoped HR recovery `REASSIGN`, `RETRY_ROUTING`. Only currently applicable actions appear and terminal proposals have none. The list helps clients render controls; each command independently rechecks access/version/state and is never authorized by a stale action list.

History accepts `page`, `size`, `decisionPage`, `evaluationPage`, and `assignmentPage`; defaults are page 0/size 20. Size is 1–100 and all page numbers must be nonnegative. The revision page is independent of the other three proposal-wide pages. Metadata includes all four page numbers and `totalRevisions`, `totalDecisions`, `totalEvaluations`, `totalAssignments`. Continue each collection until its total is reached: `GET /profile-proposals/{id}/history?page=0&size=100&evaluationPage=1` returns older evaluations even when there is only one revision. Decisions/evaluations are latest-first with stable ID tie-breaking; reviewer assignments are chronological. Child records include public revision IDs, decisions also assignment IDs, and assignment history includes reviewer/status/reason/actor/creation/closure evidence.

Each revision exposes `orgAssignmentId`, `orgAssignmentPredecessorId`, and `organizationContextStatus`: `RESOLVED` identifies the immutable effective organization-assignment row selected for submission, `NOT_EVALUATED` describes unsent content, and `UNKNOWN` explicitly represents legacy evaluated context that cannot be reconstructed. Supersession does not rewrite prior snapshots. Do not confuse these IDs with approval reviewer assignment IDs.

The payload uses a bounded change list rather than an employee-shaped object. Omitted entries mean unchanged; explicit null/blank values, unknown names, duplicates, and removals are rejected. The four versioned groups are personal phone, personal email, residential address, and emergency contact. Emergency contact requires all three entries (`emergencyContactName`, `emergencyContactRelationship`, `emergencyContactPhone`) together. This does not alter account email or identity binding.

Create a phone proposal:

```bash
curl --fail-with-body -X POST http://localhost:8080/api/v1/employees/me/profile-proposals \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: contact-phone-2026-10-14' \
  --data '{"changes":[{"fieldName":"personalPhone","proposedValue":"+62 812 3456 7890"}]}'
```

Then submit with `POST /profile-proposals/{proposalId}/submit` and `{"expectedVersion":0}` only if the creation response actually returned version 0. Use a distinct idempotency key for the submit command. After a return, PATCH saves edited content while retaining RETURNED; resubmit is a separate command carrying the confirmed full change list and current version.

For the following commands, set `PROPOSAL_ID` and `PROPOSAL_VERSION` from the latest successful response. Use the employee token for submission:

```bash
curl --fail-with-body -X POST "http://localhost:8080/api/v1/profile-proposals/$PROPOSAL_ID/submit" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: contact-phone-submit-2026-10-14' \
  --data "{\"expectedVersion\":$PROPOSAL_VERSION}"
```

An eligible assigned reviewer first reads proposal detail using their own token. The current identifiers are under `data.proposal`; set `PROPOSAL_VERSION`, `REVISION_ID`, and `ASSIGNMENT_ID` from that object before deciding. A blocked proposal has no active reviewer assignment and needs scoped HR recovery rather than approval.

```bash
curl --fail-with-body "http://localhost:8080/api/v1/profile-proposals/$PROPOSAL_ID" \
  -H "Authorization: Bearer $REVIEWER_ACCESS_TOKEN"

curl --fail-with-body -X POST "http://localhost:8080/api/v1/profile-proposals/$PROPOSAL_ID/approve" \
  -H "Authorization: Bearer $REVIEWER_ACCESS_TOKEN" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: contact-phone-approve-2026-10-14' \
  --data "{\"expectedVersion\":$PROPOSAL_VERSION,\"revisionId\":\"$REVISION_ID\",\"assignmentId\":\"$ASSIGNMENT_ID\"}"
```

For rejection or return, use the corresponding path and include a nonblank `reason` of at most 1,000 characters. After RETURNED, the owner edits with PATCH using `expectedVersion` and `changes`, then resubmits with a new key and the returned version. Both editing and resubmitting accept the complete desired proposal change list, not a partial merge with the previous revision.

Read successive history pages with the token of a currently authorized owner or scoped HR account. Advance only the collection whose next page is needed; revision `page` is oldest-first, while decisions and evaluations are latest-first. Response arrays are under `data`, and counts/page positions are under `metadata`.

```bash
curl --fail-with-body \
  "http://localhost:8080/api/v1/profile-proposals/$PROPOSAL_ID/history?page=0&size=100&decisionPage=0&evaluationPage=1&assignmentPage=0" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

For an HR contact correction, include the four expected counters using the JSON names in the API table. The service checks counters for the groups present in `changes`; untouched groups retain their values and counters. The owner contact-profile read returns `phoneVersion`, `emailVersion`, `addressVersion`, and `emergencyVersion`. There is no separate HR contact-profile GET route; do not construct one from the correction path.

Rule publication uses `{"rules":[{"ruleId":"eng-contact","priority":10,"orgUnit":"ENG","jobLevel":null,"reviewerAccountId":"<designated-account-uuid>"}]}`. The example UUID placeholder must be replaced with a real eligible account. Publishing a scoped snapshot does not grant activation rights: changing the global pointer requires organization-wide HR. Preview never reserves a reviewer; submission records its actual result. Retry and reassignment append evidence and cannot overwrite the original submission route/content.

Operation or owner-binding denial is 403. Proposal reads and HR decision/reassignment/retry commands return 404 for missing or out-of-scope resources before disclosing state/version. Stale versions/assignments, one-open conflicts, inactive reviewers, and same-field conflicts return 409. A touched-group conflict requires return, owner confirmation and fresh resubmission; there is no force-approval path. SYSTEM_ADMIN, manager and Finance roles alone grant no profile review/disclosure authority.

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

Local verification on October 14, 2026 ran `check bootJar` successfully after all acceptance corrections. Unit/JPA/HTTP: 260 cases, 259 passed and one pre-existing optional skip. PostgreSQL: 89 cases, all passed, including 15 workflow concurrency/rollback cases. Merged coverage is 3,661/3,804 lines (96.24%) and 1,118/1,253 branches (89.23%); both 85% gates pass. The application JAR built successfully.

Local verification and remote required-check enforcement are separate evidence. A remote CI run for the delivered revision and repository protection settings remain unverified. Frontend delivery, notification delivery and production readiness remain outside the implemented backend scope.
