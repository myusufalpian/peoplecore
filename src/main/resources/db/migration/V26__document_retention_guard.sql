CREATE FUNCTION reject_retained_document_deletion() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE referenced BOOLEAN;
BEGIN
    IF NEW.deletion_state = 'CLAIMED' AND OLD.deletion_state = 'LIVE' THEN
        SELECT EXISTS (
            SELECT 1 FROM reimbursement_evidence e
            JOIN reimbursement_revision r ON r.claim_id = e.claim_id AND r.revision = e.revision
            WHERE e.document_version_id = OLD.current_version_id AND r.sealed
        ) OR EXISTS (
            SELECT 1 FROM payslip_publication p
            JOIN payslip_batch_item i ON i.id = p.item_id
            WHERE i.document_version_id = OLD.current_version_id
        ) OR EXISTS (
            SELECT 1 FROM payslip_batch_item i
            JOIN payslip_batch_revision r ON r.id = i.revision_id
            WHERE i.document_version_id = OLD.current_version_id AND r.status = 'REVIEWED'
        ) INTO referenced;
        IF referenced THEN
            RAISE EXCEPTION 'Document is under retention' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER guard_document_retention BEFORE UPDATE ON private_document
    FOR EACH ROW EXECUTE FUNCTION reject_retained_document_deletion();

CREATE FUNCTION reject_hold_on_purged_document() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM private_document d WHERE d.id = NEW.document_id AND d.deletion_state = 'DELETED') THEN
        RAISE EXCEPTION 'Document is already deleted' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER guard_document_hold_state BEFORE INSERT ON document_hold
    FOR EACH ROW EXECUTE FUNCTION reject_hold_on_purged_document();

ALTER TABLE private_document ADD CONSTRAINT ck_document_purged_both CHECK (
    (deletion_state = 'DELETED') = (purged_at IS NOT NULL)
) NOT VALID;
