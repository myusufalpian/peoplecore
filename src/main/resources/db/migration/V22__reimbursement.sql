CREATE TABLE reimbursement_category_revision (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE,
    category_code VARCHAR(128) NOT NULL CHECK (length(btrim(category_code)) > 0),
    effective_from DATE NOT NULL,
    effective_to DATE,
    claim_limit BIGINT NOT NULL CHECK (claim_limit > 0),
    superseded BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CHECK (effective_to IS NULL OR effective_from < effective_to)
);

CREATE FUNCTION guard_reimbursement_category_insert() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended('reimbursement-category:' || NEW.category_code, 0));
    IF EXISTS (SELECT 1 FROM reimbursement_category_revision c
        WHERE c.category_code = NEW.category_code AND NOT c.superseded
          AND (c.effective_to IS NULL OR NEW.effective_from < c.effective_to)
          AND (NEW.effective_to IS NULL OR c.effective_from < NEW.effective_to)) THEN
        RAISE EXCEPTION 'Reimbursement category intervals overlap' USING ERRCODE = '23505';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER guard_reimbursement_category_insert BEFORE INSERT ON reimbursement_category_revision
    FOR EACH ROW EXECUTE FUNCTION guard_reimbursement_category_insert();

CREATE FUNCTION guard_reimbursement_category_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP <> 'UPDATE' OR OLD.superseded OR NOT NEW.superseded
       OR (to_jsonb(NEW) - 'superseded') IS DISTINCT FROM (to_jsonb(OLD) - 'superseded') THEN
        RAISE EXCEPTION 'Reimbursement category history is immutable' USING ERRCODE = '42501';
    END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended('reimbursement-category:' || OLD.category_code, 0));
    RETURN NEW;
END;
$$;
CREATE TRIGGER guard_reimbursement_category_update_delete BEFORE UPDATE OR DELETE ON reimbursement_category_revision
    FOR EACH ROW EXECUTE FUNCTION guard_reimbursement_category_change();

CREATE TABLE reimbursement_account (
    employee_id BIGINT PRIMARY KEY REFERENCES employees(id)
);

CREATE TABLE reimbursement_claim (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE,
    employee_id BIGINT NOT NULL REFERENCES employees(id),
    status VARCHAR(16) NOT NULL CHECK (status IN ('DRAFT','PENDING','RETURNED','REJECTED','WITHDRAWN','APPROVED','PAID')),
    revision INTEGER NOT NULL DEFAULT 0 CHECK (revision >= 0),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    category_code VARCHAR(128) NOT NULL CHECK (length(btrim(category_code)) > 0),
    transaction_date DATE NOT NULL,
    amount BIGINT NOT NULL CHECK (amount > 0),
    description VARCHAR(2000) NOT NULL CHECK (length(btrim(description)) > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_reimbursement_claim_owner ON reimbursement_claim(employee_id, id);

CREATE FUNCTION protect_reimbursement_claim() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Reimbursement claim cannot be deleted' USING ERRCODE = '42501';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.public_id IS DISTINCT FROM OLD.public_id
       OR NEW.employee_id IS DISTINCT FROM OLD.employee_id OR NEW.created_at IS DISTINCT FROM OLD.created_at
       OR NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'Reimbursement claim identity or version is invalid' USING ERRCODE = '42501';
    END IF;
    IF OLD.status NOT IN ('DRAFT','RETURNED') AND
       (NEW.category_code IS DISTINCT FROM OLD.category_code OR NEW.transaction_date IS DISTINCT FROM OLD.transaction_date
        OR NEW.amount IS DISTINCT FROM OLD.amount OR NEW.description IS DISTINCT FROM OLD.description) THEN
        RAISE EXCEPTION 'Submitted reimbursement content is immutable' USING ERRCODE = '42501';
    END IF;
    IF NEW.revision <> OLD.revision AND NOT
        (OLD.status IN ('DRAFT','RETURNED') AND NEW.status='PENDING' AND NEW.revision=OLD.revision+1) THEN
        RAISE EXCEPTION 'Invalid reimbursement revision transition' USING ERRCODE = '42501';
    END IF;
    IF OLD.status IN ('DRAFT','RETURNED') AND NEW.status='PENDING' AND NEW.revision<>OLD.revision+1 THEN
        RAISE EXCEPTION 'Reimbursement submission requires a new revision' USING ERRCODE = '42501';
    END IF;
    IF NEW.status <> OLD.status AND NOT
        ((OLD.status IN ('DRAFT','RETURNED') AND NEW.status IN ('PENDING','WITHDRAWN'))
         OR (OLD.status='PENDING' AND NEW.status IN ('RETURNED','REJECTED','WITHDRAWN','APPROVED'))
         OR (OLD.status='APPROVED' AND NEW.status='PAID')) THEN
        RAISE EXCEPTION 'Invalid reimbursement status transition' USING ERRCODE = '42501';
    END IF;
    IF NEW.status='PENDING' AND OLD.status <> 'PENDING' AND NOT EXISTS
        (SELECT 1 FROM reimbursement_revision r WHERE r.claim_id=OLD.id AND r.revision=NEW.revision AND r.sealed) THEN
        RAISE EXCEPTION 'Submitted reimbursement requires a sealed revision' USING ERRCODE = '42501';
    END IF;
    IF NEW.status='APPROVED' AND OLD.status <> 'APPROVED' AND
       (SELECT count(id) FROM reimbursement_decision WHERE claim_id=OLD.id AND revision=OLD.revision AND outcome='APPROVE') <> 2 THEN
        RAISE EXCEPTION 'Approval requires two review decisions' USING ERRCODE = '42501';
    END IF;
    IF NEW.status IN ('RETURNED','REJECTED') AND NEW.status <> OLD.status AND NOT EXISTS
       (SELECT 1 FROM reimbursement_decision WHERE claim_id=OLD.id AND revision=OLD.revision
        AND outcome=CASE WHEN NEW.status='RETURNED' THEN 'RETURN' ELSE 'REJECT' END) THEN
        RAISE EXCEPTION 'Status requires the corresponding decision' USING ERRCODE = '42501';
    END IF;
    IF NEW.status='PAID' AND OLD.status <> 'PAID' AND NOT EXISTS
       (SELECT 1 FROM reimbursement_payment WHERE claim_id=OLD.id AND revision=OLD.revision AND amount=OLD.amount) THEN
        RAISE EXCEPTION 'Paid status requires the full payment record' USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TABLE reimbursement_draft_evidence (
    claim_id BIGINT NOT NULL REFERENCES reimbursement_claim(id),
    document_public_id UUID NOT NULL,
    PRIMARY KEY (claim_id, document_public_id)
);

CREATE FUNCTION guard_reimbursement_draft_evidence() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE claim_status VARCHAR(16);
BEGIN
    SELECT status INTO claim_status FROM reimbursement_claim WHERE id = COALESCE(NEW.claim_id, OLD.claim_id) FOR UPDATE;
    IF claim_status IS NULL OR claim_status NOT IN ('DRAFT','RETURNED') THEN
        RAISE EXCEPTION 'Draft evidence cannot change after submission' USING ERRCODE = '42501';
    END IF;
    RETURN COALESCE(NEW, OLD);
END;
$$;
CREATE TRIGGER guard_reimbursement_draft_evidence BEFORE INSERT OR UPDATE OR DELETE ON reimbursement_draft_evidence
    FOR EACH ROW EXECUTE FUNCTION guard_reimbursement_draft_evidence();

CREATE TABLE reimbursement_revision (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    claim_id BIGINT NOT NULL REFERENCES reimbursement_claim(id),
    revision INTEGER NOT NULL CHECK (revision > 0),
    category_revision_id BIGINT NOT NULL REFERENCES reimbursement_category_revision(id),
    transaction_date DATE NOT NULL,
    amount BIGINT NOT NULL CHECK (amount > 0),
    description VARCHAR(2000) NOT NULL CHECK (length(btrim(description)) > 0),
    org_unit VARCHAR(128) NOT NULL CHECK (length(btrim(org_unit)) > 0),
    manager_employee_public_id UUID,
    submitted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    sealed BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (claim_id, revision)
);
CREATE TRIGGER protect_reimbursement_claim BEFORE UPDATE OR DELETE ON reimbursement_claim
    FOR EACH ROW EXECUTE FUNCTION protect_reimbursement_claim();

CREATE TABLE reimbursement_evidence (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    claim_id BIGINT NOT NULL,
    revision INTEGER NOT NULL,
    document_version_id BIGINT NOT NULL REFERENCES document_version(id),
    FOREIGN KEY (claim_id, revision) REFERENCES reimbursement_revision(claim_id, revision),
    UNIQUE (claim_id, revision, document_version_id)
);

CREATE FUNCTION guard_reimbursement_revision_insert() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE parent_status VARCHAR(16);
DECLARE parent_revision INTEGER;
DECLARE parent_category VARCHAR(128);
DECLARE parent_date DATE;
DECLARE parent_amount BIGINT;
DECLARE parent_description VARCHAR(2000);
DECLARE policy_code VARCHAR(128);
DECLARE category_from DATE;
DECLARE category_to DATE;
DECLARE category_limit BIGINT;
DECLARE category_superseded BOOLEAN;
BEGIN
    SELECT status, revision, category_code, transaction_date, amount, description
      INTO parent_status, parent_revision, parent_category, parent_date, parent_amount, parent_description
      FROM reimbursement_claim WHERE id=NEW.claim_id FOR UPDATE;
    SELECT c.category_code, c.effective_from, c.effective_to, c.claim_limit, c.superseded
      INTO policy_code, category_from, category_to, category_limit, category_superseded
      FROM reimbursement_category_revision c WHERE c.id=NEW.category_revision_id FOR SHARE;
    IF NEW.sealed OR parent_status NOT IN ('DRAFT','RETURNED') OR NEW.revision <> parent_revision + 1
       OR parent_category IS DISTINCT FROM policy_code OR category_superseded IS DISTINCT FROM FALSE
       OR NEW.transaction_date IS DISTINCT FROM parent_date OR NEW.amount IS DISTINCT FROM parent_amount
       OR NEW.description IS DISTINCT FROM parent_description OR NEW.transaction_date < category_from
       OR (category_to IS NOT NULL AND NEW.transaction_date >= category_to) OR NEW.amount > category_limit THEN
        RAISE EXCEPTION 'Invalid reimbursement revision snapshot' USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER guard_reimbursement_revision_insert BEFORE INSERT ON reimbursement_revision
    FOR EACH ROW EXECUTE FUNCTION guard_reimbursement_revision_insert();

CREATE FUNCTION guard_reimbursement_evidence_insert() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE claim_owner BIGINT;
DECLARE revision_sealed BOOLEAN;
DECLARE document_owner BIGINT;
DECLARE document_purpose VARCHAR(32);
DECLARE document_status VARCHAR(32);
DECLARE document_deletion VARCHAR(16);
DECLARE document_current BIGINT;
BEGIN
    SELECT c.employee_id, r.sealed INTO claim_owner, revision_sealed
      FROM reimbursement_claim c JOIN reimbursement_revision r ON r.claim_id = c.id
      WHERE c.id = NEW.claim_id AND r.revision = NEW.revision FOR UPDATE OF c, r;
    SELECT d.employee_id, d.purpose, d.status, d.deletion_state, d.current_version_id
      INTO document_owner, document_purpose, document_status, document_deletion, document_current
      FROM private_document d JOIN document_version v ON v.document_id = d.id
      WHERE v.id = NEW.document_version_id FOR UPDATE OF d;
    IF claim_owner IS NULL OR revision_sealed IS DISTINCT FROM FALSE
       OR document_owner IS DISTINCT FROM claim_owner OR document_purpose IS DISTINCT FROM 'CLAIM_EVIDENCE'
       OR document_status IS DISTINCT FROM 'VERIFIED' OR document_deletion IS DISTINCT FROM 'LIVE'
       OR document_current IS DISTINCT FROM NEW.document_version_id
       OR (SELECT count(id) FROM reimbursement_evidence WHERE claim_id=NEW.claim_id AND revision=NEW.revision) >= 5 THEN
        RAISE EXCEPTION 'Invalid reimbursement evidence' USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER guard_reimbursement_evidence_insert BEFORE INSERT ON reimbursement_evidence
    FOR EACH ROW EXECUTE FUNCTION guard_reimbursement_evidence_insert();

CREATE FUNCTION guard_reimbursement_revision_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP <> 'UPDATE' OR OLD.sealed OR NOT NEW.sealed
       OR (to_jsonb(NEW) - 'sealed') IS DISTINCT FROM (to_jsonb(OLD) - 'sealed')
       OR NOT EXISTS (SELECT 1 FROM reimbursement_evidence e WHERE e.claim_id=OLD.claim_id AND e.revision=OLD.revision) THEN
        RAISE EXCEPTION 'Reimbursement revision is immutable' USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER guard_reimbursement_revision BEFORE UPDATE OR DELETE ON reimbursement_revision
    FOR EACH ROW EXECUTE FUNCTION guard_reimbursement_revision_change();

CREATE TABLE reimbursement_review_assignment (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    claim_id BIGINT NOT NULL,
    revision INTEGER NOT NULL,
    step INTEGER NOT NULL CHECK (step IN (1,2)),
    reviewer_account_public_id UUID,
    reviewer_employee_public_id UUID,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    FOREIGN KEY (claim_id, revision) REFERENCES reimbursement_revision(claim_id, revision),
    CHECK ((reviewer_account_public_id IS NULL) = (reviewer_employee_public_id IS NULL))
);
CREATE UNIQUE INDEX uk_reimbursement_active_review ON reimbursement_review_assignment(claim_id, revision, step) WHERE active;

CREATE FUNCTION guard_reimbursement_assignment_insert() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE parent_status VARCHAR(16);
DECLARE parent_revision INTEGER;
DECLARE owner_public_id UUID;
DECLARE revision_sealed BOOLEAN;
BEGIN
    SELECT c.status, c.revision, e.public_id INTO parent_status, parent_revision, owner_public_id
      FROM reimbursement_claim c JOIN employees e ON e.id=c.employee_id WHERE c.id=NEW.claim_id FOR UPDATE OF c;
    SELECT sealed INTO revision_sealed FROM reimbursement_revision WHERE claim_id=NEW.claim_id AND revision=NEW.revision;
    IF revision_sealed IS DISTINCT FROM TRUE OR NOT NEW.active
       OR NOT ((parent_status IN ('DRAFT','RETURNED') AND NEW.revision=parent_revision+1)
               OR (parent_status='PENDING' AND NEW.revision=parent_revision))
       OR owner_public_id=NEW.reviewer_employee_public_id
       OR EXISTS (SELECT 1 FROM reimbursement_decision WHERE claim_id=NEW.claim_id AND revision=NEW.revision AND step=NEW.step) THEN
        RAISE EXCEPTION 'Invalid reimbursement review assignment' USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER guard_reimbursement_assignment_insert BEFORE INSERT ON reimbursement_review_assignment
    FOR EACH ROW EXECUTE FUNCTION guard_reimbursement_assignment_insert();

CREATE FUNCTION guard_reimbursement_assignment_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP <> 'UPDATE' OR NOT OLD.active OR NEW.active
       OR (to_jsonb(NEW) - 'active') IS DISTINCT FROM (to_jsonb(OLD) - 'active') THEN
        RAISE EXCEPTION 'Reimbursement assignment provenance is immutable' USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER guard_reimbursement_assignment BEFORE UPDATE OR DELETE ON reimbursement_review_assignment
    FOR EACH ROW EXECUTE FUNCTION guard_reimbursement_assignment_change();

CREATE TABLE reimbursement_decision (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    claim_id BIGINT NOT NULL,
    revision INTEGER NOT NULL,
    step INTEGER NOT NULL CHECK (step IN (1,2)),
    reviewer_account_public_id UUID NOT NULL,
    reviewer_employee_public_id UUID NOT NULL,
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('APPROVE','REJECT','RETURN')),
    reason VARCHAR(2000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    FOREIGN KEY (claim_id, revision) REFERENCES reimbursement_revision(claim_id, revision),
    UNIQUE (claim_id, revision, step),
    CHECK (outcome = 'APPROVE' OR (reason IS NOT NULL AND length(btrim(reason)) > 0))
);

CREATE FUNCTION guard_reimbursement_decision_insert() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE parent_status VARCHAR(16);
DECLARE parent_revision INTEGER;
DECLARE owner_public_id UUID;
DECLARE assigned_account UUID;
DECLARE assigned_employee UUID;
DECLARE manager_employee UUID;
BEGIN
    SELECT c.status, c.revision, e.public_id INTO parent_status, parent_revision, owner_public_id
      FROM reimbursement_claim c JOIN employees e ON e.id=c.employee_id WHERE c.id=NEW.claim_id FOR UPDATE OF c;
    SELECT reviewer_account_public_id, reviewer_employee_public_id INTO assigned_account, assigned_employee
      FROM reimbursement_review_assignment WHERE claim_id=NEW.claim_id AND revision=NEW.revision AND step=NEW.step AND active;
    IF NEW.step = 2 THEN
        SELECT reviewer_employee_public_id INTO manager_employee FROM reimbursement_decision
          WHERE claim_id=NEW.claim_id AND revision=NEW.revision AND step=1 AND outcome='APPROVE';
    END IF;
    IF parent_status IS DISTINCT FROM 'PENDING' OR parent_revision IS DISTINCT FROM NEW.revision
       OR assigned_account IS DISTINCT FROM NEW.reviewer_account_public_id
       OR assigned_employee IS DISTINCT FROM NEW.reviewer_employee_public_id
       OR owner_public_id = NEW.reviewer_employee_public_id
       OR (NEW.step=2 AND (manager_employee IS NULL OR manager_employee=NEW.reviewer_employee_public_id)) THEN
        RAISE EXCEPTION 'Invalid reimbursement decision' USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER guard_reimbursement_decision_insert BEFORE INSERT ON reimbursement_decision
    FOR EACH ROW EXECUTE FUNCTION guard_reimbursement_decision_insert();

CREATE TABLE reimbursement_payment (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE,
    claim_id BIGINT NOT NULL UNIQUE REFERENCES reimbursement_claim(id),
    revision INTEGER NOT NULL,
    amount BIGINT NOT NULL CHECK (amount > 0),
    paid_date DATE NOT NULL,
    payment_reference VARCHAR(255) NOT NULL CHECK (length(btrim(payment_reference)) > 0),
    created_by UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    FOREIGN KEY (claim_id, revision) REFERENCES reimbursement_revision(claim_id, revision)
);

CREATE TABLE reimbursement_payment_correction (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE,
    payment_id BIGINT NOT NULL REFERENCES reimbursement_payment(id),
    prior_paid_date DATE NOT NULL,
    prior_reference VARCHAR(255) NOT NULL,
    corrected_paid_date DATE NOT NULL,
    corrected_reference VARCHAR(255) NOT NULL CHECK (length(btrim(corrected_reference)) > 0),
    reason VARCHAR(2000) NOT NULL CHECK (length(btrim(reason)) > 0),
    created_by UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE FUNCTION guard_reimbursement_payment_insert() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE parent_status VARCHAR(16);
DECLARE parent_revision INTEGER;
DECLARE revision_amount BIGINT;
DECLARE revision_sealed BOOLEAN;
BEGIN
    SELECT c.status, c.revision, r.amount, r.sealed
      INTO parent_status, parent_revision, revision_amount, revision_sealed
      FROM reimbursement_claim c JOIN reimbursement_revision r ON r.claim_id=c.id AND r.revision=NEW.revision
      WHERE c.id=NEW.claim_id FOR UPDATE OF c;
    IF parent_status IS DISTINCT FROM 'APPROVED' OR parent_revision IS DISTINCT FROM NEW.revision
       OR revision_sealed IS DISTINCT FROM TRUE OR revision_amount IS DISTINCT FROM NEW.amount
       OR (SELECT count(id) FROM reimbursement_decision WHERE claim_id=NEW.claim_id AND revision=NEW.revision AND outcome='APPROVE') <> 2 THEN
        RAISE EXCEPTION 'Payment requires the approved claim amount' USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER guard_reimbursement_payment_insert BEFORE INSERT ON reimbursement_payment
    FOR EACH ROW EXECUTE FUNCTION guard_reimbursement_payment_insert();

CREATE FUNCTION guard_reimbursement_payment_correction_insert() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE parent_status VARCHAR(16);
DECLARE last_date DATE;
DECLARE last_reference VARCHAR(255);
BEGIN
    SELECT c.status, p.paid_date, p.payment_reference INTO parent_status, last_date, last_reference
      FROM reimbursement_payment p JOIN reimbursement_claim c ON c.id=p.claim_id
      WHERE p.id=NEW.payment_id FOR UPDATE OF c;
    SELECT corrected_paid_date, corrected_reference INTO last_date, last_reference
      FROM reimbursement_payment_correction WHERE payment_id=NEW.payment_id ORDER BY id DESC LIMIT 1;
    IF NOT FOUND THEN
        SELECT paid_date, payment_reference INTO last_date, last_reference FROM reimbursement_payment WHERE id=NEW.payment_id;
    END IF;
    IF parent_status IS DISTINCT FROM 'PAID' OR last_date IS DISTINCT FROM NEW.prior_paid_date
       OR last_reference IS DISTINCT FROM NEW.prior_reference THEN
        RAISE EXCEPTION 'Payment correction history changed' USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER guard_reimbursement_payment_correction BEFORE INSERT ON reimbursement_payment_correction
    FOR EACH ROW EXECUTE FUNCTION guard_reimbursement_payment_correction_insert();

CREATE TABLE reimbursement_history (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE,
    claim_id BIGINT NOT NULL REFERENCES reimbursement_claim(id),
    revision INTEGER NOT NULL,
    action VARCHAR(64) NOT NULL,
    actor_public_id UUID NOT NULL,
    reason VARCHAR(2000),
    reviewer_public_id UUID,
    review_step INTEGER CHECK (review_step IS NULL OR review_step IN (1,2)),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE FUNCTION reject_reimbursement_history_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Reimbursement history is immutable' USING ERRCODE = '42501';
END;
$$;
DO $$ DECLARE target TEXT; BEGIN
    FOREACH target IN ARRAY ARRAY['reimbursement_evidence',
        'reimbursement_decision','reimbursement_payment','reimbursement_payment_correction','reimbursement_history'] LOOP
        EXECUTE format('CREATE TRIGGER protect_reimbursement_history BEFORE UPDATE OR DELETE OR TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION reject_reimbursement_history_mutation()',target);
    END LOOP;
END; $$;
CREATE TRIGGER protect_reimbursement_category_truncate BEFORE TRUNCATE ON reimbursement_category_revision
    FOR EACH STATEMENT EXECUTE FUNCTION reject_reimbursement_history_mutation();
CREATE TRIGGER protect_reimbursement_revision_truncate BEFORE TRUNCATE ON reimbursement_revision
    FOR EACH STATEMENT EXECUTE FUNCTION reject_reimbursement_history_mutation();
CREATE TRIGGER protect_reimbursement_assignment_truncate BEFORE TRUNCATE ON reimbursement_review_assignment
    FOR EACH STATEMENT EXECUTE FUNCTION reject_reimbursement_history_mutation();
