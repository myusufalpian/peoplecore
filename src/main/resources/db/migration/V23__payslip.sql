CREATE TABLE payslip_batch (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE,
    created_by BIGINT NOT NULL REFERENCES user_accounts (id),
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    current_revision_id BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_payslip_batch_status CHECK (status IN ('DRAFT', 'REVIEWED', 'PUBLISHED'))
);

CREATE TABLE payslip_batch_revision (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE,
    batch_id BIGINT NOT NULL REFERENCES payslip_batch (id),
    revision_number BIGINT NOT NULL CHECK (revision_number > 0),
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    reviewed_by BIGINT REFERENCES user_accounts (id),
    reviewed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uk_payslip_batch_revision_number UNIQUE (batch_id, revision_number),
    CONSTRAINT uk_payslip_batch_revision_owner UNIQUE (batch_id, id),
    CONSTRAINT ck_payslip_revision_status CHECK (
        (status = 'DRAFT' AND reviewed_by IS NULL AND reviewed_at IS NULL)
        OR (status = 'REVIEWED' AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL)
    )
);
ALTER TABLE payslip_batch ADD CONSTRAINT fk_payslip_batch_current_revision
    FOREIGN KEY (id, current_revision_id) REFERENCES payslip_batch_revision (batch_id, id);

CREATE TABLE payslip_batch_item (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE,
    revision_id BIGINT NOT NULL REFERENCES payslip_batch_revision (id),
    employee_id BIGINT NOT NULL REFERENCES employees (id),
    period_start DATE NOT NULL,
    document_id BIGINT NOT NULL,
    document_version_id BIGINT NOT NULL,
    CONSTRAINT fk_payslip_item_document_version FOREIGN KEY (document_id, document_version_id)
        REFERENCES document_version (document_id, id),
    CONSTRAINT uk_payslip_item_period UNIQUE (revision_id, employee_id, period_start),
    CONSTRAINT uk_payslip_item_publication UNIQUE (id, revision_id, employee_id, period_start),
    CONSTRAINT ck_payslip_item_month CHECK (EXTRACT(DAY FROM period_start) = 1)
);

CREATE TABLE payslip_publication (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE,
    revision_id BIGINT NOT NULL REFERENCES payslip_batch_revision (id),
    item_id BIGINT NOT NULL,
    employee_id BIGINT NOT NULL REFERENCES employees (id),
    period_start DATE NOT NULL,
    published_by BIGINT NOT NULL REFERENCES user_accounts (id),
    published_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_payslip_publication_item FOREIGN KEY (item_id, revision_id, employee_id, period_start)
        REFERENCES payslip_batch_item (id, revision_id, employee_id, period_start),
    CONSTRAINT uk_payslip_publication_item UNIQUE (item_id),
    CONSTRAINT uk_payslip_publication_current UNIQUE (id, employee_id, period_start)
);

CREATE TABLE payslip_current (
    employee_id BIGINT NOT NULL REFERENCES employees (id),
    period_start DATE NOT NULL,
    publication_id BIGINT NOT NULL,
    PRIMARY KEY (employee_id, period_start),
    CONSTRAINT fk_payslip_current_publication FOREIGN KEY (publication_id, employee_id, period_start)
        REFERENCES payslip_publication (id, employee_id, period_start)
);

CREATE FUNCTION guard_payslip_batch_change() RETURNS trigger AS $$
DECLARE revision_status VARCHAR(16);
DECLARE item_count BIGINT;
DECLARE current_count BIGINT;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'DRAFT' OR NEW.current_revision_id IS NOT NULL THEN
            RAISE EXCEPTION 'Payslip batches must start as drafts' USING ERRCODE = '42501';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP <> 'UPDATE' OR NEW.public_id IS DISTINCT FROM OLD.public_id
        OR NEW.created_by IS DISTINCT FROM OLD.created_by OR NEW.created_at IS DISTINCT FROM OLD.created_at
        OR OLD.status = 'PUBLISHED' THEN
        RAISE EXCEPTION 'Payslip batch history is immutable' USING ERRCODE = '42501';
    END IF;
    IF NOT ((OLD.status = 'DRAFT' AND NEW.status IN ('DRAFT', 'REVIEWED'))
        OR (OLD.status = 'REVIEWED' AND NEW.status IN ('DRAFT', 'PUBLISHED'))) THEN
        RAISE EXCEPTION 'Invalid payslip batch transition' USING ERRCODE = '23514';
    END IF;
    IF NEW.status = 'REVIEWED' THEN
        SELECT status INTO revision_status FROM payslip_batch_revision
            WHERE id = NEW.current_revision_id AND batch_id = NEW.id;
        IF revision_status IS DISTINCT FROM 'REVIEWED' THEN
            RAISE EXCEPTION 'Payslip batch requires a reviewed revision' USING ERRCODE = '23514';
        END IF;
    END IF;
    IF OLD.status = 'REVIEWED' AND NEW.status = 'DRAFT'
        AND NEW.current_revision_id IS NOT DISTINCT FROM OLD.current_revision_id THEN
        RAISE EXCEPTION 'Payslip edits require a new revision' USING ERRCODE = '23514';
    END IF;
    IF NEW.status = 'PUBLISHED' AND NEW.current_revision_id IS DISTINCT FROM OLD.current_revision_id THEN
        RAISE EXCEPTION 'Payslip publication cannot change the reviewed revision' USING ERRCODE = '23514';
    END IF;
    IF NEW.status = 'PUBLISHED' THEN
        SELECT count(id) INTO item_count FROM payslip_batch_item WHERE revision_id = NEW.current_revision_id;
        SELECT count(p.id) INTO current_count FROM payslip_publication p
            JOIN payslip_current c ON c.publication_id = p.id AND c.employee_id = p.employee_id
                AND c.period_start = p.period_start WHERE p.revision_id = NEW.current_revision_id;
        IF item_count < 1 OR current_count <> item_count THEN
            RAISE EXCEPTION 'Payslip publication must include the complete reviewed batch' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER guard_payslip_batch BEFORE INSERT OR UPDATE OR DELETE ON payslip_batch
    FOR EACH ROW EXECUTE FUNCTION guard_payslip_batch_change();

CREATE FUNCTION guard_payslip_item_change() RETURNS trigger AS $$
DECLARE revision_status VARCHAR(16);
DECLARE doc_owner BIGINT;
DECLARE doc_status VARCHAR(32);
DECLARE doc_purpose VARCHAR(32);
DECLARE doc_deletion_state VARCHAR(16);
DECLARE doc_current_version BIGINT;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'Payslip items cannot be changed in place' USING ERRCODE = '42501';
    END IF;
    IF TG_OP = 'DELETE' THEN
        SELECT status INTO revision_status FROM payslip_batch_revision WHERE id = OLD.revision_id FOR UPDATE;
        IF revision_status <> 'DRAFT' THEN
            RAISE EXCEPTION 'Reviewed payslip items are immutable' USING ERRCODE = '42501';
        END IF;
        RETURN OLD;
    END IF;
    SELECT status INTO revision_status FROM payslip_batch_revision WHERE id = NEW.revision_id FOR UPDATE;
    SELECT employee_id, status, purpose, deletion_state, current_version_id
        INTO doc_owner, doc_status, doc_purpose, doc_deletion_state, doc_current_version
        FROM private_document WHERE id = NEW.document_id FOR UPDATE;
    IF revision_status IS DISTINCT FROM 'DRAFT' OR doc_owner IS DISTINCT FROM NEW.employee_id
        OR doc_status IS DISTINCT FROM 'VERIFIED' OR doc_purpose IS DISTINCT FROM 'PAYSLIP'
        OR doc_deletion_state IS DISTINCT FROM 'LIVE' OR doc_current_version IS DISTINCT FROM NEW.document_version_id THEN
        RAISE EXCEPTION 'Payslip item requires a current verified employee document' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER guard_payslip_item BEFORE INSERT OR UPDATE OR DELETE ON payslip_batch_item
    FOR EACH ROW EXECUTE FUNCTION guard_payslip_item_change();

CREATE FUNCTION guard_payslip_revision_change() RETURNS trigger AS $$
DECLARE item_count BIGINT;
DECLARE invalid_count BIGINT;
DECLARE batch_state VARCHAR(16);
DECLARE current_revision BIGINT;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'Payslip revisions must start as drafts' USING ERRCODE = '42501';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP <> 'UPDATE' OR OLD.status <> 'DRAFT' OR NEW.status <> 'REVIEWED'
        OR NEW.public_id IS DISTINCT FROM OLD.public_id OR NEW.batch_id IS DISTINCT FROM OLD.batch_id
        OR NEW.revision_number IS DISTINCT FROM OLD.revision_number OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Payslip revision history is immutable' USING ERRCODE = '42501';
    END IF;
    SELECT count(id) INTO item_count FROM payslip_batch_item WHERE revision_id = OLD.id;
    IF item_count < 1 OR item_count > 100 THEN
        RAISE EXCEPTION 'A reviewed payslip revision requires 1 to 100 items' USING ERRCODE = '23514';
    END IF;
    SELECT status, current_revision_id INTO batch_state, current_revision
        FROM payslip_batch WHERE id = OLD.batch_id FOR UPDATE;
    IF batch_state IS DISTINCT FROM 'DRAFT' OR current_revision IS DISTINCT FROM OLD.id THEN
        RAISE EXCEPTION 'Only the current draft may be reviewed' USING ERRCODE = '23514';
    END IF;
    PERFORM d.id FROM private_document d JOIN payslip_batch_item i ON i.document_id = d.id
        WHERE i.revision_id = OLD.id ORDER BY d.id FOR UPDATE OF d;
    SELECT count(i.id) INTO invalid_count FROM payslip_batch_item i
        JOIN private_document d ON d.id = i.document_id WHERE i.revision_id = OLD.id
        AND (d.employee_id IS DISTINCT FROM i.employee_id OR d.status <> 'VERIFIED' OR d.purpose <> 'PAYSLIP'
            OR d.deletion_state <> 'LIVE' OR d.current_version_id IS DISTINCT FROM i.document_version_id);
    IF invalid_count <> 0 THEN
        RAISE EXCEPTION 'Payslip review requires current verified documents' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER guard_payslip_revision BEFORE INSERT OR UPDATE OR DELETE ON payslip_batch_revision
    FOR EACH ROW EXECUTE FUNCTION guard_payslip_revision_change();

CREATE FUNCTION guard_payslip_publication_insert() RETURNS trigger AS $$
DECLARE revision_status VARCHAR(16);
DECLARE batch_status VARCHAR(16);
DECLARE current_revision BIGINT;
DECLARE item_document BIGINT;
DECLARE item_document_version BIGINT;
DECLARE doc_owner BIGINT;
DECLARE doc_status VARCHAR(32);
DECLARE doc_purpose VARCHAR(32);
DECLARE doc_deletion_state VARCHAR(16);
DECLARE doc_current_version BIGINT;
BEGIN
    SELECT r.status, b.status, b.current_revision_id
        INTO revision_status, batch_status, current_revision
        FROM payslip_batch_revision r JOIN payslip_batch b ON b.id = r.batch_id
        WHERE r.id = NEW.revision_id FOR UPDATE OF b, r;
    SELECT document_id, document_version_id INTO item_document, item_document_version
        FROM payslip_batch_item WHERE id = NEW.item_id AND revision_id = NEW.revision_id
        AND employee_id = NEW.employee_id AND period_start = NEW.period_start;
    SELECT employee_id, status, purpose, deletion_state, current_version_id
        INTO doc_owner, doc_status, doc_purpose, doc_deletion_state, doc_current_version
        FROM private_document WHERE id = item_document FOR UPDATE;
    IF revision_status IS DISTINCT FROM 'REVIEWED' OR batch_status IS DISTINCT FROM 'REVIEWED'
        OR current_revision IS DISTINCT FROM NEW.revision_id OR doc_owner IS DISTINCT FROM NEW.employee_id
        OR doc_status IS DISTINCT FROM 'VERIFIED' OR doc_purpose IS DISTINCT FROM 'PAYSLIP'
        OR doc_deletion_state IS DISTINCT FROM 'LIVE' OR doc_current_version IS DISTINCT FROM item_document_version THEN
        RAISE EXCEPTION 'Payslip publication requires a current reviewed revision and verified document' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER guard_payslip_publication BEFORE INSERT ON payslip_publication
    FOR EACH ROW EXECUTE FUNCTION guard_payslip_publication_insert();

CREATE FUNCTION guard_payslip_current_change() RETURNS trigger AS $$
DECLARE batch_status VARCHAR(16);
DECLARE current_revision BIGINT;
DECLARE publication_revision BIGINT;
BEGIN
    SELECT b.status, b.current_revision_id, p.revision_id
        INTO batch_status, current_revision, publication_revision
        FROM payslip_publication p JOIN payslip_batch_revision r ON r.id = p.revision_id
        JOIN payslip_batch b ON b.id = r.batch_id
        WHERE p.id = NEW.publication_id AND p.employee_id = NEW.employee_id
            AND p.period_start = NEW.period_start FOR UPDATE OF b;
    IF batch_status IS DISTINCT FROM 'REVIEWED' OR current_revision IS DISTINCT FROM publication_revision THEN
        RAISE EXCEPTION 'Payslip current pointer requires a new reviewed publication' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER guard_payslip_current BEFORE INSERT OR UPDATE ON payslip_current
    FOR EACH ROW EXECUTE FUNCTION guard_payslip_current_change();

CREATE FUNCTION require_payslip_publication_committed() RETURNS trigger AS $$
DECLARE batch_status VARCHAR(16);
BEGIN
    SELECT b.status INTO batch_status FROM payslip_current c
        JOIN payslip_publication p ON p.id = c.publication_id
        JOIN payslip_batch_revision r ON r.id = p.revision_id
        JOIN payslip_batch b ON b.id = r.batch_id
        WHERE c.employee_id = NEW.employee_id AND c.period_start = NEW.period_start;
    IF batch_status IS DISTINCT FROM 'PUBLISHED' THEN
        RAISE EXCEPTION 'Payslip current pointer requires an atomically published batch' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;
CREATE CONSTRAINT TRIGGER require_payslip_publication_committed
    AFTER INSERT OR UPDATE ON payslip_current DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION require_payslip_publication_committed();

CREATE FUNCTION reject_payslip_history_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Payslip publication history is immutable' USING ERRCODE = '42501';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER reject_payslip_publication_mutation BEFORE UPDATE OR DELETE OR TRUNCATE ON payslip_publication
    FOR EACH STATEMENT EXECUTE FUNCTION reject_payslip_history_mutation();
CREATE TRIGGER reject_payslip_revision_truncate BEFORE TRUNCATE ON payslip_batch_revision
    FOR EACH STATEMENT EXECUTE FUNCTION reject_payslip_history_mutation();
CREATE TRIGGER reject_payslip_item_truncate BEFORE TRUNCATE ON payslip_batch_item
    FOR EACH STATEMENT EXECUTE FUNCTION reject_payslip_history_mutation();
