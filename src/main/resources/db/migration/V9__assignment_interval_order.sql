ALTER TABLE employee_assignments
    ADD CONSTRAINT employee_assignments_valid_interval
    CHECK (valid_to IS NULL OR valid_from < valid_to);
