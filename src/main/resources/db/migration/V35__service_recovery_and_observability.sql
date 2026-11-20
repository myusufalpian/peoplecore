CREATE TABLE service_recovery_incident (
    public_id UUID PRIMARY KEY,
    status VARCHAR(32) NOT NULL CHECK (status IN ('ISOLATED', 'RECONCILING', 'REOPENED')),
    restore_point_at TIMESTAMPTZ NOT NULL,
    observed_loss_window_seconds BIGINT NOT NULL CHECK (observed_loss_window_seconds >= 0),
    initiated_by VARCHAR(128) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    reconciled_at TIMESTAMPTZ,
    reopened_at TIMESTAMPTZ,
    rto_seconds BIGINT CHECK (rto_seconds IS NULL OR rto_seconds >= 0),
    CHECK (status <> 'REOPENED' OR (reconciled_at IS NOT NULL AND reopened_at IS NOT NULL AND rto_seconds IS NOT NULL))
);

CREATE UNIQUE INDEX uk_service_recovery_active_incident
    ON service_recovery_incident ((1))
    WHERE status IN ('ISOLATED', 'RECONCILING');

CREATE TABLE service_recovery_exception (
    public_id UUID PRIMARY KEY,
    incident_public_id UUID NOT NULL REFERENCES service_recovery_incident(public_id),
    domain VARCHAR(32) NOT NULL CHECK (domain IN ('IDENTITY', 'DOCUMENT', 'NOTIFICATION', 'EXPORT', 'HR', 'FINANCE')),
    owner_role VARCHAR(32) NOT NULL CHECK (owner_role IN ('SYSTEM_ADMIN', 'HR_ADMIN', 'FINANCE_PAYROLL')),
    resource_type VARCHAR(64) NOT NULL,
    resource_public_id UUID NOT NULL,
    exception_code VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL CHECK (status IN ('OPEN', 'RESOLVED', 'ACCEPTED_RESTRICTION')),
    detail VARCHAR(1000) NOT NULL,
    resolved_by VARCHAR(128),
    resolution_reason VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL,
    resolved_at TIMESTAMPTZ,
    UNIQUE (incident_public_id, resource_type, resource_public_id, exception_code),
    CHECK (status = 'OPEN' OR (resolved_by IS NOT NULL AND resolution_reason IS NOT NULL AND resolved_at IS NOT NULL))
);

CREATE INDEX idx_service_recovery_exception_resource
    ON service_recovery_exception (resource_type, resource_public_id, status);

CREATE TABLE service_recovery_approval (
    incident_public_id UUID NOT NULL REFERENCES service_recovery_incident(public_id),
    approver_role VARCHAR(32) NOT NULL CHECK (approver_role IN ('HR_ADMIN', 'FINANCE_PAYROLL', 'SYSTEM_ADMIN')),
    actor_id VARCHAR(128) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    approved_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (incident_public_id, approver_role)
);

CREATE TABLE service_recovery_operation (
    actor VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    incident_public_id UUID NOT NULL,
    action VARCHAR(64) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (actor, idempotency_key)
);

CREATE FUNCTION reject_service_recovery_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Service recovery evidence is append-only' USING ERRCODE = '42501';
END;
$$;

CREATE FUNCTION protect_service_recovery_incident() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP <> 'UPDATE'
        OR NEW.public_id IS DISTINCT FROM OLD.public_id
        OR NEW.restore_point_at IS DISTINCT FROM OLD.restore_point_at
        OR NEW.observed_loss_window_seconds IS DISTINCT FROM OLD.observed_loss_window_seconds
        OR NEW.initiated_by IS DISTINCT FROM OLD.initiated_by
        OR NEW.reason IS DISTINCT FROM OLD.reason
        OR NEW.created_at IS DISTINCT FROM OLD.created_at
        OR OLD.status = 'REOPENED' THEN
        RAISE EXCEPTION 'Service recovery incident identity and reopened state are immutable' USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER service_recovery_incident_guard BEFORE UPDATE OR DELETE ON service_recovery_incident
    FOR EACH ROW EXECUTE FUNCTION protect_service_recovery_incident();
CREATE TRIGGER service_recovery_incident_no_truncate BEFORE TRUNCATE ON service_recovery_incident
    FOR EACH STATEMENT EXECUTE FUNCTION reject_service_recovery_mutation();

CREATE FUNCTION protect_service_recovery_exception() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP <> 'UPDATE'
        OR NEW.public_id IS DISTINCT FROM OLD.public_id
        OR NEW.incident_public_id IS DISTINCT FROM OLD.incident_public_id
        OR NEW.domain IS DISTINCT FROM OLD.domain
        OR NEW.owner_role IS DISTINCT FROM OLD.owner_role
        OR NEW.resource_type IS DISTINCT FROM OLD.resource_type
        OR NEW.resource_public_id IS DISTINCT FROM OLD.resource_public_id
        OR NEW.exception_code IS DISTINCT FROM OLD.exception_code
        OR NEW.detail IS DISTINCT FROM OLD.detail
        OR NEW.created_at IS DISTINCT FROM OLD.created_at
        OR (OLD.status <> 'OPEN' AND NEW.status = 'OPEN') THEN
        RAISE EXCEPTION 'Service recovery exception identity and resolved state are immutable' USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER service_recovery_exception_guard BEFORE UPDATE OR DELETE ON service_recovery_exception
    FOR EACH ROW EXECUTE FUNCTION protect_service_recovery_exception();
CREATE TRIGGER service_recovery_exception_no_truncate BEFORE TRUNCATE ON service_recovery_exception
    FOR EACH STATEMENT EXECUTE FUNCTION reject_service_recovery_mutation();

CREATE TRIGGER service_recovery_approval_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON service_recovery_approval
    FOR EACH STATEMENT EXECUTE FUNCTION reject_service_recovery_mutation();
CREATE TRIGGER service_recovery_operation_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON service_recovery_operation
    FOR EACH STATEMENT EXECUTE FUNCTION reject_service_recovery_mutation();
