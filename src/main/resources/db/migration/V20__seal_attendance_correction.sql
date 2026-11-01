CREATE FUNCTION protect_attendance_correction() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Attendance corrections cannot be deleted' USING ERRCODE = '42501';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id
        OR NEW.public_id IS DISTINCT FROM OLD.public_id
        OR NEW.employee_id IS DISTINCT FROM OLD.employee_id
        OR NEW.employee_public_id IS DISTINCT FROM OLD.employee_public_id
        OR NEW.work_date IS DISTINCT FROM OLD.work_date
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Attendance correction identity is immutable' USING ERRCODE = '42501';
    END IF;
    IF NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'Attendance correction version must advance once' USING ERRCODE = '42501';
    END IF;
    IF OLD.status NOT IN ('DRAFT', 'RETURNED')
        AND (NEW.proposed_in IS DISTINCT FROM OLD.proposed_in
            OR NEW.proposed_out IS DISTINCT FROM OLD.proposed_out
            OR NEW.reason IS DISTINCT FROM OLD.reason
            OR NEW.active_revision_id IS DISTINCT FROM OLD.active_revision_id) THEN
        RAISE EXCEPTION 'Submitted attendance correction content is immutable' USING ERRCODE = '42501';
    END IF;
    IF NEW.status <> OLD.status AND NOT (
        (OLD.status = 'DRAFT' AND NEW.status IN ('PENDING', 'DISCARDED'))
        OR (OLD.status = 'RETURNED' AND NEW.status IN ('PENDING', 'WITHDRAWN'))
        OR (OLD.status = 'PENDING' AND NEW.status IN ('RETURNED', 'REJECTED', 'WITHDRAWN', 'APPROVED'))) THEN
        RAISE EXCEPTION 'Invalid attendance correction status transition' USING ERRCODE = '42501';
    END IF;
    IF OLD.status = 'PENDING' AND NEW.status IN ('RETURNED', 'REJECTED', 'APPROVED')
        AND NOT EXISTS (SELECT 1 FROM attendance_correction_decision d
            WHERE d.revision_id = NEW.active_revision_id) THEN
        RAISE EXCEPTION 'Correction decision evidence is required' USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_attendance_correction_protected BEFORE UPDATE OR DELETE ON attendance_correction
    FOR EACH ROW EXECUTE FUNCTION protect_attendance_correction();
