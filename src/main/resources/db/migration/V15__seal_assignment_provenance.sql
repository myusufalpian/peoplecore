-- Assignment provenance is immutable after insertion; the only permitted
-- change is the one-way supersession transition (superseded FALSE -> TRUE,
-- which records the revise reason). This stops a runtime SQL writer from
-- rewriting the revision, interval, ownership or lineage of an assignment
-- already referenced by a pinned occurrence.
CREATE OR REPLACE FUNCTION reject_assignment_provenance_change()
RETURNS trigger AS $$
BEGIN
    IF NEW.id <> OLD.id
        OR NEW.public_id <> OLD.public_id
        OR NEW.employee_id <> OLD.employee_id
        OR NEW.revision_id <> OLD.revision_id
        OR NEW.from_date <> OLD.from_date
        OR NEW.to_date IS DISTINCT FROM OLD.to_date
        OR NEW.supersedes_public_id IS DISTINCT FROM OLD.supersedes_public_id
        OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'Assignment provenance is immutable' USING ERRCODE = '42501';
    END IF;
    IF OLD.superseded OR NOT NEW.superseded THEN
        RAISE EXCEPTION 'Assignment provenance is immutable' USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_schedule_assignment_provenance ON employee_schedule_assignment;
CREATE TRIGGER trg_schedule_assignment_provenance
    BEFORE UPDATE ON employee_schedule_assignment
    FOR EACH ROW EXECUTE FUNCTION reject_assignment_provenance_change();

DROP TRIGGER IF EXISTS trg_calendar_assignment_provenance ON employee_calendar_assignment;
CREATE TRIGGER trg_calendar_assignment_provenance
    BEFORE UPDATE ON employee_calendar_assignment
    FOR EACH ROW EXECUTE FUNCTION reject_assignment_provenance_change();
