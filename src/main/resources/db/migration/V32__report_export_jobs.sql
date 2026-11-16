CREATE TABLE report_export_job (
    public_id UUID PRIMARY KEY,
    requester_public_id UUID NOT NULL REFERENCES user_accounts(public_id),
    requester_issuer VARCHAR(255) NOT NULL,
    requester_subject VARCHAR(255) NOT NULL,
    request_json TEXT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','GENERATING','READY','INVALIDATED','EXPIRED','FAILED')),
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    data_as_of TIMESTAMPTZ,
    authorization_generation BIGINT,
    next_boundary TIMESTAMPTZ,
    lease_generation BIGINT NOT NULL DEFAULT 0,
    lease_until TIMESTAMPTZ,
    candidate_id UUID,
    byte_size BIGINT CHECK(byte_size BETWEEN 1 AND 10485760),
    sha256 VARCHAR(64),
    reason_code VARCHAR(64),
    CHECK(expires_at=created_at+interval '24 hours'),
    CHECK(status<>'GENERATING' OR lease_until IS NOT NULL),
    CHECK(status<>'READY' OR (candidate_id IS NOT NULL AND byte_size IS NOT NULL AND sha256 IS NOT NULL
        AND data_as_of IS NOT NULL AND authorization_generation IS NOT NULL))
);
CREATE INDEX report_export_due ON report_export_job(status,lease_until,created_at);
CREATE INDEX report_export_requester ON report_export_job(requester_public_id,status);

CREATE TABLE report_export_candidate (
    public_id UUID PRIMARY KEY,
    job_public_id UUID NOT NULL REFERENCES report_export_job(public_id),
    lease_generation BIGINT NOT NULL,
    registered_at TIMESTAMPTZ NOT NULL,
    next_reconcile_at TIMESTAMPTZ NOT NULL,
    uploaded_at TIMESTAMPTZ,
    byte_size BIGINT NOT NULL CHECK(byte_size BETWEEN 1 AND 10485760),
    sha256 VARCHAR(64) NOT NULL CHECK(length(sha256)=64),
    deleted_at TIMESTAMPTZ,
    UNIQUE(job_public_id,lease_generation),
    CHECK(uploaded_at IS NULL OR (byte_size IS NOT NULL AND sha256 IS NOT NULL)),
    CHECK(deleted_at IS NULL OR uploaded_at IS NOT NULL)
);

CREATE FUNCTION preserve_report_export_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.public_id<>OLD.public_id OR NEW.requester_public_id<>OLD.requester_public_id
       OR NEW.requester_issuer<>OLD.requester_issuer OR NEW.requester_subject<>OLD.requester_subject
       OR NEW.request_json<>OLD.request_json OR NEW.created_at<>OLD.created_at OR NEW.expires_at<>OLD.expires_at
       OR NEW.lease_generation<OLD.lease_generation THEN
        RAISE EXCEPTION 'Export identity is immutable';
    END IF;
    IF OLD.status IN ('INVALIDATED','EXPIRED','FAILED') AND NEW.status<>OLD.status THEN
        RAISE EXCEPTION 'Terminal export cannot be resurrected';
    END IF;
    IF OLD.status='READY' AND NEW.status NOT IN ('READY','INVALIDATED','EXPIRED') THEN
        RAISE EXCEPTION 'Published export cannot be regenerated';
    END IF;
    IF OLD.status='READY' AND (NEW.candidate_id IS DISTINCT FROM OLD.candidate_id
       OR NEW.data_as_of IS DISTINCT FROM OLD.data_as_of OR NEW.authorization_generation IS DISTINCT FROM OLD.authorization_generation
       OR NEW.next_boundary IS DISTINCT FROM OLD.next_boundary OR NEW.byte_size IS DISTINCT FROM OLD.byte_size
       OR NEW.sha256 IS DISTINCT FROM OLD.sha256 OR NEW.lease_generation<>OLD.lease_generation) THEN
        RAISE EXCEPTION 'Published export artifact is immutable';
    END IF;
    IF NEW.status='READY' AND NOT EXISTS(
        SELECT c.public_id FROM report_export_candidate c WHERE c.public_id=NEW.candidate_id
        AND c.job_public_id=NEW.public_id AND c.lease_generation=NEW.lease_generation
        AND c.uploaded_at IS NOT NULL AND c.deleted_at IS NULL
        AND c.byte_size=NEW.byte_size AND c.sha256=NEW.sha256) THEN
        RAISE EXCEPTION 'Export candidate is not confirmed for this generation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER report_export_identity_guard BEFORE UPDATE ON report_export_job
FOR EACH ROW EXECUTE FUNCTION preserve_report_export_identity();

CREATE FUNCTION preserve_export_candidate() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.public_id<>OLD.public_id OR NEW.job_public_id<>OLD.job_public_id OR NEW.lease_generation<>OLD.lease_generation
       OR NEW.registered_at<>OLD.registered_at
       OR NEW.byte_size<>OLD.byte_size OR NEW.sha256<>OLD.sha256
       OR (OLD.uploaded_at IS NOT NULL AND (NEW.uploaded_at IS DISTINCT FROM OLD.uploaded_at
           OR NEW.byte_size IS DISTINCT FROM OLD.byte_size OR NEW.sha256 IS DISTINCT FROM OLD.sha256))
       OR (OLD.deleted_at IS NOT NULL AND NEW.deleted_at IS DISTINCT FROM OLD.deleted_at) THEN
        RAISE EXCEPTION 'Export candidate evidence is immutable';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER report_export_candidate_guard BEFORE UPDATE ON report_export_candidate
FOR EACH ROW EXECUTE FUNCTION preserve_export_candidate();
