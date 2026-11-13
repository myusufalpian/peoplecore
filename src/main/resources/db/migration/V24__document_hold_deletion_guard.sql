CREATE FUNCTION guard_document_deletion_against_hold() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.deletion_state IS DISTINCT FROM OLD.deletion_state
       AND NEW.deletion_state IN ('CLAIMED', 'DELETED')
       AND EXISTS (SELECT 1 FROM document_hold h WHERE h.document_id = OLD.id AND h.released_at IS NULL) THEN
        RAISE EXCEPTION 'Document has an active legal hold' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER guard_document_deletion_against_hold BEFORE UPDATE ON private_document
    FOR EACH ROW EXECUTE FUNCTION guard_document_deletion_against_hold();
