ALTER TABLE outbox_events ADD COLUMN correlation_id VARCHAR(128) NOT NULL DEFAULT gen_random_uuid()::text;
ALTER TABLE outbox_events ADD COLUMN lease_generation BIGINT NOT NULL DEFAULT 0;
ALTER TABLE outbox_events ADD COLUMN lease_until TIMESTAMPTZ;
ALTER TABLE outbox_events ADD COLUMN next_attempt_at TIMESTAMPTZ;
ALTER TABLE outbox_events ADD COLUMN transport_reason VARCHAR(64);
CREATE INDEX idx_outbox_due ON outbox_events (status, next_attempt_at, created_at);

CREATE TABLE inbox_events (
    consumer VARCHAR(64) NOT NULL,
    event_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (consumer, event_id)
);
CREATE TABLE event_quarantine (
    id UUID PRIMARY KEY,
    event_id UUID,
    consumer VARCHAR(64) NOT NULL,
    reason VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (consumer, event_id)
);
CREATE TABLE event_replays (
    id UUID PRIMARY KEY,
    actor_id VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    consumer VARCHAR(64) NOT NULL CHECK (consumer IN ('notifications', 'exports')),
    reason VARCHAR(1000) NOT NULL,
    status VARCHAR(32) NOT NULL CHECK (status IN ('PENDING', 'COMPLETE', 'CANCELLED')),
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (actor_id, idempotency_key)
);
CREATE TABLE event_replay_items (
    replay_id UUID NOT NULL REFERENCES event_replays(id),
    event_id UUID NOT NULL REFERENCES outbox_events(event_id),
    status VARCHAR(32) NOT NULL CHECK (status IN ('PENDING', 'PUBLISHED', 'QUARANTINED', 'CANCELLED')),
    lease_generation BIGINT NOT NULL DEFAULT 0,
    lease_until TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    PRIMARY KEY (replay_id, event_id)
);
CREATE TABLE event_transport_budget (
    singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
    window_start TIMESTAMPTZ NOT NULL,
    used INTEGER NOT NULL CHECK (used >= 0)
);
INSERT INTO event_transport_budget VALUES (1, '1970-01-01T00:00:00Z', 0);
