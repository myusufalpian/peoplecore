-- V1__init_foundation.sql
-- Foundation schema: Outbox, Audit, Command Receipts, and core aggregate foundations

-- 1. Command Receipts (Idempotency)
CREATE TABLE IF NOT EXISTS command_receipts (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_id VARCHAR(128) NOT NULL,
    command_type VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    result_status VARCHAR(64) NOT NULL,
    result_payload TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_command_receipts_actor_type_key UNIQUE (actor_id, command_type, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_command_receipts_expires_at ON command_receipts (expires_at);

-- 2. Audit Events (Append-only)
CREATE TABLE IF NOT EXISTS audit_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,
    actor_id VARCHAR(128) NOT NULL,
    action VARCHAR(128) NOT NULL,
    aggregate_type VARCHAR(128) NOT NULL,
    aggregate_id BIGINT,
    aggregate_public_id UUID,
    correlation_id VARCHAR(128),
    reason TEXT,
    details TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_audit_events_aggregate ON audit_events (aggregate_type, aggregate_id);
CREATE INDEX IF NOT EXISTS idx_audit_events_created_at ON audit_events (created_at);

-- 3. Outbox Events (Transactional Outbox)
CREATE TABLE IF NOT EXISTS outbox_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,
    event_type VARCHAR(128) NOT NULL,
    schema_version INT NOT NULL DEFAULT 1,
    aggregate_type VARCHAR(128) NOT NULL,
    aggregate_id BIGINT,
    aggregate_public_id UUID,
    payload TEXT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at TIMESTAMP WITH TIME ZONE,
    attempt_count INT NOT NULL DEFAULT 0,
    last_attempt_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_outbox_events_status_created ON outbox_events (status, created_at);

-- 4. User Accounts (MVP core aggregate)
CREATE TABLE IF NOT EXISTS user_accounts (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE,
    oidc_issuer VARCHAR(255) NOT NULL,
    oidc_subject VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    access_ends_at TIMESTAMP WITH TIME ZONE,
    cutoff_time TIME,
    cutoff_timezone VARCHAR(64),
    last_login_at TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_user_accounts_issuer_subject UNIQUE (oidc_issuer, oidc_subject)
);

-- 5. Employees (MVP core aggregate)
CREATE TABLE IF NOT EXISTS employees (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE,
    employee_number VARCHAR(64) NOT NULL UNIQUE,
    user_account_id BIGINT REFERENCES user_accounts (id),
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_employees_account_id ON employees (user_account_id);
