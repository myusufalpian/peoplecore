-- V11__profile_proposals.sql
-- Organization-owned reviewed profile changes.
-- typed active contact profile with four independently
-- versioned groups, proposal/revision/change/assignment/decision/evaluation
-- rows, one-open-proposal partial unique index, scoped queue indexes and
-- immutable submitted/history protection.

CREATE TABLE IF NOT EXISTS employee_contact_profile (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_id BIGINT NOT NULL UNIQUE REFERENCES employees (id),
    personal_phone VARCHAR(32),
    personal_email VARCHAR(254),
    residential_address VARCHAR(1000),
    emergency_contact_name VARCHAR(200),
    emergency_contact_relationship VARCHAR(100),
    emergency_contact_phone VARCHAR(32),
    phone_version BIGINT NOT NULL DEFAULT 0,
    email_version BIGINT NOT NULL DEFAULT 0,
    address_version BIGINT NOT NULL DEFAULT 0,
    emergency_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_contact_profile_versions CHECK (
        phone_version >= 0 AND email_version >= 0
        AND address_version >= 0 AND emergency_version >= 0)
);

CREATE TABLE IF NOT EXISTS profile_proposal (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE,
    employee_id BIGINT NOT NULL REFERENCES employees (id),
    state VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    current_revision_id BIGINT,
    current_assignment_id BIGINT,
    blocked_reason VARCHAR(64),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_profile_proposal_state CHECK (
        state IN ('DRAFT', 'PENDING', 'RETURNED', 'APPROVED', 'REJECTED', 'CANCELLED')),
    CONSTRAINT ck_profile_proposal_blocked CHECK (
        blocked_reason IS NULL OR blocked_reason IN (
            'NO_MATCH', 'AMBIGUOUS_RULE', 'NO_DESIGNATED_REVIEWER',
            'REVIEWER_INELIGIBLE', 'SELF_REVIEW_PROHIBITED', 'NO_ACTIVE_CONFIGURATION'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_profile_proposal_open
    ON profile_proposal (employee_id)
    WHERE state IN ('DRAFT', 'PENDING', 'RETURNED');

CREATE INDEX IF NOT EXISTS idx_profile_proposal_employee
    ON profile_proposal (employee_id, state);

CREATE TABLE IF NOT EXISTS profile_revision (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE,
    proposal_id BIGINT NOT NULL REFERENCES profile_proposal (id),
    revision_number INT NOT NULL,
    rule_set_public_id UUID,
    evaluated_at TIMESTAMP WITH TIME ZONE,
    evaluated_org_unit VARCHAR(128),
    evaluated_job_level VARCHAR(64),
    route_reviewer_account_public_id UUID,
    blocked_reason VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_profile_revision_number UNIQUE (proposal_id, revision_number),
    CONSTRAINT ck_profile_revision_blocked CHECK (
        blocked_reason IS NULL OR blocked_reason IN (
            'NO_MATCH', 'AMBIGUOUS_RULE', 'NO_DESIGNATED_REVIEWER',
            'REVIEWER_INELIGIBLE', 'SELF_REVIEW_PROHIBITED', 'NO_ACTIVE_CONFIGURATION'))
);

CREATE TABLE IF NOT EXISTS profile_revision_change (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    revision_id BIGINT NOT NULL REFERENCES profile_revision (id),
    field_name VARCHAR(64) NOT NULL,
    baseline_value VARCHAR(1000),
    proposed_value VARCHAR(1000) NOT NULL,
    baseline_version BIGINT NOT NULL,
    CONSTRAINT uk_profile_revision_change_field UNIQUE (revision_id, field_name),
    CONSTRAINT ck_profile_revision_change_field CHECK (field_name IN (
        'personalPhone', 'personalEmail', 'residentialAddress',
        'emergencyContactName', 'emergencyContactRelationship', 'emergencyContactPhone')),
    CONSTRAINT ck_profile_revision_change_version CHECK (baseline_version >= 0)
);

CREATE TABLE IF NOT EXISTS profile_assignment (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE,
    proposal_id BIGINT NOT NULL REFERENCES profile_proposal (id),
    revision_id BIGINT NOT NULL REFERENCES profile_revision (id),
    assignee_account_public_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    reason VARCHAR(1000),
    actor VARCHAR(128),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    closed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT ck_profile_assignment_status CHECK (
        status IN ('ACTIVE', 'SUPERSEDED', 'CLOSED'))
);

CREATE INDEX IF NOT EXISTS idx_profile_assignment_proposal
    ON profile_assignment (proposal_id, status);

CREATE INDEX IF NOT EXISTS idx_profile_assignment_assignee
    ON profile_assignment (assignee_account_public_id, status);

CREATE TABLE IF NOT EXISTS profile_decision (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    proposal_id BIGINT NOT NULL REFERENCES profile_proposal (id),
    revision_id BIGINT NOT NULL REFERENCES profile_revision (id),
    assignment_id BIGINT REFERENCES profile_assignment (id),
    action VARCHAR(32) NOT NULL,
    actor_account_public_id UUID NOT NULL,
    reason VARCHAR(1000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_profile_decision_action CHECK (
        action IN ('APPROVE', 'REJECT', 'RETURN'))
);

CREATE INDEX IF NOT EXISTS idx_profile_decision_proposal
    ON profile_decision (proposal_id, created_at);

CREATE TABLE IF NOT EXISTS profile_route_evaluation (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    proposal_id BIGINT NOT NULL REFERENCES profile_proposal (id),
    revision_id BIGINT REFERENCES profile_revision (id),
    evaluated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    rule_set_public_id UUID,
    org_unit VARCHAR(128),
    job_level VARCHAR(64),
    result VARCHAR(16) NOT NULL,
    rule_id VARCHAR(64),
    reviewer_account_public_id UUID,
    blocked_reason VARCHAR(64),
    CONSTRAINT ck_profile_route_evaluation_result CHECK (
        result IN ('MATCHED', 'BLOCKED'))
);

CREATE INDEX IF NOT EXISTS idx_profile_route_evaluation_proposal
    ON profile_route_evaluation (proposal_id, evaluated_at);

ALTER TABLE profile_proposal
    ADD CONSTRAINT fk_profile_proposal_revision
    FOREIGN KEY (current_revision_id) REFERENCES profile_revision (id);

ALTER TABLE profile_proposal
    ADD CONSTRAINT fk_profile_proposal_assignment
    FOREIGN KEY (current_assignment_id) REFERENCES profile_assignment (id);

-- Submitted revisions, changes, assignments history, decisions and route
-- evaluations are append-only. Draft edits arrive as new revisions;
-- assignment supersession uses status transitions on the assignment row.
CREATE FUNCTION reject_profile_history_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Profile history rows are append-only' USING ERRCODE = '42501';
END;
$$;

DROP TRIGGER IF EXISTS trg_profile_revision_immutable ON profile_revision;
CREATE TRIGGER trg_profile_revision_immutable
BEFORE UPDATE OR DELETE OR TRUNCATE ON profile_revision
FOR EACH STATEMENT EXECUTE FUNCTION reject_profile_history_mutation();

DROP TRIGGER IF EXISTS trg_profile_revision_change_immutable ON profile_revision_change;
CREATE TRIGGER trg_profile_revision_change_immutable
BEFORE UPDATE OR DELETE OR TRUNCATE ON profile_revision_change
FOR EACH STATEMENT EXECUTE FUNCTION reject_profile_history_mutation();

DROP TRIGGER IF EXISTS trg_profile_decision_immutable ON profile_decision;
CREATE TRIGGER trg_profile_decision_immutable
BEFORE UPDATE OR DELETE OR TRUNCATE ON profile_decision
FOR EACH STATEMENT EXECUTE FUNCTION reject_profile_history_mutation();

DROP TRIGGER IF EXISTS trg_profile_route_evaluation_immutable ON profile_route_evaluation;
CREATE TRIGGER trg_profile_route_evaluation_immutable
BEFORE UPDATE OR DELETE OR TRUNCATE ON profile_route_evaluation
FOR EACH STATEMENT EXECUTE FUNCTION reject_profile_history_mutation();
