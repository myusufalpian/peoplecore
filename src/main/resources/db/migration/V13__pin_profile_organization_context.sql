ALTER TABLE profile_revision ADD COLUMN org_assignment_public_id UUID;
ALTER TABLE profile_revision ADD COLUMN org_assignment_predecessor_public_id UUID;

CREATE OR REPLACE FUNCTION reject_profile_revision_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
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
        AND OLD.org_assignment_public_id IS NOT DISTINCT FROM NEW.org_assignment_public_id
        AND OLD.org_assignment_predecessor_public_id IS NOT DISTINCT FROM NEW.org_assignment_predecessor_public_id
        AND OLD.created_at IS NOT DISTINCT FROM NEW.created_at THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'Profile history rows are append-only' USING ERRCODE = '42501';
END;
$$;

