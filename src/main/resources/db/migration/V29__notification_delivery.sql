CREATE TABLE notification_destination (
    public_id UUID PRIMARY KEY,
    employee_public_id UUID NOT NULL UNIQUE REFERENCES employees(public_id),
    address VARCHAR(254) NOT NULL,
    version BIGINT NOT NULL CHECK(version>=0),
    status VARCHAR(16) NOT NULL CHECK(status IN ('VERIFIED','SUPPRESSED')),
    evidence_ref VARCHAR(255) NOT NULL,
    verified_by VARCHAR(128) NOT NULL,
    verified_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE invitation_delivery_source (
    invitation_public_id UUID PRIMARY KEY REFERENCES enrollment_invitations(public_id),
    employee_public_id UUID NOT NULL REFERENCES employees(public_id),
    destination_id UUID NOT NULL REFERENCES notification_destination(public_id),
    destination_version BIGINT NOT NULL,
    ciphertext TEXT,
    key_id VARCHAR(2048) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE notification_intent (
    event_id UUID NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    aggregate_public_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    employee_public_id UUID NOT NULL,
    account_public_id UUID,
    template VARCHAR(64) NOT NULL,
    eligibility_type VARCHAR(64) NOT NULL,
    assignment_public_id UUID,
    destination_id UUID REFERENCES notification_destination(public_id),
    destination_version BIGINT,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(event_id,employee_public_id)
);
CREATE TABLE notification_delivery (
    public_id UUID PRIMARY KEY,
    original_delivery_id UUID REFERENCES notification_delivery(public_id),
    event_id UUID NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    aggregate_public_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    employee_public_id UUID NOT NULL,
    account_public_id UUID,
    template VARCHAR(64) NOT NULL,
    template_version INTEGER NOT NULL DEFAULT 1 CHECK(template_version=1),
    channel VARCHAR(16) NOT NULL DEFAULT 'EMAIL' CHECK(channel='EMAIL'),
    eligibility_type VARCHAR(64) NOT NULL,
    assignment_public_id UUID,
    destination_id UUID REFERENCES notification_destination(public_id),
    destination_version BIGINT,
    state VARCHAR(16) NOT NULL CHECK(state IN ('PENDING','SENDING','ACCEPTED','DELIVERED','UNKNOWN','FAILED','SUPPRESSED')),
    version BIGINT NOT NULL DEFAULT 0 CHECK(version>=0),
    lease_generation BIGINT NOT NULL DEFAULT 0 CHECK(lease_generation>=0),
    lease_ends_at TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK(attempt_count>=0),
    manual_attempt BOOLEAN NOT NULL DEFAULT FALSE,
    provider_message_id VARCHAR(255),
    error_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE(event_id,employee_public_id,channel,template_version),
    CHECK((destination_id IS NULL)=(destination_version IS NULL)),
    CHECK((state='SENDING')=(lease_ends_at IS NOT NULL))
);
CREATE INDEX notification_due ON notification_delivery(next_attempt_at) WHERE state='PENDING';
CREATE INDEX notification_expired_lease ON notification_delivery(lease_ends_at) WHERE state='SENDING';
CREATE TABLE notification_attempt (
    public_id UUID PRIMARY KEY,
    delivery_id UUID NOT NULL REFERENCES notification_delivery(public_id),
    generation BIGINT NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    manual_attempt BOOLEAN NOT NULL,
    UNIQUE(delivery_id,generation)
);
CREATE TABLE notification_evidence (
    public_id UUID PRIMARY KEY,
    delivery_id UUID NOT NULL REFERENCES notification_delivery(public_id),
    attempt_id UUID REFERENCES notification_attempt(public_id),
    generation BIGINT NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    provider_message_id VARCHAR(255),
    error_code VARCHAR(64),
    source_id VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE notification_operation (
    actor VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    delivery_id UUID NOT NULL REFERENCES notification_delivery(public_id),
    action VARCHAR(32) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    acknowledged_duplicate BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(actor,idempotency_key)
);
CREATE TABLE notification_rate_bucket (
    bucket_key VARCHAR(160) PRIMARY KEY,
    window_at TIMESTAMPTZ NOT NULL,
    used INTEGER NOT NULL CHECK(used>0)
);
CREATE TABLE notification_attention (
    public_id UUID PRIMARY KEY,
    event_id UUID,
    delivery_id UUID,
    code VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE notification_destination_operation (
    actor VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    destination_id UUID NOT NULL,
    destination_version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(actor,idempotency_key)
);
