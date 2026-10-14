-- V10__profile_approval_rules.sql
-- bounded typed profile rule snapshots, immediate activation pointer/history.
-- request type fixed to PROFILE_CHANGE, at most 100 rules per set,
-- explicit priority, exact unit/job-level predicates, designated reviewer per rule.
-- Activation is immediate only; no scheduled/backdated columns exist.

CREATE TABLE IF NOT EXISTS profile_rule_sets (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE,
    request_type VARCHAR(32) NOT NULL DEFAULT 'PROFILE_CHANGE',
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT ck_profile_rule_sets_request_type CHECK (request_type = 'PROFILE_CHANGE'),
    CONSTRAINT ck_profile_rule_sets_status CHECK (status IN ('DRAFT', 'PUBLISHED'))
);

CREATE TABLE IF NOT EXISTS profile_rules (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    rule_set_id BIGINT NOT NULL REFERENCES profile_rule_sets (id) ON DELETE CASCADE,
    rule_id VARCHAR(64) NOT NULL,
    priority INT NOT NULL,
    org_unit VARCHAR(128),
    job_level VARCHAR(64),
    reviewer_account_public_id UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_profile_rules_set_rule UNIQUE (rule_set_id, rule_id),
    CONSTRAINT ck_profile_rules_rule_id CHECK (rule_id <> ''),
    CONSTRAINT ck_profile_rules_org_unit CHECK (org_unit IS NULL OR org_unit <> ''),
    CONSTRAINT ck_profile_rules_job_level CHECK (job_level IS NULL OR job_level <> '')
);

CREATE INDEX IF NOT EXISTS idx_profile_rules_set ON profile_rules (rule_set_id);

CREATE TABLE IF NOT EXISTS profile_rule_activations (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    rule_set_id BIGINT NOT NULL REFERENCES profile_rule_sets (id),
    activated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    activated_by VARCHAR(128) NOT NULL,
    CONSTRAINT ck_profile_rule_activations_actor CHECK (activated_by <> '')
);

CREATE INDEX IF NOT EXISTS idx_profile_rule_activations_set
    ON profile_rule_activations (rule_set_id, activated_at);

CREATE TABLE IF NOT EXISTS profile_rule_current_activation (
    id INT PRIMARY KEY CHECK (id = 1),
    rule_set_id BIGINT NOT NULL REFERENCES profile_rule_sets (id),
    activated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    activated_by VARCHAR(128) NOT NULL
);

-- Activated content is immutable: no rule may be inserted into, updated
-- within, deleted from, or moved out of a published set; publish a new
-- version instead. Both parent identities are checked so reparenting a
-- published rule into a draft set is denied as well. Every affected parent
-- row is locked before the status check so a concurrent publication cannot
-- commit between the check and the child write; publishers lock the same
-- parent row when flipping status, in stable id order where two parents
-- are involved.
CREATE OR REPLACE FUNCTION reject_published_profile_rule_write()
RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        PERFORM 1 FROM profile_rule_sets WHERE id = NEW.rule_set_id FOR UPDATE;
        IF EXISTS (SELECT 1 FROM profile_rule_sets s
                   WHERE s.id = NEW.rule_set_id AND s.status = 'PUBLISHED') THEN
            RAISE EXCEPTION 'Published profile rule content is immutable';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        PERFORM 1 FROM profile_rule_sets WHERE id = OLD.rule_set_id FOR UPDATE;
        IF EXISTS (SELECT 1 FROM profile_rule_sets s
                   WHERE s.id = OLD.rule_set_id AND s.status = 'PUBLISHED') THEN
            RAISE EXCEPTION 'Published profile rule content is immutable';
        END IF;
        RETURN OLD;
    END IF;
    IF OLD.rule_set_id = NEW.rule_set_id THEN
        PERFORM 1 FROM profile_rule_sets WHERE id = NEW.rule_set_id FOR UPDATE;
    ELSIF OLD.rule_set_id < NEW.rule_set_id THEN
        PERFORM 1 FROM profile_rule_sets WHERE id = OLD.rule_set_id FOR UPDATE;
        PERFORM 1 FROM profile_rule_sets WHERE id = NEW.rule_set_id FOR UPDATE;
    ELSE
        PERFORM 1 FROM profile_rule_sets WHERE id = NEW.rule_set_id FOR UPDATE;
        PERFORM 1 FROM profile_rule_sets WHERE id = OLD.rule_set_id FOR UPDATE;
    END IF;
    IF EXISTS (SELECT 1 FROM profile_rule_sets s
               WHERE s.id IN (OLD.rule_set_id, NEW.rule_set_id) AND s.status = 'PUBLISHED') THEN
        RAISE EXCEPTION 'Published profile rule content is immutable';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_profile_rules_immutable ON profile_rules;
CREATE TRIGGER trg_profile_rules_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON profile_rules
    FOR EACH ROW EXECUTE FUNCTION reject_published_profile_rule_write();

-- Published sets are frozen; drafts keep request type fixed and may only
-- transition DRAFT -> PUBLISHED via the publish path.
CREATE OR REPLACE FUNCTION reject_published_profile_rule_set_write()
RETURNS trigger AS $$
BEGIN
    IF OLD.status = 'PUBLISHED' THEN
        RAISE EXCEPTION 'Published profile rule content is immutable';
    END IF;
    IF NEW.request_type IS DISTINCT FROM OLD.request_type THEN
        RAISE EXCEPTION 'Profile rule request type is fixed';
    END IF;
    IF NEW.status <> 'DRAFT' AND NEW.status <> 'PUBLISHED' THEN
        RAISE EXCEPTION 'Unknown profile rule set status';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_profile_rule_sets_immutable ON profile_rule_sets;
CREATE TRIGGER trg_profile_rule_sets_immutable
    BEFORE UPDATE ON profile_rule_sets
    FOR EACH ROW EXECUTE FUNCTION reject_published_profile_rule_set_write();
