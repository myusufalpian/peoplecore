CREATE FUNCTION advance_authorization_fence() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    UPDATE auth_generation SET generation=generation+1 WHERE id=1;
    IF NOT FOUND THEN RAISE EXCEPTION 'Authorization generation unavailable'; END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER role_authorization_fence AFTER INSERT OR UPDATE OR DELETE ON role_assignments
FOR EACH STATEMENT EXECUTE FUNCTION advance_authorization_fence();
CREATE TRIGGER binding_authorization_fence AFTER INSERT OR UPDATE OR DELETE ON account_bindings
FOR EACH STATEMENT EXECUTE FUNCTION advance_authorization_fence();
CREATE TRIGGER assignment_authorization_fence AFTER INSERT OR UPDATE OR DELETE ON employee_assignments
FOR EACH STATEMENT EXECUTE FUNCTION advance_authorization_fence();
CREATE TRIGGER employee_authorization_fence AFTER INSERT OR UPDATE OR DELETE ON employees
FOR EACH STATEMENT EXECUTE FUNCTION advance_authorization_fence();
CREATE TRIGGER account_insert_authorization_fence AFTER INSERT OR DELETE ON user_accounts
FOR EACH STATEMENT EXECUTE FUNCTION advance_authorization_fence();
CREATE TRIGGER account_update_authorization_fence AFTER UPDATE OF status, access_ends_at ON user_accounts
FOR EACH STATEMENT EXECUTE FUNCTION advance_authorization_fence();
CREATE TRIGGER schedule_authorization_fence AFTER INSERT OR UPDATE OR DELETE ON employee_schedule_assignment
FOR EACH STATEMENT EXECUTE FUNCTION advance_authorization_fence();
CREATE TRIGGER calendar_authorization_fence AFTER INSERT OR UPDATE OR DELETE ON employee_calendar_assignment
FOR EACH STATEMENT EXECUTE FUNCTION advance_authorization_fence();

CREATE TRIGGER reimbursement_claim_authorization_fence AFTER INSERT OR UPDATE OR DELETE ON reimbursement_claim
FOR EACH STATEMENT EXECUTE FUNCTION advance_authorization_fence();
CREATE TRIGGER reimbursement_assignment_authorization_fence AFTER INSERT OR UPDATE OR DELETE ON reimbursement_review_assignment
FOR EACH STATEMENT EXECUTE FUNCTION advance_authorization_fence();
CREATE TRIGGER reimbursement_decision_authorization_fence AFTER INSERT OR UPDATE OR DELETE ON reimbursement_decision
FOR EACH STATEMENT EXECUTE FUNCTION advance_authorization_fence();

CREATE TRIGGER leave_request_authorization_fence AFTER INSERT OR UPDATE OR DELETE ON leave_request
FOR EACH STATEMENT EXECUTE FUNCTION advance_authorization_fence();
CREATE TRIGGER leave_assignment_authorization_fence AFTER INSERT OR UPDATE OR DELETE ON leave_review_assignment
FOR EACH STATEMENT EXECUTE FUNCTION advance_authorization_fence();
CREATE TRIGGER leave_decision_authorization_fence AFTER INSERT OR UPDATE OR DELETE ON leave_decision
FOR EACH STATEMENT EXECUTE FUNCTION advance_authorization_fence();
CREATE TRIGGER leave_cancellation_authorization_fence AFTER INSERT OR UPDATE OR DELETE ON leave_cancellation
FOR EACH STATEMENT EXECUTE FUNCTION advance_authorization_fence();
CREATE TRIGGER leave_cancel_assignment_authorization_fence AFTER INSERT OR UPDATE OR DELETE ON leave_cancellation_assignment
FOR EACH STATEMENT EXECUTE FUNCTION advance_authorization_fence();
CREATE TRIGGER leave_cancel_decision_authorization_fence AFTER INSERT OR UPDATE OR DELETE ON leave_cancellation_decision
FOR EACH STATEMENT EXECUTE FUNCTION advance_authorization_fence();

CREATE TRIGGER attendance_correction_authorization_fence AFTER INSERT OR UPDATE OR DELETE ON attendance_correction
FOR EACH STATEMENT EXECUTE FUNCTION advance_authorization_fence();
CREATE TRIGGER attendance_assignment_authorization_fence AFTER INSERT OR UPDATE OR DELETE ON attendance_correction_assignment
FOR EACH STATEMENT EXECUTE FUNCTION advance_authorization_fence();
CREATE TRIGGER attendance_decision_authorization_fence AFTER INSERT OR UPDATE OR DELETE ON attendance_correction_decision
FOR EACH STATEMENT EXECUTE FUNCTION advance_authorization_fence();

CREATE TRIGGER profile_proposal_authorization_fence AFTER INSERT OR UPDATE OR DELETE ON profile_proposal
FOR EACH STATEMENT EXECUTE FUNCTION advance_authorization_fence();
CREATE TRIGGER profile_assignment_authorization_fence AFTER INSERT OR UPDATE OR DELETE ON profile_assignment
FOR EACH STATEMENT EXECUTE FUNCTION advance_authorization_fence();
CREATE TRIGGER profile_decision_authorization_fence AFTER INSERT OR UPDATE OR DELETE ON profile_decision
FOR EACH STATEMENT EXECUTE FUNCTION advance_authorization_fence();
