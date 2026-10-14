-- V12 seals submitted profile revision content against later extension.
--
-- profile_revision_change rows are append-only, but the immutable-history
-- trigger rejects only UPDATE/DELETE/TRUNCATE, not INSERT. A sealed revision
-- owns its complete field set: content is written together with the revision
-- inside one transaction and the row is sealed before commit. Any later INSERT
-- referencing a sealed revision is rejected, including concurrent runtime SQL
-- that only passed the parent-status check before the seal committed, because
-- the inserting transaction blocks on the parent row lock taken below and
-- re-evaluates the seal afterwards.
--
-- All pre-existing revisions hold complete submitted content written
-- atomically at creation time, so they are sealed as part of this upgrade.
-- Future content always arrives as a new revision row; there is no unseal
-- operation.
--
-- Ordering matters: the V11 statement trigger on profile_revision rejects
-- every UPDATE, including the backfill below. The replacement row-level
-- enforcement is therefore installed before the backfill runs, so the
-- FALSE->TRUE seal transition it performs is the single permitted mutation.

ALTER TABLE profile_revision ADD COLUMN sealed BOOLEAN NOT NULL DEFAULT FALSE;

CREATE FUNCTION reject_sealed_profile_change_insert() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM 1 FROM profile_revision WHERE id = NEW.revision_id FOR UPDATE;
    IF EXISTS (SELECT 1 FROM profile_revision WHERE id = NEW.revision_id AND sealed) THEN
        RAISE EXCEPTION 'Submitted profile revision content is sealed' USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_profile_revision_change_sealed ON profile_revision_change;
CREATE TRIGGER trg_profile_revision_change_sealed
BEFORE INSERT ON profile_revision_change
FOR EACH ROW EXECUTE FUNCTION reject_sealed_profile_change_insert();

-- The V11 statement trigger on profile_revision rejects every UPDATE,
-- including the controlled FALSE->TRUE seal transition performed by the
-- creation path inside the same transaction that wrote the content.
-- Replace it with row-level enforcement: DELETE is always rejected and an
-- UPDATE is allowed only when it flips exactly the seal flag while every
-- other column stays identical. TRUNCATE keeps statement-level protection.
CREATE FUNCTION reject_profile_revision_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Profile history rows are append-only' USING ERRCODE = '42501';
    END IF;
    IF OLD.sealed = FALSE AND NEW.sealed = TRUE
        AND OLD.id IS NOT DISTINCT FROM NEW.id
        AND OLD.public_id IS NOT DISTINCT FROM NEW.public_id
        AND OLD.proposal_id IS NOT DISTINCT FROM NEW.proposal_id
        AND OLD.revision_number IS NOT DISTINCT FROM NEW.revision_number
        AND OLD.rule_set_public_id IS NOT DISTINCT FROM NEW.rule_set_public_id
        AND OLD.evaluated_at IS NOT DISTINCT FROM NEW.evaluated_at
        AND OLD.evaluated_org_unit IS NOT DISTINCT FROM NEW.evaluated_org_unit
        AND OLD.evaluated_job_level IS NOT DISTINCT FROM NEW.evaluated_job_level
        AND OLD.route_reviewer_account_public_id IS NOT DISTINCT FROM NEW.route_reviewer_account_public_id
        AND OLD.blocked_reason IS NOT DISTINCT FROM NEW.blocked_reason
        AND OLD.created_at IS NOT DISTINCT FROM NEW.created_at THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'Profile history rows are append-only' USING ERRCODE = '42501';
END;
$$;

DROP TRIGGER IF EXISTS trg_profile_revision_immutable ON profile_revision;
CREATE TRIGGER trg_profile_revision_immutable
BEFORE UPDATE OR DELETE ON profile_revision
FOR EACH ROW EXECUTE FUNCTION reject_profile_revision_mutation();

DROP TRIGGER IF EXISTS trg_profile_revision_truncate_immutable ON profile_revision;
CREATE TRIGGER trg_profile_revision_truncate_immutable
BEFORE TRUNCATE ON profile_revision
FOR EACH STATEMENT EXECUTE FUNCTION reject_profile_history_mutation();

UPDATE profile_revision SET sealed = TRUE;
