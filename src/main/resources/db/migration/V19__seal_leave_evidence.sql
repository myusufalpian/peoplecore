CREATE FUNCTION guard_leave_rule_insert() RETURNS trigger AS $$
DECLARE activated BOOLEAN;
BEGIN
    SELECT EXISTS (SELECT 1 FROM leave_rule_activation a WHERE a.rule_set_id = NEW.rule_set_id)
        INTO activated;
    IF activated THEN
        RAISE EXCEPTION 'Activated leave rules are immutable' USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_leave_rule_no_activated_insert BEFORE INSERT ON leave_rule
    FOR EACH ROW EXECUTE FUNCTION guard_leave_rule_insert();

CREATE FUNCTION guard_leave_portion_insert() RETURNS trigger AS $$
DECLARE editable BOOLEAN;
BEGIN
    SELECT EXISTS (SELECT 1 FROM leave_request r
        WHERE r.id = NEW.request_id AND r.status IN ('DRAFT', 'RETURNED'))
        INTO editable;
    IF NOT editable THEN
        RAISE EXCEPTION 'Submitted leave portions are immutable' USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_leave_portion_no_submitted_insert BEFORE INSERT ON leave_portion
    FOR EACH ROW EXECUTE FUNCTION guard_leave_portion_insert();

CREATE FUNCTION guard_leave_decision_insert() RETURNS trigger AS $$
DECLARE pending BOOLEAN;
BEGIN
    IF NEW.step NOT IN (1, 2) THEN
        RAISE EXCEPTION 'Leave decisions require step 1 or 2' USING ERRCODE = '42501';
    END IF;
    SELECT EXISTS (SELECT 1 FROM leave_request r
        WHERE r.id = NEW.request_id AND r.status = 'PENDING')
        INTO pending;
    IF NOT pending THEN
        RAISE EXCEPTION 'Leave decisions require a pending request' USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_leave_decision_no_nonpending_insert BEFORE INSERT ON leave_decision
    FOR EACH ROW EXECUTE FUNCTION guard_leave_decision_insert();

CREATE TRIGGER protect_leave_reservation BEFORE UPDATE OR DELETE ON leave_reservation
    FOR EACH ROW EXECUTE FUNCTION protect_leave_active_evidence();

CREATE FUNCTION guard_leave_rule_current() RETURNS trigger AS $$
BEGIN
    IF NEW.id <> 1 THEN
        RAISE EXCEPTION 'Leave rule pointer must remain singular' USING ERRCODE = '42501';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM leave_rule_activation a WHERE a.rule_set_id = NEW.rule_set_id) THEN
        RAISE EXCEPTION 'Leave rule pointer requires an activated rule set' USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER guard_leave_rule_current BEFORE INSERT OR UPDATE ON leave_rule_current
    FOR EACH ROW EXECUTE FUNCTION guard_leave_rule_current();
