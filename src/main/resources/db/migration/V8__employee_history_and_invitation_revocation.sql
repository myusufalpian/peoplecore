ALTER TABLE employees
    ADD COLUMN employment_start_date DATE,
    ADD COLUMN employment_end_date DATE,
    ADD CONSTRAINT ck_employment_dates CHECK (
        employment_end_date IS NULL OR (
            employment_start_date IS NOT NULL AND employment_end_date >= employment_start_date));

ALTER TABLE employee_assignments
    ADD COLUMN superseded_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN supersedes_id UUID REFERENCES employee_assignments(public_id);

CREATE INDEX idx_employee_assignments_current_revision
    ON employee_assignments(employee_id, valid_from) WHERE superseded_at IS NULL;

-- Legacy revocation instants are unknown and must not be fabricated.
ALTER TABLE enrollment_invitations ADD COLUMN revoked_at TIMESTAMP WITH TIME ZONE;
