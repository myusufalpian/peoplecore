CREATE TABLE document_account (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_id BIGINT NOT NULL UNIQUE REFERENCES employees (id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE private_document (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE,
    employee_id BIGINT NOT NULL REFERENCES employees (id),
    purpose VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    declared_media_type VARCHAR(100) NOT NULL,
    declared_size BIGINT NOT NULL,
    current_upload_id BIGINT,
    current_upload_generation BIGINT NOT NULL DEFAULT 0,
    current_generation BIGINT NOT NULL DEFAULT 0,
    current_version_id BIGINT,
    deletion_state VARCHAR(16) NOT NULL DEFAULT 'LIVE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_document_purpose CHECK (purpose IN ('CLAIM_EVIDENCE', 'PAYSLIP')),
    CONSTRAINT ck_document_status CHECK (status IN ('PENDING_UPLOAD', 'VERIFYING', 'VERIFIED', 'REJECTED')),
    CONSTRAINT ck_document_file_name CHECK (length(btrim(file_name)) > 0),
    CONSTRAINT ck_document_declared_size CHECK (declared_size BETWEEN 1 AND 10485760),
    CONSTRAINT ck_document_declared_type CHECK (
        declared_media_type IN ('application/pdf', 'image/jpeg', 'image/png')
        AND (purpose <> 'PAYSLIP' OR declared_media_type = 'application/pdf')
    ),
    CONSTRAINT ck_document_generation CHECK (current_generation >= 0 AND current_upload_generation >= 0),
    CONSTRAINT ck_document_deletion_state CHECK (deletion_state IN ('LIVE', 'CLAIMED', 'DELETED')),
    CONSTRAINT ck_document_verified_version CHECK (status <> 'VERIFIED' OR current_version_id IS NOT NULL)
);
CREATE INDEX idx_document_employee ON private_document (employee_id, created_at, id);

CREATE TABLE document_upload_attempt (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE,
    document_id BIGINT NOT NULL REFERENCES private_document (id),
    generation BIGINT NOT NULL,
    staging_key VARCHAR(512) NOT NULL UNIQUE,
    byte_size BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    object_etag VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uk_document_upload_generation UNIQUE (document_id, generation),
    CONSTRAINT uk_document_upload_owner UNIQUE (document_id, id),
    CONSTRAINT uk_document_upload_current UNIQUE (document_id, id, generation),
    CONSTRAINT ck_document_upload_generation CHECK (generation > 0),
    CONSTRAINT ck_document_upload_key CHECK (staging_key LIKE 'staging/%'),
    CONSTRAINT ck_document_upload_size CHECK (byte_size BETWEEN 1 AND 10485760),
    CONSTRAINT ck_document_upload_status CHECK (status IN ('RESERVED', 'COMPLETED', 'ABANDONED')),
    CONSTRAINT ck_document_upload_completion CHECK (
        status <> 'COMPLETED' OR (object_etag IS NOT NULL AND length(btrim(object_etag)) > 0 AND completed_at IS NOT NULL)
    )
);
ALTER TABLE private_document ADD CONSTRAINT fk_document_current_upload
    FOREIGN KEY (id, current_upload_id, current_upload_generation)
    REFERENCES document_upload_attempt (document_id, id, generation);

CREATE TABLE document_verification_attempt (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE,
    document_id BIGINT NOT NULL REFERENCES private_document (id),
    upload_attempt_id BIGINT NOT NULL,
    generation BIGINT NOT NULL,
    candidate_key VARCHAR(512) NOT NULL UNIQUE,
    status VARCHAR(16) NOT NULL,
    lease_token UUID,
    lease_expires_at TIMESTAMP WITH TIME ZONE,
    copy_started_at TIMESTAMP WITH TIME ZONE,
    copy_completed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uk_document_attempt_generation UNIQUE (document_id, generation),
    CONSTRAINT uk_document_attempt_owner UNIQUE (document_id, id),
    CONSTRAINT fk_document_attempt_upload FOREIGN KEY (document_id, upload_attempt_id)
        REFERENCES document_upload_attempt (document_id, id),
    CONSTRAINT ck_document_attempt_generation CHECK (generation > 0),
    CONSTRAINT ck_document_copy_completion CHECK (copy_completed_at IS NULL OR copy_started_at IS NOT NULL),
    CONSTRAINT ck_document_candidate_key CHECK (candidate_key LIKE 'candidates/%'),
    CONSTRAINT ck_document_attempt_status CHECK (status IN ('QUEUED', 'CLAIMED', 'CLEAN', 'REJECTED', 'ABANDONED')),
    CONSTRAINT ck_document_attempt_lease CHECK (
        (status = 'QUEUED' AND lease_token IS NULL AND lease_expires_at IS NULL)
        OR (status IN ('CLAIMED', 'CLEAN', 'REJECTED') AND lease_token IS NOT NULL AND lease_expires_at IS NOT NULL)
        OR status = 'ABANDONED'
    )
);
CREATE INDEX idx_document_attempt_work ON document_verification_attempt (status, lease_expires_at, id);

CREATE TABLE document_version (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE,
    document_id BIGINT NOT NULL,
    attempt_id BIGINT NOT NULL UNIQUE,
    lease_token UUID NOT NULL,
    media_type VARCHAR(100) NOT NULL,
    byte_size BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    scanner_version VARCHAR(255) NOT NULL,
    verified_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_document_version_attempt FOREIGN KEY (document_id, attempt_id)
        REFERENCES document_verification_attempt (document_id, id),
    CONSTRAINT uk_document_version_owner UNIQUE (document_id, id),
    CONSTRAINT ck_document_version_size CHECK (byte_size BETWEEN 1 AND 10485760),
    CONSTRAINT ck_document_version_type CHECK (media_type IN ('application/pdf', 'image/jpeg', 'image/png')),
    CONSTRAINT ck_document_version_sha CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_document_scanner_version CHECK (length(btrim(scanner_version)) > 0)
);
ALTER TABLE private_document ADD CONSTRAINT fk_document_current_version
    FOREIGN KEY (id, current_version_id) REFERENCES document_version (document_id, id);

CREATE TABLE document_hold (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE,
    document_id BIGINT NOT NULL REFERENCES private_document (id),
    reason VARCHAR(1000) NOT NULL,
    created_by BIGINT NOT NULL REFERENCES user_accounts (id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    released_at TIMESTAMP WITH TIME ZONE,
    release_reason VARCHAR(1000),
    CONSTRAINT ck_document_hold_reason CHECK (length(btrim(reason)) > 0),
    CONSTRAINT ck_document_hold_release CHECK (
        (released_at IS NULL AND release_reason IS NULL)
        OR (released_at IS NOT NULL AND release_reason IS NOT NULL AND length(btrim(release_reason)) > 0)
    )
);
CREATE UNIQUE INDEX uk_document_active_hold ON document_hold (document_id) WHERE released_at IS NULL;

CREATE FUNCTION guard_document_hold_change() RETURNS trigger AS $$
BEGIN
    IF TG_OP <> 'UPDATE' OR OLD.released_at IS NOT NULL OR NEW.released_at IS NULL
        OR NEW.public_id IS DISTINCT FROM OLD.public_id
        OR NEW.document_id IS DISTINCT FROM OLD.document_id
        OR NEW.reason IS DISTINCT FROM OLD.reason
        OR NEW.created_by IS DISTINCT FROM OLD.created_by
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Document hold history is immutable' USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER guard_document_hold_update_delete BEFORE UPDATE OR DELETE ON document_hold
    FOR EACH ROW EXECUTE FUNCTION guard_document_hold_change();
CREATE TRIGGER guard_document_hold_truncate BEFORE TRUNCATE ON document_hold
    FOR EACH STATEMENT EXECUTE FUNCTION guard_document_hold_change();

CREATE FUNCTION guard_document_upload_change() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'RESERVED' THEN
            RAISE EXCEPTION 'Document uploads must start reserved' USING ERRCODE = '42501';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.document_id IS DISTINCT FROM OLD.document_id OR NEW.public_id IS DISTINCT FROM OLD.public_id
        OR NEW.generation IS DISTINCT FROM OLD.generation OR NEW.staging_key IS DISTINCT FROM OLD.staging_key
        OR NEW.byte_size IS DISTINCT FROM OLD.byte_size
        OR NEW.created_at IS DISTINCT FROM OLD.created_at OR OLD.status <> 'RESERVED'
        OR NEW.status NOT IN ('COMPLETED', 'ABANDONED') THEN
        RAISE EXCEPTION 'Document upload identity and completed results are immutable' USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER guard_document_upload BEFORE INSERT OR UPDATE ON document_upload_attempt
    FOR EACH ROW EXECUTE FUNCTION guard_document_upload_change();

CREATE FUNCTION guard_document_attempt_insert() RETURNS trigger AS $$
DECLARE parent_status VARCHAR(32);
DECLARE parent_deletion_state VARCHAR(16);
DECLARE parent_generation BIGINT;
DECLARE parent_upload_id BIGINT;
DECLARE upload_status VARCHAR(16);
BEGIN
    SELECT status, deletion_state, current_generation, current_upload_id
        INTO parent_status, parent_deletion_state, parent_generation, parent_upload_id
        FROM private_document WHERE id = NEW.document_id FOR UPDATE;
    SELECT status INTO upload_status FROM document_upload_attempt
        WHERE id = NEW.upload_attempt_id AND document_id = NEW.document_id FOR UPDATE;
    IF parent_status IS NULL OR parent_status <> 'VERIFYING' OR parent_deletion_state <> 'LIVE'
        OR parent_generation <> NEW.generation OR NEW.status <> 'QUEUED'
        OR NEW.copy_started_at IS NOT NULL OR NEW.copy_completed_at IS NOT NULL
        OR parent_upload_id IS DISTINCT FROM NEW.upload_attempt_id OR upload_status IS DISTINCT FROM 'COMPLETED' THEN
        RAISE EXCEPTION 'Document attempt requires the current verification generation' USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER guard_document_attempt_insert BEFORE INSERT ON document_verification_attempt
    FOR EACH ROW EXECUTE FUNCTION guard_document_attempt_insert();

CREATE FUNCTION guard_document_attempt_identity() RETURNS trigger AS $$
BEGIN
    IF NEW.document_id IS DISTINCT FROM OLD.document_id
        OR NEW.upload_attempt_id IS DISTINCT FROM OLD.upload_attempt_id
        OR NEW.public_id IS DISTINCT FROM OLD.public_id
        OR NEW.generation IS DISTINCT FROM OLD.generation
        OR NEW.candidate_key IS DISTINCT FROM OLD.candidate_key
        OR NEW.created_at IS DISTINCT FROM OLD.created_at
        OR (OLD.copy_started_at IS NOT NULL AND NEW.copy_started_at IS DISTINCT FROM OLD.copy_started_at)
        OR (OLD.copy_completed_at IS NOT NULL AND NEW.copy_completed_at IS DISTINCT FROM OLD.copy_completed_at)
        OR (OLD.lease_token IS NOT NULL AND NEW.lease_token IS DISTINCT FROM OLD.lease_token)
        OR OLD.status IN ('CLEAN', 'REJECTED', 'ABANDONED') THEN
        RAISE EXCEPTION 'Document attempt identity and completed results are immutable' USING ERRCODE = '42501';
    END IF;
    IF (NEW.copy_started_at IS NOT NULL AND OLD.copy_started_at IS NULL AND OLD.status <> 'CLAIMED')
        OR (NEW.copy_completed_at IS NOT NULL AND OLD.copy_completed_at IS NULL AND OLD.copy_started_at IS NULL)
        OR (NEW.status = 'CLEAN' AND NEW.copy_completed_at IS NULL) THEN
        RAISE EXCEPTION 'Document copy must be claimed and completed once' USING ERRCODE = '42501';
    END IF;
    IF NOT ((OLD.status = 'QUEUED' AND NEW.status IN ('QUEUED', 'CLAIMED', 'ABANDONED'))
        OR (OLD.status = 'CLAIMED' AND NEW.status IN ('CLAIMED', 'CLEAN', 'REJECTED', 'ABANDONED'))) THEN
        RAISE EXCEPTION 'Invalid document attempt transition' USING ERRCODE = '23514';
    END IF;
    IF (NEW.status = 'CLAIMED' AND NEW.lease_expires_at <= clock_timestamp())
        OR (OLD.status = 'CLAIMED' AND NEW.status IN ('CLAIMED', 'CLEAN', 'REJECTED')
            AND OLD.lease_expires_at <= clock_timestamp()) THEN
        RAISE EXCEPTION 'Expired document leases cannot be renewed or completed' USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER guard_document_attempt BEFORE UPDATE ON document_verification_attempt
    FOR EACH ROW EXECUTE FUNCTION guard_document_attempt_identity();

CREATE FUNCTION guard_document_version_insert() RETURNS trigger AS $$
DECLARE parent_status VARCHAR(32);
DECLARE parent_deletion_state VARCHAR(16);
DECLARE parent_generation BIGINT;
DECLARE parent_type VARCHAR(100);
DECLARE parent_size BIGINT;
DECLARE parent_upload_id BIGINT;
DECLARE upload_status VARCHAR(16);
DECLARE upload_size BIGINT;
DECLARE attempt_document_id BIGINT;
DECLARE attempt_upload_id BIGINT;
DECLARE attempt_generation BIGINT;
DECLARE attempt_status VARCHAR(16);
DECLARE attempt_token UUID;
DECLARE attempt_expiry TIMESTAMP WITH TIME ZONE;
BEGIN
    SELECT status, deletion_state, current_generation, declared_media_type, declared_size, current_upload_id
        INTO parent_status, parent_deletion_state, parent_generation, parent_type, parent_size, parent_upload_id
        FROM private_document WHERE id = NEW.document_id FOR UPDATE;
    SELECT status, byte_size INTO upload_status, upload_size
        FROM document_upload_attempt WHERE id = parent_upload_id AND document_id = NEW.document_id FOR UPDATE;
    SELECT document_id, upload_attempt_id, generation, status, lease_token, lease_expires_at
        INTO attempt_document_id, attempt_upload_id, attempt_generation, attempt_status, attempt_token, attempt_expiry
        FROM document_verification_attempt WHERE id = NEW.attempt_id FOR UPDATE;
    IF parent_status IS NULL OR attempt_document_id IS NULL OR attempt_document_id <> NEW.document_id
        OR parent_status <> 'VERIFYING' OR parent_deletion_state <> 'LIVE'
        OR parent_generation <> attempt_generation OR attempt_status <> 'CLEAN'
        OR attempt_token IS DISTINCT FROM NEW.lease_token
        OR attempt_expiry <= clock_timestamp() OR parent_type <> NEW.media_type
        OR attempt_upload_id IS DISTINCT FROM parent_upload_id OR upload_status IS DISTINCT FROM 'COMPLETED'
        OR parent_size IS DISTINCT FROM NEW.byte_size OR upload_size IS DISTINCT FROM NEW.byte_size THEN
        RAISE EXCEPTION 'Document version requires a current clean verification lease' USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER guard_document_version BEFORE INSERT ON document_version
    FOR EACH ROW EXECUTE FUNCTION guard_document_version_insert();

CREATE FUNCTION reject_document_version_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Verified document versions are immutable' USING ERRCODE = '42501';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER reject_document_version_update_delete BEFORE UPDATE OR DELETE ON document_version
    FOR EACH ROW EXECUTE FUNCTION reject_document_version_mutation();
CREATE TRIGGER reject_document_version_truncate BEFORE TRUNCATE ON document_version
    FOR EACH STATEMENT EXECUTE FUNCTION reject_document_version_mutation();
